package dev.lovelace.loveessential.config;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** Readable file order; the existing catalog API remains an internal representation. */
public final class ScoreboardDocument {
  private static final List<String> FIELDS =
      List.of(
          "presets-version",
          "title",
          "separator",
          "bottom",
          "max",
          "interval",
          "disabled-worlds",
          "categories",
          "placeholders",
          "presets",
          "default-preset");

  private static String fileKey(String key) {
    return key.equals("title")
        ? "header"
        : key.equals("bottom") ? "footer" : key.equals("interval") ? "refresh-ticks" : key;
  }

  public static Map<String, Object> read(ConfigurationSection file) {
    if (!file.contains("header") && !file.contains("placeholders")) return null;
    var result = new LinkedHashMap<String, Object>();
    for (String key : FIELDS)
      if (file.contains(fileKey(key)))
        result.put(key, ModuleConfigs.snapshot(file.get(fileKey(key))));
    return result;
  }

  public static void write(ConfigurationSection file, Map<String, ?> catalog) {
    for (String key : FIELDS)
      if (!key.equals("disabled-worlds")) file.set(fileKey(key), catalog.get(key));
    if (catalog.containsKey("disabled-worlds"))
      file.set("disabled-worlds", catalog.get("disabled-worlds"));
    file.set("catalog", null);
  }

  public static void migrate(ConfigurationSection file) {
    var old = file.getConfigurationSection("catalog");
    if (old == null) return;
    @SuppressWarnings("unchecked")
    var values = (Map<String, Object>) ModuleConfigs.snapshot(old);
    if (file.contains("refresh-ticks")) values.put("interval", file.get("refresh-ticks"));
    if (file.contains("max-selected-lines"))
      values.put(
          "max",
          Math.min(
              ((Number) values.getOrDefault("max", 8)).intValue(),
              file.getInt("max-selected-lines", 8)));
    var existing = read(file);
    var worlds = new LinkedHashSet<String>(file.getStringList("disabled-worlds"));
    worlds.addAll(old.getStringList("disabled-worlds"));
    if (existing != null) values.putAll(existing);
    values.put("disabled-worlds", List.copyOf(worlds));
    write(file, values);
  }
}
