package dev.lovelace.loveessential.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Literal user documents, with a private resource index for legacy API lookup names. */
public final class Presentation {
  public record Binding(String module, String file, String key, String path) {}

  public record Move(String module, String from, String to) {}

  private static final List<Binding> BINDINGS;
  private static final List<Map<?, ?>> ALIASES, UPDATES;
  private static final List<Move> MOVES;

  static {
    try (var stream =
        Presentation.class.getClassLoader().getResourceAsStream("presentation-bindings.yml")) {
      var y = new YamlConfiguration();
      y.load(new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8));
      ALIASES = List.copyOf(y.getMapList("aliases"));
      UPDATES = List.copyOf(y.getMapList("updates"));
      MOVES =
          y.getMapList("moves").stream()
              .map(
                  v ->
                      new Move(
                          v.get("module").toString(),
                          v.get("from").toString(),
                          v.get("to").toString()))
              .toList();
      BINDINGS =
          y.getMapList("bindings").stream()
              .map(
                  v ->
                      new Binding(
                          v.get("module").toString(),
                          v.get("file").toString(),
                          v.get("key").toString(),
                          v.get("path").toString()))
              .toList();
    } catch (Exception error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  public static List<Binding> bindings() {
    return BINDINGS;
  }

  public static void migrate(
      String id,
      YamlConfiguration module,
      YamlConfiguration gui,
      YamlConfiguration oldLanguage,
      YamlConfiguration moduleDefaults,
      YamlConfiguration guiDefaults) {
    var language = new HashMap<String, Object>();
    for (var document : List.of(moduleDefaults, guiDefaults, oldLanguage, module, gui)) {
      for (String section : List.of("texts", "messages")) {
        var texts = document.getConfigurationSection(section);
        if (texts != null)
          for (String key : texts.getKeys(true))
            if (!texts.isConfigurationSection(key)) language.put(key, texts.get(key));
      }
      if (document == oldLanguage)
        for (String key : document.getKeys(true))
          if (!document.isConfigurationSection(key)) language.put(key, document.get(key));
    }
    for (var document : List.of(module, gui)) {
      var texts = document.getConfigurationSection("texts");
      if (texts != null)
        for (String key : texts.getKeys(true))
          if (!texts.isConfigurationSection(key) && !document.contains("messages." + key))
            document.set("messages." + key, texts.get(key));
      document.set("texts", null);
      inline(document, language);
    }
    var buttons = gui.getConfigurationSection("buttons");
    if (buttons != null)
      for (String key : buttons.getKeys(false)) {
        var button = buttons.getConfigurationSection(key);
        if (button == null) continue;
        // Default shared styles were only duplicate labels; preserve actual overrides.
        var fields = new HashMap<>(button.getValues(false));
        Object title = fields.get("title");
        boolean defaultTitle = Objects.equals(title, language.get(key.replace('-', '.')));
        if (defaultTitle) fields.remove("title");
        fields.values().removeIf(value -> value instanceof String text && text.isEmpty());
        if (!fields.isEmpty()) gui.createSection("components." + key, fields);
      }
    gui.set("buttons", null);
    for (var binding : BINDINGS)
      if (binding.module().equals(id)) {
        var document = binding.file().equals("gui.yml") ? gui : module;
        Object value = document.get("messages." + binding.key());
        if (!document.contains(binding.path()) && value != null)
          document.set(binding.path(), value);
        document.set("messages." + binding.key(), null);
      }
    for (var move : MOVES)
      if (move.module().equals(id)) {
        var source = move.from().startsWith("gui.") ? gui : module;
        var target = move.to().startsWith("gui.") ? gui : module;
        String from = local(move.from()), to = local(move.to());
        if (source.contains(from)) {
          if (!target.contains(to)) ModuleConfigs.setValue(target, to, source.get(from));
          else if (!Objects.equals(
              ModuleConfigs.snapshot(source.get(from)), ModuleConfigs.snapshot(target.get(to))))
            throw new IllegalArgumentException("errors.conflict");
          source.set(from, null);
        }
      }
    for (var update : UPDATES)
      if (update.get("module").equals(id)) {
        var document = update.get("file").equals("gui.yml") ? gui : module;
        String path = update.get("path").toString();
        if (Objects.equals(ModuleConfigs.snapshot(document.get(path)), update.get("previous")))
          document.set(path, update.get("value"));
      }
    if (id.equals("profile")) gui.set("menus.profile-stats-details-title", null);
  }

  private static String local(String path) {
    return path.startsWith("gui.") ? path.substring(4) : path;
  }

  public static String canonicalPath(String module, String path) {
    for (var move : MOVES)
      if (move.module().equals(module)
          && (path.equals(move.from()) || path.startsWith(move.from() + ".")))
        return move.to() + path.substring(move.from().length());
    return path;
  }

  public static void stripRuntime(ConfigurationSection section, String module) {
    for (var move : MOVES) if (move.module().equals(module)) section.set(move.from(), null);
  }

  private static void inline(ConfigurationSection document, Map<String, Object> language) {
    for (String path : List.copyOf(document.getKeys(true))) {
      if (!referencePath(path)) continue;
      Object raw = document.get(path);
      String target = path.substring(0, path.lastIndexOf('-'));
      if (raw instanceof ConfigurationSection section) {
        if (!document.contains(target)) document.set(target, ModuleConfigs.snapshot(section));
        document.set(path, null);
        continue;
      }
      Object resolved =
          raw instanceof List<?> list
              ? list.stream().map(v -> language.getOrDefault(v.toString(), v)).toList()
              : language.getOrDefault(Objects.toString(raw, ""), raw);
      if (!document.contains(target) && resolved != null && !resolved.equals(""))
        document.set(target, ModuleConfigs.snapshot(resolved));
      document.set(path, null);
    }
  }

  static boolean referencePath(String path) {
    if (!path.endsWith("-key") && !path.endsWith("-keys")) return false;
    // Catalog IDs and templates are user data, even when their names end in '-key'.
    if (path.startsWith("placeholders.") || path.contains(".placeholders.")) return false;
    int dot = path.lastIndexOf('.');
    if (dot < 0) return true;
    String parent = path.substring(0, dot);
    parent = parent.substring(parent.lastIndexOf('.') + 1);
    return !Set.of("presets", "categories", "groups", "users", "worlds").contains(parent);
  }

  /** Old implementation names exist only in memory, never in files. */
  public static void runtime(ConfigurationSection document) {
    for (var move : MOVES) {
      String root = ModuleConfigs.prefix(move.module()) + ".";
      Object value = document.get(root + move.to());
      if (value != null) ModuleConfigs.setValue(document, root + move.from(), value);
    }
    for (var alias : ALIASES) {
      String module = alias.get("module").toString();
      String root =
          ModuleConfigs.prefix(module) + (alias.get("file").equals("gui.yml") ? ".gui." : ".");
      String path = root + alias.get("literal");
      Object value = document.get(path);
      String target = root + alias.get("path");
      if (value instanceof String text) document.set(target, "@inline:" + text);
      else if (value instanceof List<?> list)
        document.set(
            target,
            Boolean.TRUE.equals(alias.get("list"))
                ? list.stream().map(v -> "@inline:" + v).toList()
                : "@inline:" + String.join("\n", list.stream().map(Object::toString).toList()));
    }
  }
}
