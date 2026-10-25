package dev.lovelace.loveessential.command;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.npc.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class CommandSuggestions {
  private final LoveEssentialPlugin plugin;
  private final NpcService npcs;
  private final Gameplay gameplay;
  private final dev.lovelace.loveessential.module.PlayerProfiles profiles;
  private final LoveBridge love;
  private final dev.lovelace.loveessential.module.PrivateMessages messages;
  private static final Map<String, String> NPC_PERMISSIONS =
      Map.ofEntries(
          Map.entry("menu", "view"),
          Map.entry("help", "view"),
          Map.entry("wizard", "edit"),
          Map.entry("create", "create"),
          Map.entry("edit", "edit"),
          Map.entry("set", "edit"),
          Map.entry("here", "edit"),
          Map.entry("page", "edit"),
          Map.entry("save", "edit"),
          Map.entry("discard", "edit"),
          Map.entry("publish", "publish"),
          Map.entry("preview", "edit"),
          Map.entry("path", "path"),
          Map.entry("dialog", "dialog"),
          Map.entry("clone", "create"),
          Map.entry("export", "export"),
          Map.entry("import", "import"),
          Map.entry("schedule", "edit"),
          Map.entry("skin", "edit"),
          Map.entry("list", "view"),
          Map.entry("debug", "debug"),
          Map.entry("select", "view"),
          Map.entry("sel", "view"),
          Map.entry("info", "view"),
          Map.entry("deselect", "view"),
          Map.entry("unselect", "view"),
          Map.entry("rename", "edit"),
          Map.entry("delete", "delete"),
          Map.entry("enable", "publish"),
          Map.entry("disable", "publish"));
  private static final Map<String, String> ALIASES =
      Map.ofEntries(
          Map.entry("создать", "create"),
          Map.entry("выбрать", "select"),
          Map.entry("инфо", "info"),
          Map.entry("снять", "deselect"),
          Map.entry("сбросить", "deselect"),
          Map.entry("меню", "menu"),
          Map.entry("помощь", "help"),
          Map.entry("мастер", "wizard"),
          Map.entry("список", "list"),
          Map.entry("отладка", "debug"),
          Map.entry("удалить", "delete"),
          Map.entry("изменить", "edit"),
          Map.entry("настроить", "set"),
          Map.entry("путь", "path"),
          Map.entry("диалог", "dialog"),
          Map.entry("расписание", "schedule"),
          Map.entry("скин", "skin"),
          Map.entry("копия", "clone"),
          Map.entry("экспорт", "export"),
          Map.entry("импорт", "import"),
          Map.entry("переименовать", "rename"),
          Map.entry("включить", "enable"),
          Map.entry("выключить", "disable"));

  public CommandSuggestions(
      LoveEssentialPlugin plugin,
      NpcService npcs,
      Gameplay gameplay,
      LoveBridge love,
      dev.lovelace.loveessential.module.PrivateMessages messages,
      dev.lovelace.loveessential.module.PlayerProfiles profiles) {
    this.plugin = plugin;
    this.npcs = npcs;
    this.gameplay = gameplay;
    this.love = love;
    this.messages = messages;
    this.profiles = profiles;
  }

  private dev.lovelace.loveessential.module.Revive revive;

  public CommandSuggestions revive(dev.lovelace.loveessential.module.Revive revive) {
    this.revive = revive;
    return this;
  }

  public List<String> complete(CommandSender sender, String command, String[] args) {
    if (!plugin.ready()) return List.of();
    if (sender instanceof Player p && !love.authenticated(p)) return List.of();
    if (command.equals("loveessential")) {
      if (args.length <= 1) {
        var out = new ArrayList<>(List.of("help", "помощь"));
        if (NPC_PERMISSIONS.values().stream()
            .anyMatch(v -> sender.hasPermission("loveessential.npc." + v))) out.add("npc");
        if (sender.hasPermission("loveessential.admin"))
          out.addAll(
              List.of("menu", "меню", "module", "reload", "integrations", "backup", "tab", "таб"));
        for (String name : plugin.getDescription().getCommands().keySet())
          if (!name.equals("loveessential")
              && !name.equals("npc")
              && (sender.hasPermission("loveessential.admin")
                  || Set.of(
                          "waystone",
                          "home",
                          "sethome",
                          "delhome",
                          "spawn",
                          "afk",
                          "skin",
                          "sit",
                          "lay",
                          "eject",
                          "ride",
                          "hat",
                          "traces",
                          "profile",
                          "nick",
                          "msg",
                          "reply",
                          "ignore",
                          "msgtoggle",
                          "scoreboard",
                          "report")
                      .contains(name)
                  || name.equals("invsee") && sender.hasPermission("loveessential.invsee")))
            out.add(name);
        return out;
      }
      String op = args[0].toLowerCase(Locale.ROOT);
      String routed = resolve(plugin, op);
      if (routed != null && !routed.equals("loveessential"))
        return complete(sender, routed, Arrays.copyOfRange(args, 1, args.length));
      if (!sender.hasPermission("loveessential.admin")) return List.of();
      if (op.equals("module") || op.equals("модуль")) {
        if (args.length == 2) return List.copyOf(LoveEssentialPlugin.MODULES);
        if (args.length == 3 && LoveEssentialPlugin.MODULES.contains(args[1]))
          return List.of("enable", "disable", "info", "settings", "reload");
      }
      return List.of();
    }
    if (command.equals("nick")) {
      if (!plugin.module("nickname")) return List.of();
      int offset = 0;
      if (args.length > 0 && args[0].equalsIgnoreCase("player")) {
        if (!sender.hasPermission("loveessential.nick.others")) return List.of();
        if (args.length == 2) return players(sender);
        if (args.length > 2) offset = 2;
      }
      if (args.length <= offset + 1) {
        var out = new ArrayList<>(List.of("set", "reset"));
        out.add("color");
        out.add("gradient");
        if (offset == 0 && sender.hasPermission("loveessential.nick.others")) out.add("player");
        return out;
      }
      String op = args[offset].toLowerCase(Locale.ROOT);
      if (Set.of("color", "gradient").contains(op)) {
        var out = new ArrayList<String>();
        if (args.length == offset + 2) out.add("reset");
        if (op.equals("color") ? args.length == offset + 2 : args.length <= offset + 6) {
          out.addAll(net.kyori.adventure.text.format.NamedTextColor.NAMES.keys());
          out.addAll(List.of("#ff6699", "#ffcc66", "#55ffff"));
        }
        return out;
      }
      return List.of();
    }
    if (command.equals("npc")) {
      if (!plugin.module("npc")) return List.of();
      if (args.length <= 1) {
        var result = new TreeSet<String>();
        NPC_PERMISSIONS.entrySet().stream()
            .filter(e -> sender.hasPermission("loveessential.npc." + e.getValue()))
            .map(Map.Entry::getKey)
            .forEach(result::add);
        ALIASES.forEach((alias, canonical) -> {
          if (result.contains(canonical)) result.add(alias);
        });
        return List.copyOf(result);
      }
      String op = args[0].toLowerCase(Locale.ROOT);
      op = ALIASES.getOrDefault(op, op);
      if (!sender.hasPermission("loveessential.npc." + NPC_PERMISSIONS.getOrDefault(op, "admin")))
        return List.of();
      if (args.length == 2
          && Set.of(
                  "edit",
                  "info",
                  "sel",
                  "select",
                  "debug",
                  "delete",
                  "enable",
                  "disable",
                  "clone",
                  "export",
                  "schedule",
                  "skin")
              .contains(op)) {
        var result = new ArrayList<>(ids());
        if (op.equals("delete")) result.addAll(List.of("confirm", "cancel"));
        return result;
      }
      if (op.equals("set")) {
        if (args.length == 2) return NpcOptions.fields();
        if (args.length == 3) {
          if (args[1].equals("follow-player") || args[1].equals("skin")) return players(sender);
          return NpcOptions.choices(args[1], plugin.getConfig());
        }
      }
      if (op.equals("delete") && args.length == 3 && npcs.get(npcs.resolve(args[1])).isPresent())
        return List.of("confirm");
      if (op.equals("page") && args.length == 2) return List.of("1", "2", "3", "4", "5", "6", "7");
      if (op.equals("path")) {
        if (args.length == 2)
          return List.of(
              "wizard", "add", "point", "mode", "remove", "move", "undo", "redo", "record", "tool",
              "show", "test", "pause", "resume", "step");
        if (args.length == 3 && args[1].equals("wizard")) return ids();
        if (args.length == 3 && args[1].equals("mode"))
          return List.of("ONCE", "LOOP", "PING_PONG", "RANDOM");
        if (args.length == 4 && args[1].equals("point"))
          return List.of("wait", "speed", "radius", "position", "condition", "actions");
      }
      if (op.equals("dialog")) {
        if (args.length == 2)
          return List.of(
              "wizard", "node", "answer", "start", "remove", "answer-condition", "answer-actions");
        if (args.length == 3 && args[1].equals("wizard")) return ids();
      }
      return List.of();
    }
    if (!(sender instanceof Player p)) return List.of();
    if (command.equals("profile")) {
      if (!plugin.module("profile")) return List.of();
      var names =
          Bukkit.getOnlinePlayers().stream()
              .filter(target -> profiles.canView(p, target))
              .map(Player::getName)
              .toList();
      if (args.length == 1) {
        var out = new ArrayList<>(names);
        out.addAll(
            List.of(
                "view",
                "list",
                "settings",
                "stats",
                "statistics",
                "activity",
                "privacy",
                "hide",
                "show",
                "toggle"));
        return out;
      }
      if (args.length == 2)
        return switch (args[0].toLowerCase(Locale.ROOT)) {
          case "view" -> names;
          case "activity" -> List.of("show", "hide", "toggle");
          case "privacy" -> List.of("public", "private");
          default -> List.of();
        };
      return List.of();
    }
    if (Set.of("msg", "ignore", "invsee").contains(command)) {
      String module = command.equals("invsee") ? "inventory" : "messages";
      String permission = command.equals("invsee") ? "invsee" : module;
      if (!plugin.allows(module, p)
          || command.equals("invsee") && !p.hasPermission("loveessential." + permission))
        return List.of();
      if (command.equals("invsee") && args.length == 2)
        return p.hasPermission("loveessential.invsee.edit")
            ? List.of("view", "edit")
            : List.of("view");
      if (args.length == 1) {
        var out = new ArrayList<>(players(sender));
        if (command.equals("ignore")) out.addAll(messages.ignoredNames(p));
        return out;
      }
    }
    if (command.equals("ride") && args.length == 1 && plugin.module("sitride")) {
      var options = new ArrayList<>(players(sender));
      options.addAll(List.of("eject", "сбросить"));
      return options;
    }
    if (command.equals("revive") && plugin.module("revive")) {
      if (args.length == 1)
        return List.of("suicide", "revive", "down", "kill").stream()
            .filter(sub -> sub.equals("suicide") || sender.hasPermission("loveessential.revive.command." + sub))
            .toList();
      if (args.length == 2 && Set.of("revive", "kill").contains(args[0].toLowerCase(Locale.ROOT)))
        return revive == null ? List.of() : revive.downedNames();
      if (args.length == 2 && args[0].equalsIgnoreCase("down")) return players(sender);
      return List.of();
    }
    if (command.equals("traces") && args.length == 1 && plugin.module("traces"))
      return List.of("on", "off");
    if (command.equals("god") && args.length == 1 && sender.hasPermission("loveessential.god"))
      return List.of("on", "off");
    if (command.equals("gamemode")
        && args.length == 1
        && sender.hasPermission("loveessential.gamemode"))
      return List.of("survival", "creative", "adventure", "spectator");
    if (command.equals("speed") && args.length == 1 && sender.hasPermission("loveessential.speed"))
      return List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    if (command.equals("skin") && args.length == 1 && plugin.module("skin")) {
      var skins = new ArrayList<>(players(sender));
      skins.add("reset");
      return skins;
    }
    if (Set.of("spawn", "setspawn").contains(command) && args.length == 1 && plugin.module("spawn"))
      return gameplay.names(p, "spawn");
    if (command.equals("waystone")) return gameplay.waystones().suggestions(p, args);
    if (command.equals("portal")) return gameplay.portals().suggestions(p, args);
    if (Set.of("hologram", "portal").contains(command)
        && sender.hasPermission("loveessential.admin")
        && plugin.module(command)) {
      if (args.length == 1)
        return command.equals("hologram")
            ? List.of("create", "edit", "info", "addline", "move", "delete")
            : List.of("pos1", "create", "destination", "delete");
      if (args.length == 2 && !Set.of("create", "pos1").contains(args[0]))
        return gameplay.names(p, command);
    }
    return List.of();
  }

  public static String resolve(LoveEssentialPlugin plugin, String input) {
    for (var e : plugin.getDescription().getCommands().entrySet()) {
      if (e.getKey().equalsIgnoreCase(input)) return e.getKey();
      Object aliases = e.getValue().get("aliases");
      if (aliases instanceof List<?> list
          && list.stream().anyMatch(a -> a.toString().equalsIgnoreCase(input))) return e.getKey();
      if (aliases instanceof String s && s.equalsIgnoreCase(input)) return e.getKey();
    }
    return null;
  }

  private List<String> ids() {
    return npcs.all().stream().mapToInt(n -> npcs.number(n.id())).filter(id -> id > 0)
        .sorted().mapToObj(Integer::toString).toList();
  }

  private List<String> players(CommandSender sender) {
    return Bukkit.getOnlinePlayers().stream()
        .filter(p -> !(sender instanceof Player viewer) || viewer.canSee(p))
        .filter(love::authenticated)
        .map(Player::getName)
        .toList();
  }
}
