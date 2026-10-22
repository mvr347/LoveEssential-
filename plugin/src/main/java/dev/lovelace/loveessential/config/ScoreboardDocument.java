package dev.lovelace.loveessential.config;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** Readable file order; the existing catalog API remains an internal representation. */
public final class ScoreboardDocument {
  private static final List<String> FIELDS =
      List.of(
          "max",
          "interval",
          "disabled-worlds",
          "title",
          "separator",
          "categories",
          "placeholders",
          "bottom",
          "presets-version",
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
      if (!key.equals("disabled-worlds")) {
        Object value = catalog.get(key);
        // The typed public API intentionally knows only its own fields. Keep custom
        // icons/textures and other extension data on surviving entries, not deleted IDs.
        if ((key.equals("placeholders") || key.equals("presets"))
            && value instanceof Map<?, ?> entries) {
          var preserved = new LinkedHashMap<String, Object>();
          for (var entry : entries.entrySet()) {
            String id = entry.getKey().toString();
            preserved.put(
                id,
                extras(
                    ModuleConfigs.snapshot(file.get(fileKey(key) + "." + id)), entry.getValue()));
          }
          value = preserved;
        }
        ModuleConfigs.setValue(file, fileKey(key), value);
      }
    if (catalog.containsKey("disabled-worlds"))
      file.set("disabled-worlds", catalog.get("disabled-worlds"));
    file.set("catalog", null);
    order(file);
  }

  private static Object extras(Object previous, Object current) {
    if (!(previous instanceof Map<?, ?> old) || !(current instanceof Map<?, ?> next))
      return current;
    var result = new LinkedHashMap<String, Object>();
    old.forEach((key, value) -> result.put(key.toString(), value));
    next.forEach((key, value) -> result.put(key.toString(), extras(old.get(key), value)));
    return result;
  }

  /** Rules first; preserve unknown settings, comments and every literal catalog value. */
  public static void order(ConfigurationSection file) {
    var values = new LinkedHashMap<String, Object>();
    var comments = new HashMap<String, List<String>>();
    var inline = new HashMap<String, List<String>>();
    for (String key : file.getKeys(true)) {
      comments.put(key, file.getComments(key));
      inline.put(key, file.getInlineComments(key));
    }
    var content =
        Set.of(
            "header",
            "separator",
            "categories",
            "placeholders",
            "footer",
            "presets-version",
            "presets",
            "default-preset");
    for (String key : file.getKeys(false))
      if (!content.contains(key)) values.put(key, ModuleConfigs.snapshot(file.get(key)));
    for (String key :
        List.of(
            "header",
            "separator",
            "categories",
            "placeholders",
            "footer",
            "presets-version",
            "presets",
            "default-preset"))
      if (file.contains(key)) values.put(key, ModuleConfigs.snapshot(file.get(key)));
    for (String key : List.copyOf(file.getKeys(false))) file.set(key, null);
    values.forEach((key, value) -> ModuleConfigs.setValue(file, key, value));
    comments.forEach((key, value) -> file.setComments(key, value));
    inline.forEach((key, value) -> file.setInlineComments(key, value));
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
