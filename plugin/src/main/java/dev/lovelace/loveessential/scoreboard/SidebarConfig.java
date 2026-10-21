package dev.lovelace.loveessential.scoreboard;

import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.scoreboard.Sidebar.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** Human-readable module configuration; personal selections remain exclusively in SQLite. */
public final class SidebarConfig {
  private SidebarConfig() {}

  private static String inline(String value) {
    return "@inline:" + value;
  }

  public static Catalog read(ConfigurationSection c) {
    var categories = new LinkedHashMap<String, String>();
    var cs = c.getConfigurationSection("categories");
    if (cs != null)
      for (String id : cs.getKeys(false)) categories.put(id, inline(cs.getString(id, id)));
    var lines = new ArrayList<Line>();
    var section = c.getConfigurationSection("placeholders");
    if (section != null)
      for (String id : section.getKeys(false)) {
        var line = section.getConfigurationSection(id);
        if (line == null) throw new IllegalArgumentException("errors.config");
        var variants = new ArrayList<String>();
        var names = new ArrayList<String>();
        for (Object raw : Objects.requireNonNullElse(line.getList("variants"), List.of())) {
          if (raw instanceof Map<?, ?> map) {
            variants.add(inline(Objects.toString(map.get("template"), "")));
            names.add(inline(Objects.toString(map.get("name"), "")));
          } else if (raw instanceof String text) {
            variants.add(inline(text));
            names.add(inline(""));
          } else throw new IllegalArgumentException("errors.config");
        }
        if (variants.isEmpty()) {
          variants.add(inline(line.getString("template", "")));
          names.add(inline(""));
        }
        String category = line.getString("category", "general");
        categories.putIfAbsent(category, inline(category));
        lines.add(
            new Line(
                id,
                inline(line.getString("name", id)),
                category,
                variants,
                line.getString("requirement.placeholder", ""),
                line.getString("requirement.equals", ""),
                inline(line.getString("requirement.reason", "")),
                names,
                line.getStringList("lore").stream().map(SidebarConfig::inline).toList()));
      }
    var presets = new ArrayList<Preset>();
    var ps = c.getConfigurationSection("presets");
    if (ps != null)
      for (String id : ps.getKeys(false)) {
        var selections = new ArrayList<Selection>();
        for (Map<?, ?> entry : ps.getMapList(id + ".lines"))
          selections.add(
              new Selection(
                  Objects.toString(entry.get("id"), ""),
                  Integer.parseInt(Objects.toString(entry.get("variant"), "0"))));
        presets.add(new Preset(id, inline(ps.getString(id + ".name", id)), selections));
      }
    var result =
        new Catalog(
            inline(c.getString("title", "")),
            inline(c.getString("separator", "")),
            inline(c.getString("bottom", "")),
            c.getInt("max", 8),
            c.getInt("interval", 20),
            c.getStringList("disabled-worlds"),
            categories,
            lines,
            presets,
            c.getString("default-preset", ""));
    validate(result);
    return result;
  }

  public static void validate(Catalog value) {
    if (value.max() < 1
        || value.max() > 8
        || value.interval() < 2
        || value.interval() > 1200
        || value.lines().size() > 256
        || value.presets().size() > 64) throw new IllegalArgumentException("errors.config");
    var ids = new HashSet<String>();
    for (var line : value.lines())
      if (!line.id().matches("[A-Za-z0-9_-]{1,48}")
          || !ids.add(line.id())
          || line.variants().isEmpty()
          || line.variants().size() > 8
          || line.variantNames().size() != line.variants().size())
        throw new IllegalArgumentException("errors.config");
    var presets = new HashSet<String>();
    for (var preset : value.presets())
      if (!preset.id().matches("[A-Za-z0-9_-]{1,48}")
          || !presets.add(preset.id())
          || preset.entries().size() > 8
          || preset.entries().stream()
              .anyMatch(entry -> !ids.contains(entry.id()) || entry.variant() < 0))
        throw new IllegalArgumentException("errors.config");
  }

  public static Map<String, Object> write(Catalog c, Lang lang, Map<String, String> pending) {
    java.util.function.Function<String, String> text =
        key -> pending.getOrDefault(key, lang.text(key));
    var result = new LinkedHashMap<String, Object>();
    result.put("presets-version", 1);
    result.put("title", text.apply(c.titleKey()));
    result.put("separator", text.apply(c.separatorKey()));
    result.put("bottom", text.apply(c.bottomKey()));
    result.put("max", c.max());
    result.put("interval", c.interval());
    result.put("disabled-worlds", c.disabledWorlds());
    var categories = new LinkedHashMap<String, Object>();
    c.categories().forEach((id, key) -> categories.put(id, text.apply(key)));
    result.put("categories", categories);
    var lines = new LinkedHashMap<String, Object>();
    for (var line : c.lines()) {
      var entry = new LinkedHashMap<String, Object>();
      entry.put("name", text.apply(line.nameKey()));
      entry.put("category", line.category());
      var variants = new ArrayList<Map<String, String>>();
      for (int i = 0; i < line.variants().size(); i++)
        variants.add(
            Map.of(
                "name",
                text.apply(line.variantNames().get(i)),
                "template",
                text.apply(line.variants().get(i))));
      entry.put("variants", variants);
      entry.put("lore", line.loreKeys().stream().map(text).toList());
      entry.put(
          "requirement",
          Map.of(
              "placeholder",
              line.requirement(),
              "equals",
              line.expected(),
              "reason",
              text.apply(line.reasonKey())));
      lines.put(line.id(), entry);
    }
    result.put("placeholders", lines);
    var presets = new LinkedHashMap<String, Object>();
    for (var preset : c.presets())
      presets.put(
          preset.id(),
          Map.of(
              "name",
              text.apply(preset.nameKey()),
              "lines",
              preset.entries().stream()
                  .filter(
                      entry -> c.lines().stream().anyMatch(line -> line.id().equals(entry.id())))
                  .map(
                      entry ->
                          Map.of(
                              "id",
                              entry.id(),
                              "variant",
                              Math.clamp(
                                  entry.variant(),
                                  0,
                                  c.lines().stream()
                                          .filter(line -> line.id().equals(entry.id()))
                                          .findFirst()
                                          .orElseThrow()
                                          .variants()
                                          .size()
                                      - 1)))
                  .toList()));
    result.put("presets", presets);
    result.put("default-preset", c.defaultPreset());
    return result;
  }

  public static Catalog materialize(Map<String, Object> map) {
    var y = new org.bukkit.configuration.file.YamlConfiguration();
    y.createSection("catalog", map);
    return read(y.getConfigurationSection("catalog"));
  }
}
