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
    values = flatten(y);
  }

  public synchronized void putAll(Map<String, String> texts) throws Exception {
    if (texts.isEmpty()) return;
    var y = new YamlConfiguration();
    y.load(file.toFile());
    for (var e : texts.entrySet()) {
      if (!e.getKey().matches("[a-zA-Z0-9_.-]+") || e.getValue().length() > 8192)
        throw new IllegalArgumentException("invalid-text");
      y.set(e.getKey(), e.getValue());
    }
    Files.copy(file, file.resolveSibling("lang.yml.bak"), StandardCopyOption.REPLACE_EXISTING);
    atomic(y);
    values = flatten(y);
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
    return key != null && values.containsKey(key);
  }

  public String text(String key) {
    Object v = values.get(key);
    return v == null
        ? "[" + key + "]"
        : v instanceof List<?> l
            ? String.join("\n", l.stream().map(Object::toString).toList())
            : v.toString();
  }

  public List<String> lines(String key) {
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
