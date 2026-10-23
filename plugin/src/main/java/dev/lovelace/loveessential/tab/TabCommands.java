package dev.lovelace.loveessential.tab;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.config.ModuleConfigs;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** {@code /le tab ...}: administration of tab.yml, diagnostics, and personal toggles. */
public final class TabCommands {
  private final LoveEssentialPlugin plugin;
  private final Lang lang;
  private final LoveBridge love;
  private final TabModule tab;
  private final TabSettings settings;

  public TabCommands(
      LoveEssentialPlugin plugin, Lang lang, LoveBridge love, TabModule tab, TabSettings settings) {
    this.plugin = plugin;
    this.lang = lang;
    this.love = love;
    this.tab = tab;
    this.settings = settings;
  }

  private void admin(CommandSender sender) {
    if (!plugin.ready()) throw new IllegalArgumentException("errors.starting");
    if (sender instanceof Player p && !love.authenticated(p))
      throw new IllegalArgumentException("errors.access");
    if (!sender.hasPermission("loveessential.admin"))
      throw new IllegalArgumentException("errors.permission");
  }

  /** {@code args} are the words after {@code /le tab}. */
  public void run(CommandSender sender, String[] args) {
    if (args.length == 0) {
      if (sender instanceof Player p) settings.open(p);
      else lang.send(sender, "help.tab-settings");
      return;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "reload" -> {
        admin(sender);
        complete(sender, plugin.reloadSettings(), "common.reloaded");
      }
      case "group" -> {
        admin(sender);
        if (args.length < 3) throw new IllegalArgumentException("errors.input");
        setProperty(sender, "groups", args[1], args[2], rest(args, 3));
      }
      case "player" -> {
        admin(sender);
        if (args.length < 3) throw new IllegalArgumentException("errors.input");
        setProperty(sender, "users", args[1], args[2], rest(args, 3));
      }
      case "debug" -> {
        admin(sender);
        if (args.length != 2) throw new IllegalArgumentException("errors.input");
        debug(sender, target(args[1]));
      }
      case "parse" -> {
        admin(sender);
        if (args.length < 3) throw new IllegalArgumentException("errors.input");
        var target = target(args[1]);
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        sender.sendMessage(tab.placeholders().renderRaw(text, target, target));
      }
      case "cpu" -> {
        admin(sender);
        var averages = tab.cpuMillis();
        if (averages.isEmpty()) lang.send(sender, "tab.cpu-empty");
        averages.forEach(
            (name, ms) ->
                lang.send(
                    sender,
                    "tab.cpu-line",
                    Map.of("name", name, "ms", String.format(Locale.ROOT, "%.3f", ms))));
      }
      case "export" -> {
        admin(sender);
        export(sender);
      }
      case "nametag" -> {
        if (args.length != 2 || !args[1].equalsIgnoreCase("toggle"))
          throw new IllegalArgumentException("errors.input");
        personal(sender, "nametag");
      }
      case "bossbar" -> {
        if (args.length == 2 && args[1].equalsIgnoreCase("toggle")) {
          personal(sender, "bossbar");
        } else if (args.length == 4 && args[1].equalsIgnoreCase("announce")) {
          admin(sender);
          long seconds = parseSeconds(args[3]);
          tab.announce(args[2], seconds);
          lang.send(sender, "tab.announced", Map.of("name", args[2]));
        } else throw new IllegalArgumentException("errors.input");
      }
      default -> throw new IllegalArgumentException("errors.input");
    }
  }

  /** The value of a property: every word after the property name, or null when none is given. */
  private static String rest(String[] args, int from) {
    if (args.length <= from) return null;
    return String.join(" ", Arrays.copyOfRange(args, from, args.length));
  }

  private static long parseSeconds(String value) {
    try {
      long seconds = Long.parseLong(value);
      if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("errors.input");
      return seconds;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("errors.input", e);
    }
  }

  private Player target(String name) {
    var target = Bukkit.getPlayerExact(name);
    if (target == null) throw new IllegalArgumentException("errors.input");
    return target;
  }

  private void complete(CommandSender sender, CompletableFuture<?> future, String key) {
    future.whenComplete(
        (v, error) ->
            Bukkit.getScheduler()
                .runTask(
                    plugin,
                    () -> {
                      if (error != null) lang.error(sender, error);
                      else lang.send(sender, key);
                    }));
  }

  /** Writes one property into tab.yml; a missing value removes the property. */
  private void setProperty(CommandSender sender, String section, String id, String property, String value) {
    TabProperties.validate(property, value);
    if (value != null) {
      try {
        MiniMessage.miniMessage().deserialize(value);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("errors.input", e);
      }
    }
    if (!id.matches("[A-Za-z0-9_.-]{1,48}")) throw new IllegalArgumentException("errors.input");
    String path = section + "." + id + "." + property;
    Object expected = ModuleConfigs.snapshot(plugin.getConfig().get("tab." + path));
    complete(sender, plugin.editSetting("tab", path, expected, value), "chat.saved");
  }

  private void personal(CommandSender sender, String kind) {
    if (!(sender instanceof Player p)) throw new IllegalArgumentException("errors.player-only");
    if (!love.authenticated(p)) throw new IllegalArgumentException("errors.access");
    var prefs = tab.preferences();
    var current = prefs.get(p.getUniqueId());
    if (!prefs.loaded(p.getUniqueId())) throw new IllegalArgumentException("errors.starting");
    boolean nametag = kind.equals("nametag");
    var next =
        nametag
            ? new TabPreferences.Prefs(!current.nametagsHidden(), current.bossbarsHidden())
            : new TabPreferences.Prefs(current.nametagsHidden(), !current.bossbarsHidden());
    boolean hidden = nametag ? next.nametagsHidden() : next.bossbarsHidden();
    prefs
        .save(p.getUniqueId(), next)
        .whenComplete(
            (v, error) ->
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (!p.isOnline()) return;
                          if (error != null) lang.send(p, "errors.storage");
                          else lang.send(p, "tab." + kind + (hidden ? "-hidden" : "-shown"));
                        }));
  }

  private void debug(CommandSender sender, Player target) {
    var target0 = tab.target(target);
    lang.send(sender, "tab.debug-group", Map.of("group", target0.group().isEmpty() ? "-" : target0.group()));
    for (String key : TabProperties.KEYS) {
      var value = target0.properties().value(key);
      if (value.isEmpty()) continue;
      lang.send(
          sender,
          "tab.debug-property",
          Map.of(
              "key", key,
              "value", value.get(),
              "source", target0.properties().sources().getOrDefault(key, "-")));
    }
  }

  private void export(CommandSender sender) {
    var out = new YamlConfiguration();
    for (String key : List.of("groups", "users", "conditions", "animations", "engine-conditions"))
      out.set(key, ModuleConfigs.snapshot(plugin.getConfig().get("tab." + key)));
    var file = plugin.getDataFolder().toPath().resolve("tab-export.yml");
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, out.saveToString());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("errors.storage", e);
    }
    lang.send(sender, "tab.exported", Map.of("file", file.getFileName().toString()));
  }
}
