package dev.lovelace.loveessential.lang;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Immutable lookup snapshot; writes are serialized and use atomic replacement. */
public final class Lang {
  private final Path file;
  private final Map<String, Object> defaults;
  private volatile Map<String, Object> values = Map.of();

  private record Source(Path file, String path) {}

  private volatile Map<String, Source> owners = Map.of();
  private final MiniMessage mm = MiniMessage.miniMessage();

  public Lang(JavaPlugin plugin) throws Exception {
    file = plugin.getDataFolder().toPath().resolve("lang.yml");
    try (var input = plugin.getResource("lang.yml")) {
      if (input == null) throw new IOException("missing-lang-resource");
      var y = new YamlConfiguration();
      y.load(new InputStreamReader(input, StandardCharsets.UTF_8));
      defaults = flatten(y);
    }
    reload();
  }

  public synchronized void reload() throws Exception {
    Files.createDirectories(file.getParent());
    var y = new YamlConfiguration();
    if (Files.exists(file)) y.load(file.toFile());
    boolean changed = false;
    for (var e : defaults.entrySet())
      if (!y.contains(e.getKey())) {
        y.set(e.getKey(), e.getValue());
        changed = true;
      }
    if (changed || !Files.exists(file)) atomic(y);
    var combined = new HashMap<>(flatten(y));
    var sources = new HashMap<String, Source>();
    for (String module :
        dev.lovelace.loveessential.LoveEssentialPlugin.MODULES.stream().sorted().toList()) {
      for (String name : List.of(module + ".yml", "gui.yml")) {
        Path local = file.getParent().resolve("modules/" + module + "/" + name);
        if (!Files.exists(local)) continue;
        var document = new YamlConfiguration();
        document.load(local.toFile());
        var texts = document.getConfigurationSection("messages");
        if (texts == null) texts = document.getConfigurationSection("texts");
        for (var binding : dev.lovelace.loveessential.config.Presentation.bindings())
          if (binding.module().equals(module)
              && binding.file().equals(name)
              && document.contains(binding.path())) {
            combined.put(binding.key(), document.get(binding.path()));
            sources.put(binding.key(), new Source(local, binding.path()));
          }
        if (texts == null) continue;
        for (String key : texts.getKeys(true))
          if (!texts.isConfigurationSection(key)) {
            Object value = texts.get(key);
            if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof List<?>) {
              combined.put(key, value);
              sources.put(key, new Source(local, "messages." + key));
            }
          }
      }
    }
    values = Map.copyOf(combined);
    owners = Map.copyOf(sources);
  }

  public synchronized void putAll(Map<String, String> texts) throws Exception {
    if (texts.isEmpty()) return;
    synchronized (dev.lovelace.loveessential.config.ModuleConfigs.class) {
      var documents = new LinkedHashMap<Path, YamlConfiguration>();
      for (var entry : texts.entrySet()) {
        if (!entry.getKey().matches("[a-zA-Z0-9_.-]+") || entry.getValue().length() > 8192)
          throw new IllegalArgumentException("invalid-text");
        Source source = owners.get(entry.getKey());
        Path target = source == null ? null : source.file();
        if (target == null) {
          String module = dev.lovelace.loveessential.gui.GuiSettings.module(entry.getKey());
          target = file.getParent().resolve("modules/" + module + "/" + module + ".yml");
        }
        var document = documents.get(target);
        if (document == null) {
          document = new YamlConfiguration();
          if (Files.exists(target)) document.load(target.toFile());
          documents.put(target, document);
        }
        document.set(
            source == null ? "messages." + entry.getKey() : source.path(), entry.getValue());
      }
      for (var entry : documents.entrySet()) {
        Path target = entry.getKey();
        Files.createDirectories(target.getParent());
        if (Files.exists(target))
          Files.copy(
              target,
              target.resolveSibling(target.getFileName() + ".bak"),
              StandardCopyOption.REPLACE_EXISTING);
        atomic(target, entry.getValue());
      }
      reload();
    }
  }

  private static Map<String, Object> flatten(YamlConfiguration y) {
    var out = new HashMap<String, Object>();
    y.getValues(true)
        .forEach(
            (k, v) -> {
              if (v instanceof String || v instanceof Number || v instanceof Boolean) out.put(k, v);
              else if (v instanceof List<?> l) out.put(k, List.copyOf(l));
            });
    return Map.copyOf(out);
  }

  private void atomic(YamlConfiguration y) throws IOException {
    atomic(file, y);
  }

  private void atomic(Path file, YamlConfiguration y) throws IOException {
    var temp = Files.createTempFile(file.getParent(), "lang-", ".tmp");
    try {
      Files.writeString(temp, y.saveToString(), StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public boolean contains(String key) {
    return key != null && (key.startsWith("@inline:") || values.containsKey(key));
  }

  public String text(String key) {
    if (key != null && key.startsWith("@inline:")) return key.substring(8);
    Object v = values.get(key);
    return v == null
        ? "[" + key + "]"
        : v instanceof List<?> l
            ? String.join("\n", l.stream().map(Object::toString).toList())
            : v.toString();
  }

  public List<String> lines(String key) {
    if (key != null && key.startsWith("@inline:"))
      return Arrays.asList(key.substring(8).split("\n", -1));
    Object v = values.get(key);
    return v instanceof List<?> l ? l.stream().map(Object::toString).toList() : List.of(text(key));
  }

  public Component render(String key) {
    return render(key, Map.of());
  }

  public Component render(String key, Map<String, String> variables) {
    return renderText(text(key), variables);
  }

  public Component renderText(String template, Map<String, String> variables) {
    var b = TagResolver.builder();
    variables.forEach((k, v) -> b.resolver(Placeholder.unparsed(k, v)));
    return mm.deserialize(template, b.build());
  }

  public Component rich(String key, Map<String, Component> variables) {
    var b = TagResolver.builder();
    variables.forEach((k, v) -> b.resolver(Placeholder.component(k, v)));
    return mm.deserialize(text(key), b.build());
  }

  public void error(CommandSender to, Throwable error) {
    while ((error instanceof java.util.concurrent.CompletionException
            || error instanceof java.util.concurrent.ExecutionException)
        && error.getCause() != null) error = error.getCause();
    if (error instanceof dev.lovelace.loveessential.module.ModulePolicy.Denied denied) {
      send(to, denied.getMessage(), denied.variables());
      return;
    }
    send(
        to,
        error instanceof dev.lovelace.loveessential.storage.SqliteStore.Conflict
            ? "errors.conflict"
            : contains(error.getMessage()) ? error.getMessage() : "errors.input");
  }

  public void send(CommandSender to, String key) {
    send(to, key, Map.of());
  }

  public void send(CommandSender to, String key, Map<String, String> vars) {
    Component message = render("chat.prefix").append(render(key, vars));
    if (key.equals("errors.input") || key.endsWith(".usage"))
      message =
          message
              .append(Component.space())
              .append(
                  render("chat.commands")
                      .clickEvent(
                          net.kyori.adventure.text.event.ClickEvent.runCommand(
                              "/loveessential:loveessential help")));
    if (key.equals("wizard.published") && vars.containsKey("id"))
      message =
          message
              .append(Component.space())
              .append(
                  render("chat.edit")
                      .clickEvent(
                          net.kyori.adventure.text.event.ClickEvent.runCommand(
                              "/loveessential:npc edit " + vars.get("id"))));
    to.sendMessage(message);
  }
}
