package dev.lovelace.loveessential.scoreboard;

import dev.lovelace.loveessential.scoreboard.PersonalState.Entry;
import java.text.Collator;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The lines a player can put on their own scoreboard, read from {@code scoreboard.*} in
 * scoreboard.yml. Texts keep the legacy {@code &} style of the original LoveTweaks configuration;
 * they are turned into components only when drawn.
 */
public record PersonalCatalog(
    String title,
    String top,
    String bottom,
    String separator,
    int max,
    List<String> disabledWorlds,
    Map<String, Category> categories,
    Map<String, Item> items,
    Map<String, Preset> presets,
    String defaultPreset) {

  public record Category(String id, String name, List<String> lore) {}

  public record Variant(String name, String template) {}

  /**
   * Unlock condition evaluated through PlaceholderAPI: with {@code equals} the value must match it
   * (ignoring case), without it the placeholder only has to resolve to something.
   */
  public record Requirement(String placeholder, String equals, String reason) {}

  public record Item(
      String id,
      String name,
      List<Variant> variants,
      List<String> lore,
      Requirement requirement,
      String category,
      Set<String> expansions) {
    public String template(int variant) {
      return variants.get(PersonalState.normalize(variant, variants.size())).template();
    }

    public String variantName(int variant) {
      return variants.get(PersonalState.normalize(variant, variants.size())).name();
    }
  }

  public record Preset(String id, String name, List<String> lore, List<Entry> entries) {}

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,48}");
  private static final Pattern EXPANSION = Pattern.compile("%([A-Za-z0-9]+)_[^%]*%");
  private static final int MAX_ITEMS = 256, MAX_VARIANTS = 8, MAX_CATEGORIES = 7;

  public static final PersonalCatalog EMPTY =
      new PersonalCatalog(
          "", "", "", "", 8, List.of(), Map.of(), Map.of(), Map.of(), "");

  public boolean isEmpty() {
    return items.isEmpty();
  }

  public Item item(String id) {
    return items.get(id);
  }

  public List<Item> itemsOf(String category) {
    var out = new ArrayList<Item>();
    for (Item item : items.values()) if (item.category().equals(category)) out.add(item);
    return out;
  }

  /** Reads {@code scoreboard.*}; a missing {@code placeholders} section is an empty catalog. */
  public static PersonalCatalog read(ConfigurationSection config) {
    var section = config == null ? null : config.getConfigurationSection("scoreboard");
    if (section == null || !section.isConfigurationSection("placeholders")) return EMPTY;
    var categories = new LinkedHashMap<String, Category>();
    var categorySection = section.getConfigurationSection("categories");
    if (categorySection != null)
      for (String id : categorySection.getKeys(false)) {
        var c = categorySection.getConfigurationSection(id);
        if (c == null || !ID.matcher(id).matches() || categories.size() >= MAX_CATEGORIES) continue;
        categories.put(id, new Category(id, c.getString("display-name", id), c.getStringList("lore")));
      }
    if (categories.isEmpty()) categories.put("general", new Category("general", "&bОбщее", List.of()));
    String firstCategory = categories.keySet().iterator().next();

    var items = new LinkedHashMap<String, Item>();
    var itemSection = section.getConfigurationSection("placeholders");
    for (String id : itemSection.getKeys(false)) {
      var p = itemSection.getConfigurationSection(id);
      if (p == null || !ID.matcher(id).matches() || items.size() >= MAX_ITEMS)
        throw new IllegalArgumentException("errors.config");
      var variants = new ArrayList<Variant>();
      for (Map<?, ?> raw : p.getMapList("variants")) {
        Object template = raw.get("template");
        if (template == null) continue;
        Object name = raw.get("name");
        variants.add(new Variant(name == null ? "" : name.toString(), template.toString()));
      }
      if (variants.isEmpty()) variants.add(new Variant("", p.getString("template", "")));
      if (variants.size() > MAX_VARIANTS) throw new IllegalArgumentException("errors.config");
      // The numeric sort-group of older files maps onto a category.
      String category = p.getString("category");
      if (category == null) category = categoryForSortGroup(p.getInt("sort-group", 0));
      if (!categories.containsKey(category)) category = firstCategory;
      Requirement requirement = null;
      var req = p.getConfigurationSection("requirement");
      if (req != null && !req.getString("placeholder", "").isBlank())
        requirement =
            new Requirement(
                req.getString("placeholder"),
                req.getString("equals", ""),
                req.getString("reason", ""));
      var expansions = new LinkedHashSet<String>();
      for (Variant v : variants) expansions.addAll(expansions(v.template()));
      if (requirement != null) expansions.addAll(expansions(requirement.placeholder()));
      items.put(
          id,
          new Item(
              id,
              p.getString("display-name", id),
              List.copyOf(variants),
              p.getStringList("lore"),
              requirement,
              category,
              Set.copyOf(expansions)));
    }

    var presets = new LinkedHashMap<String, Preset>();
    var presetSection = section.getConfigurationSection("presets");
    if (presetSection != null)
      for (String id : presetSection.getKeys(false)) {
        var p = presetSection.getConfigurationSection(id);
        if (p == null || !ID.matcher(id).matches()) continue;
        if (presets.size() >= PersonalLayout.MAX_PRESETS) break;
        presets.put(
            id,
            new Preset(
                id, p.getString("name", id), p.getStringList("lore"), parse(p.getStringList("placeholders"))));
      }
    return new PersonalCatalog(
        section.getString("title", ""),
        section.getString("top", ""),
        section.getString("bottom", ""),
        section.getString("separator", ""),
        PersonalLayout.cardCount(section.getInt("max-placeholders", PersonalLayout.MAX_LINES)),
        List.copyOf(section.getStringList("disabled-worlds")),
        Collections.unmodifiableMap(categories),
        Collections.unmodifiableMap(items),
        Collections.unmodifiableMap(presets),
        section.getString("default-preset", ""));
  }

  /** Old numeric {@code sort-group} to a category id. */
  public static String categoryForSortGroup(int sortGroup) {
    return switch (sortGroup) {
      case 5, 20 -> "reputation";
      case 10 -> "clans";
      case 30 -> "combat";
      default -> "general";
    };
  }

  /** PlaceholderAPI expansion ids used by a text: {@code %id_params%} gives {@code id}. */
  public static Set<String> expansions(String text) {
    var ids = new LinkedHashSet<String>();
    if (text == null) return ids;
    Matcher m = EXPANSION.matcher(text);
    while (m.find()) ids.add(m.group(1).toLowerCase(Locale.ROOT));
    return ids;
  }

  /** Parses {@code id} or {@code id:variant}; a malformed variant number is 0. */
  public static List<Entry> parse(List<String> raw) {
    var out = new ArrayList<Entry>();
    if (raw == null) return out;
    for (String s : raw) {
      if (s == null || s.isBlank()) continue;
      String text = s.trim();
      int colon = text.lastIndexOf(':');
      if (colon > 0) {
        int variant = 0;
        try {
          variant = Math.max(0, Integer.parseInt(text.substring(colon + 1).trim()));
        } catch (NumberFormatException ignored) {
          // stays 0
        }
        out.add(new Entry(text.substring(0, colon).trim(), variant));
      } else out.add(new Entry(text, 0));
    }
    return out;
  }

  /**
   * The entries a preset would enable: unusable rows (requirement unmet, plugin missing, unknown id)
   * are skipped, duplicates dropped, variants clamped, and the result is cut at {@code max}.
   */
  public static List<Entry> resolve(
      List<Entry> preset, Predicate<String> usable, ToIntFunction<String> variantCount, int max) {
    var out = new ArrayList<Entry>();
    var seen = new HashSet<String>();
    for (Entry entry : preset) {
      if (out.size() >= max) break;
      int count = variantCount.applyAsInt(entry.id());
      if (count <= 0 || !usable.test(entry.id()) || !seen.add(entry.id())) continue;
      out.add(new Entry(entry.id(), PersonalState.normalize(entry.variant(), count)));
    }
    return out;
  }

  /** True when both lists hold the same id and variant pairs; the order is the player's business. */
  public static boolean sameSet(List<Entry> a, List<Entry> b) {
    return a.size() == b.size() && new HashSet<>(a).equals(new HashSet<>(b));
  }

  /** One row of the flat view: enabled first (board order), then available A-Z, then locked A-Z. */
  public record Row(String id, String sortName, int activeIndex, boolean available) {}

  public static List<String> sortRows(List<Row> rows) {
    Collator collator = Collator.getInstance(Locale.forLanguageTag("ru"));
    Comparator<Row> byName = Comparator.comparing(Row::sortName, collator).thenComparing(Row::id);
    var active = new ArrayList<Row>();
    var available = new ArrayList<Row>();
    var locked = new ArrayList<Row>();
    for (Row row : rows) {
      if (row.activeIndex() >= 0) active.add(row);
      else if (row.available()) available.add(row);
      else locked.add(row);
    }
    active.sort(Comparator.comparingInt(Row::activeIndex));
    available.sort(byName);
    locked.sort(byName);
    var out = new ArrayList<String>(rows.size());
    for (var group : List.of(active, available, locked)) for (Row row : group) out.add(row.id());
    return out;
  }

  /** Rejects a catalog that cannot be read; presets naming unknown lines are skipped when applied. */
  public static void validate(ConfigurationSection config) {
    read(config);
  }
}
