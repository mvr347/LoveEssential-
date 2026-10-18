package dev.lovelace.loveessential.config;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** File layout is separate from the merged, read-only runtime configuration. */
public final class ModuleConfigs {
  private final JavaPlugin plugin;
  private final Path directory;

  public ModuleConfigs(JavaPlugin plugin) {
    this.plugin = plugin;
    this.directory = plugin.getDataFolder().toPath();
  }

  public static String prefix(String module) {
    return module.equals("nightskip") ? "night-skip" : module;
  }

  public static String runtimePath(String module, String path) {
    return module.equals("npc") && (path.equals("actions") || path.startsWith("actions."))
        ? path
        : prefix(module) + "." + path;
  }

  private YamlConfiguration resource(String name) throws Exception {
    try (var stream = plugin.getResource(name)) {
      if (stream == null) throw new IOException("Missing resource: " + name);
      var y = new YamlConfiguration();
      y.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
      return y;
    }
  }

  private static YamlConfiguration read(Path path) throws Exception {
    var y = new YamlConfiguration();
    if (Files.exists(path)) y.load(path.toFile()); // never silently replace malformed YAML
    return y;
  }

  public synchronized YamlConfiguration load() throws Exception {
    Files.createDirectories(directory.resolve("modules"));
    Path mainFile = directory.resolve("config.yml");
    var original = read(mainFile);
    var core = read(mainFile);
    fill(core, resource("config.yml"));
    var modules = new LinkedHashMap<String, YamlConfiguration>();
    boolean migrated = core.contains("modules") || core.contains("actions");
    for (String id : LoveEssentialPlugin.MODULES.stream().sorted().toList()) {
      var module = read(directory.resolve("modules/" + id + ".yml"));
      var legacy = new YamlConfiguration();
      var section = original.getConfigurationSection(prefix(id));
      if (section != null) {
        copy(section, legacy, "");
        migrated = true;
      }
      if (original.contains("modules." + id)) legacy.set("enabled", original.get("modules." + id));
      if (id.equals("npc") && original.isConfigurationSection("actions"))
        copy(original.getConfigurationSection("actions"), legacy, "actions.");
      fill(module, legacy);
      fill(module, resource("modules/" + id + ".yml"));
      if (!(module.get("enabled") instanceof Boolean))
        throw new IllegalArgumentException("errors.config");
      modules.put(id, module);
      core.set(prefix(id), null);
    }
    core.set("modules", null);
    core.set("actions", null);
    core.set("config-version", 3);
    var merged = merge(core, modules);
    LoveEssentialPlugin.validateConfig(merged);
    if (migrated && Files.exists(mainFile)) {
      Path backup = directory.resolve("config.yml.pre-modules.bak");
      if (!Files.exists(backup)) Files.copy(mainFile, backup);
    }
    // All files were parsed and validated before the first write. Existing module values win.
    for (var e : modules.entrySet())
      writeIfChanged(directory.resolve("modules/" + e.getKey() + ".yml"), e.getValue());
    writeIfChanged(mainFile, core);
    return merged;
  }

  public synchronized YamlConfiguration update(
      String module, String path, Object expected, Object value) throws Exception {
    if (!LoveEssentialPlugin.MODULES.contains(module) || path.isBlank() || path.equals("enabled"))
      throw new IllegalArgumentException("errors.config");
    var merged = load();
    Path file = directory.resolve("modules/" + module + ".yml");
    var y = read(file);
    if (!Objects.equals(y.get(path), expected))
      throw new IllegalArgumentException("errors.conflict");
    if (y.isConfigurationSection(path)) throw new IllegalArgumentException("errors.config");
    y.set(path, value);
    merged.set(runtimePath(module, path), value);
    LoveEssentialPlugin.validateConfig(merged);
    Files.copy(
        file,
        file.resolveSibling(file.getFileName() + ".bak"),
        StandardCopyOption.REPLACE_EXISTING);
    writeIfChanged(file, y);
    return load();
  }

  /** Legacy saveConfig callers can persist runtime changes without rebuilding a monolithic file. */
  public synchronized void save(ConfigurationSection runtime) throws Exception {
    var core = new YamlConfiguration();
    copy(runtime, core, "");
    var modules = new LinkedHashMap<String, YamlConfiguration>();
    for (String id : LoveEssentialPlugin.MODULES) {
      var y = view(runtime, id);
      y.set("enabled", runtime.getBoolean("modules." + id, true));
      modules.put(id, y);
      core.set(prefix(id), null);
    }
    core.set("modules", null);
    core.set("actions", null);
    LoveEssentialPlugin.validateConfig(runtime);
    for (var e : modules.entrySet())
      writeIfChanged(directory.resolve("modules/" + e.getKey() + ".yml"), e.getValue());
    writeIfChanged(directory.resolve("config.yml"), core);
  }

  public static YamlConfiguration view(ConfigurationSection runtime, String id) {
    var y = new YamlConfiguration();
    var section = runtime.getConfigurationSection(prefix(id));
    if (section != null) copy(section, y, "");
    if (id.equals("npc") && runtime.isConfigurationSection("actions"))
      copy(runtime.getConfigurationSection("actions"), y, "actions.");
    return y;
  }

  private static YamlConfiguration merge(
      YamlConfiguration core, Map<String, YamlConfiguration> modules) {
    var merged = new YamlConfiguration();
    copy(core, merged, "");
    modules.forEach(
        (id, y) -> {
          merged.set("modules." + id, y.getBoolean("enabled"));
          for (String key : y.getKeys(true)) {
            if (key.equals("enabled")) continue;
            if (y.isConfigurationSection(key)) {
              merged.createSection(runtimePath(id, key));
              continue;
            }
            merged.set(runtimePath(id, key), y.get(key));
          }
        });
    return merged;
  }

  private static void fill(YamlConfiguration target, ConfigurationSection defaults) {
    for (String key : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(key)
          && target.contains(key)
          && !target.isConfigurationSection(key))
        throw new IllegalArgumentException("errors.config");
      if (!target.contains(key)) {
        if (defaults.isConfigurationSection(key)) target.createSection(key);
        else target.set(key, defaults.get(key));
      }
    }
  }

  private static void copy(ConfigurationSection source, YamlConfiguration target, String prefix) {
    for (String key : source.getKeys(true))
      if (source.isConfigurationSection(key)) target.createSection(prefix + key);
      else target.set(prefix + key, source.get(key));
  }

  private static void writeIfChanged(Path path, YamlConfiguration y) throws IOException {
    String content = y.saveToString();
    if (Files.exists(path) && readText(path).equals(content)) return;
    Files.createDirectories(path.getParent());
    Path temp = Files.createTempFile(path.getParent(), ".config-", ".tmp");
    try {
      Files.writeString(temp, content, StandardCharsets.UTF_8);
      try {
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static String readText(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
