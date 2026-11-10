package dev.lovelace.loveessential.module;

import dev.lovelace.lovecore.api.achievements.AchievementRarity;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The achievement catalogue read from {@code achievements.catalog} of {@code modules/achievements/achievements.yml}.
 * Parsing is free of the server, so it is tested without one. A broken entry is skipped with a message to
 * {@code problems}: one typo in a file edited by hand must not switch every achievement off.
 */
public final class AchievementCatalog {

  /** What makes an achievement come true. */
  public sealed interface Trigger {
    /** A numeric value (a metric of the stat bus or an own counter) reaches {@code atLeast}. */
    record Counter(String key, double atLeast) implements Trigger {}

    /** Every value of {@code values} has been collected into the set {@code key}. */
    record Collected(String key, List<String> values) implements Trigger {}

    /** Every achievement of {@code ids} is unlocked (a meta achievement). */
    record AllOf(List<String> ids) implements Trigger {}

    /** At least {@code atLeast} achievements are unlocked, not counting meta ones. */
    record UnlockedCount(int atLeast) implements Trigger {}

    /** Only a plugin that knows the condition unlocks it, through {@code Achievements#unlock}. */
    record Manual() implements Trigger {}
  }

  /** One catalogue entry. {@code icon} is a head texture or blank for the head of the rarity. */
  public record Def(
      String id,
      String category,
      AchievementRarity rarity,
      String name,
      String description,
      String hint,
      boolean hidden,
      String icon,
      String rewardText,
      Trigger trigger) {

    /** Counters this entry watches (empty for non-numeric triggers). */
    public boolean watches(String key) {
      return trigger instanceof Trigger.Counter c && c.key().equals(key);
    }
  }

  private final Map<String, Def> byId;
  private final List<Def> ordered;

  private AchievementCatalog(List<Def> defs) {
    this.ordered = List.copyOf(defs);
    var map = new LinkedHashMap<String, Def>();
    for (Def def : defs) map.put(def.id(), def);
    this.byId = Collections.unmodifiableMap(map);
  }

  public static AchievementCatalog empty() {
    return new AchievementCatalog(List.of());
  }

  public static AchievementCatalog parse(ConfigurationSection catalog, Consumer<String> problems) {
    var defs = new ArrayList<Def>();
    if (catalog == null) return new AchievementCatalog(defs);
    for (String id : catalog.getKeys(false)) {
      var entry = catalog.getConfigurationSection(id);
      if (entry == null) continue;
      try {
        defs.add(entry(id, entry));
      } catch (IllegalArgumentException invalid) {
        problems.accept("achievements.catalog." + id + ": " + invalid.getMessage() + " - entry skipped");
      }
    }
    return new AchievementCatalog(defs);
  }

  private static Def entry(String id, ConfigurationSection entry) {
    // A dot would make the id a nested path in the YAML file, and the entry would never be found.
    if (id.contains(" ") || id.contains(".")) throw new IllegalArgumentException("id must not contain spaces or dots");
    AchievementRarity rarity;
    try {
      rarity = AchievementRarity.valueOf(entry.getString("rarity", "COMMON").toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("unknown rarity " + entry.getString("rarity"));
    }
    String name = entry.getString("name", "");
    if (name.isBlank()) throw new IllegalArgumentException("name is required");
    return new Def(
        id,
        entry.getString("category", "other"),
        rarity,
        name,
        entry.getString("description", ""),
        entry.getString("hint", ""),
        entry.getBoolean("hidden", false),
        entry.getString("icon", ""),
        entry.getString("reward-text", ""),
        trigger(entry.getConfigurationSection("trigger")));
  }

  private static Trigger trigger(ConfigurationSection section) {
    if (section == null) return new Trigger.Manual();
    String type = section.getString("type", "manual").toLowerCase(Locale.ROOT);
    return switch (type) {
      case "manual" -> new Trigger.Manual();
      case "counter" -> {
        String key = section.getString("key", "");
        if (key.isBlank()) throw new IllegalArgumentException("counter needs a key");
        double atLeast = section.getDouble("at-least", 1);
        if (!(atLeast > 0)) throw new IllegalArgumentException("at-least must be above zero");
        yield new Trigger.Counter(key, atLeast);
      }
      case "set" -> {
        String key = section.getString("key", "");
        var values = section.getStringList("values");
        if (key.isBlank() || values.isEmpty())
          throw new IllegalArgumentException("set needs a key and values");
        yield new Trigger.Collected(key, List.copyOf(values));
      }
      case "all-of" -> {
        var ids = section.getStringList("ids");
        if (ids.isEmpty()) throw new IllegalArgumentException("all-of needs ids");
        yield new Trigger.AllOf(List.copyOf(ids));
      }
      case "unlocked-count" -> {
        int atLeast = section.getInt("at-least", 0);
        if (atLeast <= 0) throw new IllegalArgumentException("at-least must be above zero");
        yield new Trigger.UnlockedCount(atLeast);
      }
      default -> throw new IllegalArgumentException("unknown trigger type " + type);
    };
  }

  public List<Def> all() {
    return ordered;
  }

  public Optional<Def> find(String id) {
    return Optional.ofNullable(byId.get(id));
  }

  public boolean contains(String id) {
    return byId.containsKey(id);
  }

  public Set<String> ids() {
    return byId.keySet();
  }

  /** The categories in order of first use. */
  public List<String> categories() {
    var seen = new LinkedHashSet<String>();
    for (Def def : ordered) seen.add(def.category());
    return List.copyOf(seen);
  }
}
