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
      fillIn("config.yml", core, resource("config.yml"));
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
        fillIn("modules/" + id + "/" + id + ".yml", module, legacy);
        if (id.equals("scoreboard")) migrateScoreboard(module);
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
        // 2026-10-31: the statistics menu has five category buttons. Categories nobody edited are replaced by
        // the new ones (the old file is kept); edited categories stay and keep working in the new layout.
        if (id.equals("profile")
            && Files.exists(guiFile(id))
            && unchangedLegacyStatistics(gui, resource("legacy/profile-statistics-2026-10-30.yml"))) {
          backup(guiFile(id), ".pre-stat-sections.bak");
          gui.set(STATISTICS + ".categories", null);
          gui.set(STATISTICS + ".size", null);
        }
        fillIn("modules/" + id + "/gui.yml", gui, guiDefaults);
        repairMappedLines(gui);
        String beforeDisplay = id.equals("display") ? gui.saveToString() : null;
        String beforeNpcCommands = id.equals("npc") ? gui.saveToString() : null;
        refreshRetiredTexts(id, gui, guiDefaults);
        if (beforeDisplay != null && !beforeDisplay.equals(gui.saveToString())
            && Files.exists(guiFile(id))) backup(guiFile(id), ".pre-own-display.bak");
        if (beforeNpcCommands != null && !beforeNpcCommands.equals(gui.saveToString())
            && Files.exists(guiFile(id))) backup(guiFile(id), ".pre-npc-commands.bak");
        if (id.equals("tab")) {
          module.set("engine", null);
          module.set("external", null);
          inlineTab(module, oldLang);
          migrateTab(module);
        }
        // The multi-home limit was never used: a home is a single point.
        if (id.equals("home")) module.set("default-limit", null);
        var moduleDefaults = resource("modules/" + id + "/" + id + ".yml");
        var defaultsTexts = moduleDefaults.getConfigurationSection("texts");
        if (!Files.exists(configFile(id)) && defaultsTexts != null)
          for (String key : defaultsTexts.getKeys(true))
            if (!defaultsTexts.isConfigurationSection(key)
                && oldLang.contains(key)
                && !module.contains("texts." + key)) module.set("texts." + key, oldLang.get(key));
        fillIn("modules/" + id + "/" + id + ".yml", module, moduleDefaults);
        if (id.equals("scoreboard")) {
          restyleScoreboard(module, moduleDefaults, resource("legacy/scoreboard-2026-10-23.yml"));
          if (removeWorldLine(module) && Files.exists(configFile(id)))
            backup(configFile(id), ".pre-world-line.bak");
        }
        repairMappedLines(module);
        if (!(module.get("enabled") instanceof Boolean))
          throw new IllegalArgumentException("errors.config");
        guis.put(id, gui);
        module.createSection("gui", (Map<String, Object>) snapshot(gui));
        modules.put(id, module);
        core.set(prefix(id), null);
      }
      core.set("modules", null);
      core.set("actions", null);
      core.set("config-version", 6);
      var merged = merge(core, modules);
      Presentation.runtime(merged);
      LoveEssentialPlugin.validateConfig(merged);
      if (original.getInt("config-version", 0) < 6 && Files.exists(mainFile))
        backup(mainFile, ".pre-gui6.bak");
      if (migrated && Files.exists(mainFile)) backup(mainFile, ".pre-modules.bak");
      var documents = new LinkedHashMap<Path, String>();
      for (var entry : modules.entrySet()) {
        entry.getValue().set("gui", null);
        if (original.getInt("config-version", 0) < 5) {
          if (Files.exists(configFile(entry.getKey())))
            backup(configFile(entry.getKey()), ".pre-inline.bak");
          if (Files.exists(guiFile(entry.getKey())))
            backup(guiFile(entry.getKey()), ".pre-inline.bak");
        }
        if (original.getInt("config-version", 0) < 6) {
          if (Files.exists(configFile(entry.getKey())))
            backup(configFile(entry.getKey()), ".pre-gui6.bak");
          if (Files.exists(guiFile(entry.getKey())))
            backup(guiFile(entry.getKey()), ".pre-gui6.bak");
        }
        documents.put(configFile(entry.getKey()), entry.getValue().saveToString());
        documents.put(guiFile(entry.getKey()), guis.get(entry.getKey()).saveToString());
      }
      documents.put(mainFile, core.saveToString());
      // Prepare legacy backups before committing the replacement documents too.
      for (Path old : oldFiles) backup(old, ".pre-folders.bak");
      ConfigBatch.write(documents);
      for (Path old : oldFiles) {
        Files.delete(old);
      }
      return merged;
    }
  }

  /**
   * Shipped texts that turned out to be wrong and were replaced. An installation still holding one
   * of these exact old values gets the new default; anything an admin edited is left alone.
   */
  private static final Map<String, Map<String, List<String>>> RETIRED_TEXTS =
      Map.of(
          // 2026-11-01: the statistics button shows the section name only, without its number in the circle.
          "profile",
          Map.of(
              "menus.profile-menu-statistics.labels.profile-menu.section-line",
              List.of("<dark_gray>Раздел: <white><name> <dark_gray>(<index>/<count>)")),
          // /display now changes the owner's signature for everyone, including themselves.
          "display",
          Map.of(
              "messages.display.usage",
              List.of(
                  "<yellow>/display <gray>[own|others] [clan|suffix] [chat|tab|nametag] [on|off] — без аргументов открывается меню",
                  "<yellow>/display <gray>[others] <clan|suffix> <chat|tab|nametag> [on|off] — без слова others меняется ваша подпись у всех, с others — чужие подписи у вас; без аргументов открывается меню"),
              "menus.display-gui-title.title",
              List.of("<gold>Теги и пометки"),
              "messages.display.scope-own",
              List.of("Мои подписи"),
              "messages.display.scope-others",
              List.of("Чужие подписи"),
              "messages.display.all-on",
              List.of(
                  "<gold>Показать всё <dark_gray>| <white>12 настроек",
                  "<gold>Показать всё <dark_gray>| <white>6 настроек",
                  "<gold>Показать всё <dark_gray>| <white>4 настройки"),
              "messages.display.element-suffix",
              List.of("Суффикс")),
          // 2026-10-25: the message prefix reads "Сервер" instead of "Love".
          "npc",
          Map.of(
              "messages.chat.prefix", List.of("<dark_gray>[<gold>Love<dark_gray>] <reset>"),
              "messages.npc.selected", List.of("<gray>Выбран: <id>")),
          "sitride",
          Map.of(
              "messages.comfort.ride-consent",
              List.of(
                  "<yellow>Игрок должен разрешить посадку правом loveessential.ride.accept и"
                      + " находиться вне боя.",
                  "<yellow>Сесть на этого игрока сейчас нельзя: он не виден вам, не авторизован,"
                      + " находится в бою, либо посадка на игроков отключена.")));

  /**
   * 2026-10-25: the shipped scoreboard lines were rewritten as short phrases. A line whose installed
   * text is still exactly the one shipped before is replaced by the new one; anything an admin edited,
   * or a line the admin added, is left alone.
   */
  static void restyleScoreboard(
      YamlConfiguration module, ConfigurationSection defaults, ConfigurationSection previous) {
    var lines = defaults.getConfigurationSection("placeholders");
    var old = previous.getConfigurationSection("placeholders");
    var installed = module.getConfigurationSection("placeholders");
    if (lines == null || old == null || installed == null) return;
    for (String id : lines.getKeys(false)) {
      var now = installed.getConfigurationSection(id);
      var before = old.getConfigurationSection(id);
      var fresh = lines.getConfigurationSection(id);
      if (now == null || before == null) continue;
      if (now.isString("template") && now.getString("template").equals(before.getString("template")))
        now.set("template", fresh.getString("template"));
      var variants = now.getMapList("variants");
      var oldVariants = before.getMapList("variants");
      var newVariants = fresh.getMapList("variants");
      if (variants.isEmpty() || variants.size() != oldVariants.size() || variants.size() != newVariants.size())
        continue;
      var result = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < variants.size(); i++) {
        var copy = new LinkedHashMap<String, Object>();
        variants.get(i).forEach((k, v) -> copy.put(String.valueOf(k), v));
        if (Objects.equals(copy.get("template"), oldVariants.get(i).get("template")))
          copy.put("template", newVariants.get(i).get("template"));
        result.add(copy);
      }
      now.set("variants", result);
    }
  }

  /**
   * A lore line written as {@code - <gray>Text:} is read by YAML as the map {@code {<gray>Text: null}},
   * and the menu then shows {@code {Text=null}}. Such lines in an installed file are turned back into
   * the text they were meant to be.
   *
   * @return whether anything was repaired
   */
  static boolean repairMappedLines(YamlConfiguration y) {
    boolean changed = false;
    for (String key : List.copyOf(y.getKeys(true))) {
      if (!y.isList(key)) continue;
      var fixed = new ArrayList<Object>();
      boolean broken = false;
      for (Object item : y.getList(key)) {
        if (item instanceof Map<?, ?> map
            && map.size() == 1
            && map.keySet().iterator().next() instanceof String text
            && text.startsWith("<")) {
          Object rest = map.values().iterator().next();
          fixed.add(rest == null ? text + ":" : text + ": " + rest);
          broken = true;
        } else fixed.add(item);
      }
      if (broken) {
        y.set(key, fixed);
        changed = true;
      }
    }
    return changed;
  }

  static void refreshRetiredTexts(
      String id, YamlConfiguration gui, ConfigurationSection defaults) {
    var retired = RETIRED_TEXTS.get(id);
    if (retired == null) return;
    retired.forEach(
        (path, olds) -> {
          String current = gui.getString(path);
          String fresh = defaults.getString(path);
          if (current != null && fresh != null && olds.contains(current)) gui.set(path, fresh);
        });
    if (id.equals("npc")) {
      String path = "messages.help.npc-commands";
      var oldHelp = List.of(
          "<gold>Редактор NPC",
          "<gray>/le npc create <id>; /le npc delete <id> confirm",
          "<gray>/npc import citizens [номер|all] — перенести NPC из plugins/Citizens/saves.yml",
          "<gray>/npc sel [id] — выбрать NPC (смотря на него); /npc rename <имя> — переименовать, &-цвета работают",
          "<gray>/npc wizard; /npc edit <id>",
          "<gray>/npc set <параметр> <значение>; /npc here",
          "<gray>/npc path add|mode|point|remove|move|record|tool|show|undo|redo",
          "<gray>/npc dialog node|answer|start|answer-condition|answer-actions",
          "<gray>/npc save; /npc publish; /npc preview",
          "<gray>/npc delete <id> confirm",
          "<gray>/le npc — список, создание и удаление через меню");
      if (gui.getStringList(path).equals(oldHelp) && defaults.isList(path))
        gui.set(path, defaults.getStringList(path));
    }
    if (id.equals("display")) {
      var oldLores = Map.of(
          "messages.display.all-on-lore", List.of(
              "<dark_gray>Сброс выбора", "", "<dark_gray>▪ <gray>После клика:",
              "  <gray>Все теги и пометки других игроков снова видны вам.",
              "<dark_gray><st>─────────────────────</st>",
              "<green>ЛКМ <dark_gray>— <gray>показать всё"),
          "messages.display.tile-lore", List.of(
              "<dark_gray>Только у вас", "", "<dark_gray>▪ <gray>После клика:",
              "  <gray>Состояние: <now> <dark_gray>→ <next>",
              "  <gray>Другие игроки ничего не заметят.",
              "<dark_gray><st>─────────────────────</st>",
              "<green>ЛКМ <dark_gray>— <gray>переключить"));
      oldLores.forEach((path, old) -> {
        if (gui.getStringList(path).equals(old) && defaults.isList(path))
          gui.set(path, defaults.getStringList(path));
      });
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

  /**
   * Keys of the second personal-sidebar document (a catalog kept in SQLite and exported under
   * {@code catalog}); a file holding any of them is replaced. 2026-10-23: {@code placeholders},
   * {@code presets} and {@code categories} no longer count, because the personal scoreboard is back
   * and its catalog lives at exactly those keys.
   */
  private static final List<String> LEGACY_SCOREBOARD =
      List.of("catalog", "header", "max-selected-lines");

  /** Retires only the personal world card; world placeholders remain available to integrations. */
  static boolean removeWorldLine(YamlConfiguration module) {
    boolean removed = module.contains("placeholders.world");
    module.set("placeholders.world", null);
    if (module.getStringList("categories.general.lore")
        .equals(List.of("&7Координаты, мир, пинг, дата и время")))
      module.set("categories.general.lore", List.of("&7Координаты, пинг, дата и время"));
    return removed;
  }

  /**
   * A scoreboard.yml written by the second personal sidebar (before named boards) is backed up to
   * {@code scoreboard.yml.pre-named.bak} and replaced by the defaults.
   */
  private void migrateScoreboard(YamlConfiguration module) throws IOException {
    if (module.contains("scoreboards")
        || LEGACY_SCOREBOARD.stream().noneMatch(module::contains)) return;
    if (Files.exists(configFile("scoreboard")))
      backup(configFile("scoreboard"), ".pre-named.bak");
    boolean enabled = module.getBoolean("enabled", true);
    for (String key : List.copyOf(module.getKeys(false))) module.set(key, null);
    module.set("enabled", enabled);
  }

  /**
   * Moves TAB groups from the first version's keys (prefix, suffix, name, permission, priority,
   * players, worlds) to the property keys. The old file is backed up to {@code tab.yml.pre-properties.bak}.
   * Lists of players and worlds and the TAB-only sidebar have no equivalent and are dropped.
   */
  private void migrateTab(YamlConfiguration module) throws IOException {
    var groups = module.getConfigurationSection("groups");
    boolean legacy =
        module.contains("sidebar")
            || module.contains("header-footer.profiles")
            || module.contains("name-enabled")
            || module.contains("header-footer.header")
            || module.contains("header-footer.footer")
            || groups != null && groups.contains("default") && !groups.contains("_DEFAULT_");
    if (groups != null)
      for (String id : groups.getKeys(false)) {
        var group = groups.getConfigurationSection(id);
        if (group != null
            && List.of("prefix", "suffix", "name", "priority", "players", "worlds")
                .stream()
                .anyMatch(group::contains)) legacy = true;
      }
    if (!legacy) return;
    if (Files.exists(configFile("tab"))) backup(configFile("tab"), ".pre-properties.bak");
    if (groups != null && groups.contains("default") && !groups.contains("_DEFAULT_")) {
      Object renamed = snapshot(groups.getConfigurationSection("default"));
      module.set("groups.default", null);
      module.set("groups._DEFAULT_", renamed);
    }
    var migrated = module.getConfigurationSection("groups");
    if (migrated != null)
      for (String id : migrated.getKeys(false)) {
        var group = migrated.getConfigurationSection(id);
        if (group == null) continue;
        moveKey(group, "prefix", "tabprefix");
        moveKey(group, "suffix", "tabsuffix");
        moveKey(group, "name", "customtabname");
        if (group.contains("tabprefix") && !group.contains("tagprefix"))
          group.set("tagprefix", group.get("tabprefix"));
        if (group.contains("tabsuffix") && !group.contains("tagsuffix"))
          group.set("tagsuffix", group.get("tabsuffix"));
        for (String key : List.of("priority", "players", "worlds")) group.set(key, null);
        if (group.contains("permission") && group.getString("permission", "").isBlank())
          group.set("permission", null);
      }
    module.set("sidebar", null);
    module.set("name-enabled", null);
    module.set("header-footer.profiles", null);
    // Header and footer were global; they now belong to the default group, unless it has its own.
    var header = module.get("header-footer.header");
    var footer = module.get("header-footer.footer");
    if (migrated != null && migrated.isConfigurationSection("_DEFAULT_")) {
      var defaults = migrated.getConfigurationSection("_DEFAULT_");
      if (header != null && !defaults.contains("header")) defaults.set("header", header);
      if (footer != null && !defaults.contains("footer")) defaults.set("footer", footer);
    }
    module.set("header-footer.header", null);
    module.set("header-footer.footer", null);
  }

  private static void moveKey(ConfigurationSection section, String from, String to) {
    if (section.contains(from)) {
      if (!section.contains(to)) section.set(to, section.get(from));
      section.set(from, null);
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

  public static void setValue(ConfigurationSection target, String path, Object value) {
    Object copy = snapshot(value);
    if (copy instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      var values = (Map<String, Object>) map;
      target.createSection(path, values);
    } else target.set(path, copy);
  }

  public synchronized YamlConfiguration update(
      String module, String path, Object expected, Object value) throws Exception {
    synchronized (ModuleConfigs.class) {
      if (!LoveEssentialPlugin.MODULES.contains(module) || path.isBlank() || path.equals("enabled"))
        throw new IllegalArgumentException("errors.config");
      path = Presentation.canonicalPath(module, path);
      var merged = load();
      boolean gui = path.startsWith("gui.");
      Path file = gui ? guiFile(module) : configFile(module);
      String local = gui ? path.substring(4) : path;
      if (local.isBlank()) throw new IllegalArgumentException("errors.config");
      var y = read(file);
      Object actual = y.get(local);
      if (!Objects.equals(snapshot(actual), snapshot(expected)))
        throw new IllegalArgumentException("errors.conflict");
      if (y.isConfigurationSection(local) && !(value instanceof Map<?, ?>))
        throw new IllegalArgumentException("errors.config");
      y.set(local, value);
      merged.set(runtimePath(module, path), value);
      var candidate = new YamlConfiguration();
      candidate.loadFromString(merged.saveToString());
      Presentation.runtime(candidate);
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
    Presentation.stripRuntime(y, id);
    return y;
  }

  private static void stripAliases(ConfigurationSection section) {
    for (String key : List.copyOf(section.getKeys(true)))
      if (Presentation.referencePath(key)) section.set(key, null);
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

  private static final String STATISTICS = "menus.profile-menu-statistics";

  /**
   * Whether the installed statistics categories are still exactly the ones shipped before the five-button
   * layout, so they can be swapped for the new ones without losing anything an admin wrote.
   */
  static boolean unchangedLegacyStatistics(YamlConfiguration gui, ConfigurationSection legacy) {
    if (gui.getInt(STATISTICS + ".sections-version", 0) >= 1) return false;
    Object installed = snapshot(gui.get(STATISTICS + ".categories"));
    Object shipped = snapshot(legacy.get(STATISTICS + ".categories"));
    return installed instanceof Map && Objects.equals(installed, shipped);
  }

  /** {@link #fill} that names the file in the error, so the admin knows which one to open. */
  private static void fillIn(String file, YamlConfiguration target, ConfigurationSection defaults) {
    try {
      fill(target, defaults);
    } catch (ConfigError e) {
      throw new ConfigError(file + ": " + e.detail());
    }
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
                "menus.profile-menu-statistics.categories",
                "menus.profile-menu-settings.controls",
                "menus.players-title.cards",
                "header-footer.profiles")
            .stream()
            .filter(target::contains)
            .toList();
    for (String key : defaults.getKeys(true)) {
      if (preserve.stream().anyMatch(root -> key.startsWith(root + "."))) continue;
      if (defaults.isConfigurationSection(key)
          && target.contains(key)
          && !target.isConfigurationSection(key)
          && emptyValue(target.get(key))) {
        // "animations: []" or a bare "animations:" where a section belongs: nothing was set, so the
        // shipped defaults come back instead of the plugin refusing to start.
        java.util.logging.Logger.getLogger("LoveEssential")
            .warning("ключ «" + key + "» пуст, но должен быть разделом: возвращены настройки по умолчанию");
        target.set(key, null);
        target.createSection(key);
      }
      if (defaults.isConfigurationSection(key)
          && target.contains(key)
          && !target.isConfigurationSection(key))
        throw new ConfigError(
            "ключ «" + key + "» должен быть разделом (вложенными настройками), а найдено значение «"
                + target.get(key) + "»");
      if (!target.contains(key)) {
        if (defaults.isConfigurationSection(key)) target.createSection(key);
        else target.set(key, snapshot(defaults.get(key)));
      }
    }
  }

  /** Null, blank text, an empty list or an empty map: a value that carries no setting. */
  static boolean emptyValue(Object value) {
    return value == null
        || value instanceof String text && text.isBlank()
        || value instanceof java.util.Collection<?> list && list.isEmpty()
        || value instanceof Map<?, ?> map && map.isEmpty();
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
