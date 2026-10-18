package dev.lovelace.loveessential.command;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.api.NpcDefinition;
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
          Map.entry("template", "edit"),
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
          Map.entry("delete", "delete"),
          Map.entry("enable", "publish"),
          Map.entry("disable", "publish"));
  private static final Map<String, String> ALIASES =
      Map.ofEntries(
          Map.entry("создать", "create"),
          Map.entry("удалить", "delete"),
          Map.entry("изменить", "edit"),
          Map.entry("настроить", "set"),
          Map.entry("путь", "path"),
          Map.entry("диалог", "dialog"),
          Map.entry("шаблон", "template"),
          Map.entry("расписание", "schedule"),
          Map.entry("скин", "skin"),
          Map.entry("копия", "clone"),
          Map.entry("экспорт", "export"),
          Map.entry("импорт", "import"),
          Map.entry("включить", "enable"),
          Map.entry("выключить", "disable"));

  public CommandSuggestions(
      LoveEssentialPlugin plugin,
      NpcService npcs,
      Gameplay gameplay,
      LoveBridge love,
      dev.lovelace.loveessential.module.PrivateMessages messages) {
    this.plugin = plugin;
    this.npcs = npcs;
    this.gameplay = gameplay;
    this.love = love;
    this.messages = messages;
  }

  public List<String> complete(CommandSender sender, String command, String[] args) {
    if (!plugin.ready()) return List.of();
    if (sender instanceof Player p && !love.authenticated(p)) return List.of();
    if (command.equals("loveessential")) {
      if (args.length <= 1) {
        var out = new ArrayList<>(List.of("menu", "help", "input"));
        if (NPC_PERMISSIONS.values().stream()
            .anyMatch(v -> sender.hasPermission("loveessential.npc." + v))) out.add("npc");
        if (sender.hasPermission("loveessential.admin"))
          out.addAll(List.of("module", "reload", "integrations", "backup", "tab"));
        for (String name : plugin.getDescription().getCommands().keySet())
          if (!name.equals("loveessential")
              && !name.equals("npc")
              && (!name.equals("invsee") || sender.hasPermission("loveessential.invsee")))
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
      if (op.equals("tab") && args.length == 2) return List.of("export");
      return List.of();
    }
    if (command.equals("npc")) {
      if (!plugin.module("npc")) return List.of();
      if (args.length <= 1)
        return NPC_PERMISSIONS.entrySet().stream()
            .filter(e -> sender.hasPermission("loveessential.npc." + e.getValue()))
            .map(Map.Entry::getKey)
            .toList();
      String op = args[0].toLowerCase(Locale.ROOT);
      op = ALIASES.getOrDefault(op, op);
      if (!sender.hasPermission("loveessential.npc." + NPC_PERMISSIONS.getOrDefault(op, "admin")))
        return List.of();
      if (args.length == 2
          && Set.of(
                  "edit",
                  "debug",
                  "delete",
                  "enable",
                  "disable",
                  "clone",
                  "export",
                  "schedule",
                  "skin")
              .contains(op)) return ids();
      if (op.equals("set")) {
        if (args.length == 2) return NpcOptions.fields();
        if (args.length == 3) {
          if (args[1].equals("follow-player") || args[1].equals("skin")) return players(sender);
          return NpcOptions.choices(args[1], plugin.getConfig());
        }
      }
      if (op.equals("delete") && args.length == 3 && npcs.get(args[1]).isPresent())
        return List.of("confirm");
      if (op.equals("template") && args.length == 2)
        return NpcOptions.choices("template", plugin.getConfig());
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
    if (Set.of("msg", "ignore", "profile", "invsee").contains(command)) {
      String module =
          command.equals("invsee")
              ? "inventory"
              : command.equals("profile") ? "profile" : "messages";
      String permission = command.equals("invsee") ? "invsee" : module;
      if (!plugin.module(module) || !p.hasPermission("loveessential." + permission))
        return List.of();
      if (command.equals("invsee") && args.length == 2)
        return p.hasPermission("loveessential.invsee.edit")
            ? List.of("view", "edit")
            : List.of("view");
      if (args.length == 1) {
        var out = new ArrayList<>(players(sender));
        if (command.equals("profile")) out.add("list");
        if (command.equals("ignore")) out.addAll(messages.ignoredNames(p));
        return out;
      }
    }
    if (Set.of("tpa", "tpahere").contains(command) && args.length == 1 && plugin.module("teleport"))
      return players(sender);
    if (command.equals("skin") && args.length == 1 && plugin.module("skin")) {
      var skins = new ArrayList<>(players(sender));
      skins.add("reset");
      return skins;
    }
    if (Set.of("home", "delhome", "sethome").contains(command)
        && args.length == 1
        && plugin.module("home")) return gameplay.names(p, "home");
    if (Set.of("spawn", "setspawn").contains(command) && args.length == 1 && plugin.module("spawn"))
      return gameplay.names(p, "spawn");
    if (Set.of("hologram", "portal").contains(command)
        && sender.hasPermission("loveessential.admin")
        && plugin.module(command)) {
      if (args.length == 1)
        return command.equals("hologram")
            ? List.of("create", "addline", "move", "delete")
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
    return npcs.all().stream().map(NpcDefinition::id).toList();
  }

  private List<String> players(CommandSender sender) {
    return Bukkit.getOnlinePlayers().stream()
        .filter(p -> !(sender instanceof Player viewer) || viewer.canSee(p))
        .filter(love::authenticated)
        .map(Player::getName)
        .toList();
  }
}
