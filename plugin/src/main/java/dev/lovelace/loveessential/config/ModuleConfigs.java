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
  private final Map<String, YamlConfiguration> resourceDefaults = new HashMap<>();

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
    var cached = resourceDefaults.get(name);
    if (cached != null) return cached;
    try (var stream = plugin.getResource(name)) {
      if (stream == null) throw new IOException("Missing resource: " + name);
      var y = new YamlConfiguration();
      y.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
      resourceDefaults.put(name, y);
      return y;
    }
  }

  private static YamlConfiguration read(Path path) throws Exception {
    var y = new YamlConfiguration();
    if (Files.exists(path)) y.load(path.toFile()); // never silently replace malformed YAML
    return y;
  }

  public Path configFile(String module) {
    return directory.resolve("modules/" + module + "/" + module + ".yml");
  }

  public Path guiFile(String module) {
    return directory.resolve("modules/" + module + "/gui.yml");
  }

  public synchronized YamlConfiguration load() throws Exception {
    synchronized (ModuleConfigs.class) {
      Path mainFile = directory.resolve("config.yml");
      var original = read(mainFile);
      var core = read(mainFile);
      fill(core, resource("config.yml"));
      var modules = new LinkedHashMap<String, YamlConfiguration>();
      var guis = new LinkedHashMap<String, YamlConfiguration>();
      var oldFiles = new ArrayList<Path>();
      var oldLang = read(directory.resolve("lang.yml"));
      for (String owner : LoveEssentialPlugin.MODULES) {
        for (String filename : List.of(owner + ".yml", "gui.yml")) {
          var defaults = resource("modules/" + owner + "/" + filename);
          var messages = defaults.getConfigurationSection("messages");
          if (messages != null)
            for (String key : messages.getKeys(true))
              if (!messages.isConfigurationSection(key) && !oldLang.contains(key))
                oldLang.set(key, messages.get(key));
          for (var binding : Presentation.bindings())
            if (binding.module().equals(owner)
                && binding.file().equals(filename)
                && !oldLang.contains(binding.key()))
              oldLang.set(binding.key(), defaults.get(binding.path()));
        }
      }
      boolean migrated = core.contains("modules") || core.contains("actions");
      for (String id : LoveEssentialPlugin.MODULES.stream().sorted().toList()) {
        var module = read(configFile(id));
        Path oldFile = directory.resolve("modules/" + id + ".yml");
        var legacy = read(oldFile);
        if (Files.exists(oldFile)) oldFiles.add(oldFile);
        var section = original.getConfigurationSection(prefix(id));
        if (section != null) {
          fill(legacy, section);
          migrated = true;
        }
        if (original.contains("modules." + id) && !legacy.contains("enabled"))
          legacy.set("enabled", original.get("modules." + id));
        if (id.equals("npc") && original.isConfigurationSection("actions")) {
          var actions = new YamlConfiguration();
          copy(original.getConfigurationSection("actions"), actions, "actions.");
          fill(legacy, actions);
        }
        fill(module, legacy);
        if (id.equals("scoreboard")) {
          ScoreboardDocument.migrate(module);
          if (module.contains("max-selected-lines"))
            module.set(
                "max", Math.min(module.getInt("max", 8), module.getInt("max-selected-lines", 8)));
          module.set("max-selected-lines", null);
          if (module.contains("interval") && !module.contains("refresh-ticks"))
            module.set("refresh-ticks", module.get("interval"));
          module.set("interval", null);
        }
        var gui = read(guiFile(id));
        if (module.isConfigurationSection("gui")) fill(gui, module.getConfigurationSection("gui"));
        module.set("gui", null);
        if (id.equals("profile"))
          for (String key : List.of("textures", "cards", "adaptation-cards")) {
            if (module.contains(key) && !gui.contains(key)) gui.set(key, module.get(key));
            module.set(key, null);
          }
        var guiDefaults = resource("modules/" + id + "/gui.yml");
        var earlyDefaults = resource("modules/" + id + "/" + id + ".yml");
        Presentation.migrate(id, module, gui, oldLang, earlyDefaults, guiDefaults);
        // The first migration retains customized legacy language entries.
        if (!Files.exists(guiFile(id))) {
          var textDefaults = guiDefaults.getConfigurationSection("texts");
          if (textDefaults != null)
            for (String key : textDefaults.getKeys(true))
              if (!textDefaults.isConfigurationSection(key)
                  && oldLang.contains(key)
                  && !gui.contains("texts." + key)) gui.set("texts." + key, oldLang.get(key));
        }
        fill(gui, guiDefaults);
        if (id.equals("tab")) {
          module.set("engine", null);
          module.set("external", null);
          inlineTab(module, oldLang);
        }
        var moduleDefaults = resource("modules/" + id + "/" + id + ".yml");
        var defaultsTexts = moduleDefaults.getConfigurationSection("texts");
        if (!Files.exists(configFile(id)) && defaultsTexts != null)
          for (String key : defaultsTexts.getKeys(true))
            if (!defaultsTexts.isConfigurationSection(key)
                && oldLang.contains(key)
                && !module.contains("texts." + key)) module.set("texts." + key, oldLang.get(key));
        fill(module, moduleDefaults);
        if (!(module.get("enabled") instanceof Boolean))
          throw new IllegalArgumentException("errors.config");
        guis.put(id, gui);
        module.createSection("gui", (Map<String, Object>) snapshot(gui));
        modules.put(id, module);
        core.set(prefix(id), null);
      }
      core.set("modules", null);
      core.set("actions", null);
      core.set("config-version", 5);
      var merged = merge(core, modules);
      var catalog = ScoreboardDocument.read(modules.get("scoreboard"));
      if (catalog != null) merged.createSection("scoreboard.catalog", catalog);
      Presentation.runtime(merged);
      LoveEssentialPlugin.validateConfig(merged);
      if (migrated && Files.exists(mainFile)) backup(mainFile, ".pre-modules.bak");
      for (var entry : modules.entrySet()) {
        entry.getValue().set("gui", null);
        if (original.getInt("config-version", 0) < 5) {
          if (Files.exists(configFile(entry.getKey())))
            backup(configFile(entry.getKey()), ".pre-inline.bak");
          if (Files.exists(guiFile(entry.getKey())))
            backup(guiFile(entry.getKey()), ".pre-inline.bak");
        }
        writeIfChanged(configFile(entry.getKey()), entry.getValue());
        writeIfChanged(guiFile(entry.getKey()), guis.get(entry.getKey()));
      }
      writeIfChanged(mainFile, core);
      for (Path old : oldFiles) {
        backup(old, ".pre-folders.bak");
        Files.delete(old);
      }
      return merged;
    }
  }

  private void inlineTab(YamlConfiguration module, YamlConfiguration legacyLanguage)
      throws Exception {
    var defaults = resource("lang.yml");
    for (String path : List.copyOf(module.getKeys(true)))
      if (path.endsWith("-key") && module.isString(path)) {
        String local = path.substring(0, path.length() - 4);
        if (module.contains(local)) continue;
        String key = module.getString(path, "");
        Object value = legacyLanguage.contains(key) ? legacyLanguage.get(key) : defaults.get(key);
        if (value != null)
          module.set(
              local,
              (local.equals("header-footer.header") || local.equals("header-footer.footer"))
                      && value instanceof String text
                  ? Arrays.asList(text.split("\n", -1))
                  : value);
      }
    if (!module.contains("sidebar.lines") && module.contains("sidebar.line-keys")) {
      var lines = new ArrayList<String>();
      for (String key : module.getStringList("sidebar.line-keys"))
        lines.add(legacyLanguage.getString(key, defaults.getString(key, "")));
      module.set("sidebar.lines", lines);
    }
  }

  private static void backup(Path file, String suffix) throws IOException {
    Path target = file.resolveSibling(file.getFileName() + suffix);
    if (!Files.exists(target)) Files.copy(file, target);
  }

  public static Object snapshot(Object value) {
    if (value instanceof ConfigurationSection section) {
      var result = new LinkedHashMap<String, Object>();
      for (String key : section.getKeys(false)) result.put(key, snapshot(section.get(key)));
      return result;
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, v) -> result.put(key.toString(), snapshot(v)));
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(ModuleConfigs::snapshot).toList();
    return value;
  }

  public synchronized YamlConfiguration update(
      String module, String path, Object expected, Object value) throws Exception {
    synchronized (ModuleConfigs.class) {
      if (!LoveEssentialPlugin.MODULES.contains(module) || path.isBlank() || path.equals("enabled"))
        throw new IllegalArgumentException("errors.config");
      var merged = load();
      boolean gui = path.startsWith("gui.");
      Path file = gui ? guiFile(module) : configFile(module);
      String local = gui ? path.substring(4) : path;
      if (local.isBlank()) throw new IllegalArgumentException("errors.config");
      var y = read(file);
      Object actual =
          module.equals("scoreboard") && local.equals("catalog")
              ? ScoreboardDocument.read(y)
              : y.get(local);
      if (!Objects.equals(snapshot(actual), snapshot(expected)))
        throw new IllegalArgumentException("errors.conflict");
      if (y.isConfigurationSection(local) && !(value instanceof Map<?, ?>))
        throw new IllegalArgumentException("errors.config");
      if (module.equals("scoreboard")
          && local.equals("catalog")
          && value instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        var catalog = (Map<String, Object>) map;
        ScoreboardDocument.write(y, catalog);
      } else y.set(local, value);
      merged.set(runtimePath(module, path), value);
      var candidate = new YamlConfiguration();
      candidate.loadFromString(merged.saveToString());
      LoveEssentialPlugin.validateConfig(candidate);
      Files.copy(
          file,
          file.resolveSibling(file.getFileName() + ".bak"),
          StandardCopyOption.REPLACE_EXISTING);
      writeIfChanged(file, y);
      return load();
    }
  }

  public synchronized void save(ConfigurationSection runtime) throws Exception {
    synchronized (ModuleConfigs.class) {
      LoveEssentialPlugin.validateConfig(runtime);
      var core = new YamlConfiguration();
      copy(runtime, core, "");
      for (String id : LoveEssentialPlugin.MODULES) {
        var module = view(runtime, id);
        var gui = new YamlConfiguration();
        if (module.isConfigurationSection("gui"))
          copy(module.getConfigurationSection("gui"), gui, "");
        module.set("gui", null);
        module.set("enabled", runtime.getBoolean("modules." + id, true));
        writeIfChanged(configFile(id), module);
        stripAliases(gui);
        writeIfChanged(guiFile(id), gui);
        core.set(prefix(id), null);
      }
      core.set("modules", null);
      core.set("actions", null);
      writeIfChanged(directory.resolve("config.yml"), core);
    }
  }

  public static YamlConfiguration view(ConfigurationSection runtime, String id) {
    var y = new YamlConfiguration();
    var section = runtime.getConfigurationSection(prefix(id));
    if (section != null) copy(section, y, "");
    if (id.equals("npc") && runtime.isConfigurationSection("actions"))
      copy(runtime.getConfigurationSection("actions"), y, "actions.");
    stripAliases(y);
    if (id.equals("scoreboard")) y.set("catalog", null);
    return y;
  }

  private static void stripAliases(ConfigurationSection section) {
    for (String key : List.copyOf(section.getKeys(true)))
      if (key.endsWith("-key") || key.endsWith("-keys")) section.set(key, null);
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
    var preserve =
        List.of(
                "groups",
                "placeholders",
                "catalog",
                "presets",
                "users",
                "worlds",
                "header-footer.profiles")
            .stream()
            .filter(target::contains)
            .toList();
    for (String key : defaults.getKeys(true)) {
      if (preserve.stream().anyMatch(root -> key.startsWith(root + "."))) continue;
      if (defaults.isConfigurationSection(key)
          && target.contains(key)
          && !target.isConfigurationSection(key))
        throw new IllegalArgumentException("errors.config");
      if (!target.contains(key)) {
        if (defaults.isConfigurationSection(key)) target.createSection(key);
        else target.set(key, snapshot(defaults.get(key)));
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
