package dev.lovelace.loveessential.tab;

import dev.lovelace.loveessential.lang.Lang;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicit, backed-up export to the real TAB engine. All engine fields are passed through. */
public final class TabExport {
  private static final Pattern TEXT = Pattern.compile("@lang\\(([a-zA-Z0-9_.-]+)\\)");

  public static Object transform(Object value, java.util.function.Predicate<String> exists) {
    if (value instanceof ConfigurationSection s) {
      var map = new LinkedHashMap<String, Object>();
      for (String k : s.getKeys(false)) map.put(k, transform(s.get(k), exists));
      return map;
    }
    if (value instanceof Map<?, ?> m) {
      var out = new LinkedHashMap<String, Object>();
      m.forEach((k, v) -> out.put(k.toString(), transform(v, exists)));
      return out;
    }
    if (value instanceof List<?> l) return l.stream().map(v -> transform(v, exists)).toList();
    if (value instanceof String s) {
      var matcher = TEXT.matcher(s);
      var result = new StringBuilder();
      while (matcher.find()) {
        String key = matcher.group(1);
        if (!exists.test(key)) throw new IllegalArgumentException("validation.lang");
        matcher.appendReplacement(
            result, Matcher.quoteReplacement("%loveessential_text_" + key + "%"));
      }
      matcher.appendTail(result);
      return result.toString();
    }
    return value;
  }

  public static Map<String, Object> snapshot(JavaPlugin plugin, Lang lang) {
    var out = new LinkedHashMap<String, Object>();
    for (String file : List.of("config", "groups", "animations")) {
      var s = plugin.getConfig().getConfigurationSection("tab.external." + file);
      if (s != null) out.put(file, transform(s, lang::contains));
    }
    return Map.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  public static void write(Path directory, Map<String, Object> files) throws Exception {
    Files.createDirectories(directory);
    for (var file : files.entrySet()) {
      if (!Set.of("config", "groups", "animations").contains(file.getKey()))
        throw new IllegalArgumentException("invalid-tab-file");
      Path target = directory.resolve(file.getKey() + ".yml");
      var yaml = new YamlConfiguration();
      if (Files.exists(target)) yaml.load(target.toFile());
      for (var e : ((Map<String, Object>) file.getValue()).entrySet())
        yaml.set(e.getKey(), e.getValue());
      if (Files.exists(target))
        Files.copy(
            target,
            target.resolveSibling(target.getFileName() + ".loveessential.bak"),
            StandardCopyOption.REPLACE_EXISTING);
      var temp = Files.createTempFile(directory, "love-tab-", ".tmp");
      try {
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
          Files.move(
              temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temp);
      }
    }
  }
}
