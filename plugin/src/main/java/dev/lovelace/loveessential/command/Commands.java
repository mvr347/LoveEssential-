package dev.lovelace.loveessential.command;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.npc.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.wizard.NpcWizard;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class Commands implements CommandExecutor, TabCompleter {
  private final LoveEssentialPlugin plugin;
  private final Extensions extensions;
  private final MainThread main;
  private final Lang lang;
  private final SqliteStore store;
  private final NpcService npcs;
  private final NpcWizard wizard;
  private final DialogService dialogs;
  private final Gameplay gameplay;
  private final dev.lovelace.loveessential.module.Nicknames nicknames;
  private final dev.lovelace.loveessential.module.PrivateMessages messages;
  private final dev.lovelace.loveessential.module.InventoryInspector inventories;
  private final dev.lovelace.loveessential.module.PlayerProfiles profiles;
  private final LoveBridge love;
  private final dev.lovelace.loveessential.gui.ControlMenus menus;

  private final dev.lovelace.loveessential.content.ContentMenus contentMenus;

  public Commands(
      LoveEssentialPlugin plugin,
      MainThread main,
      Lang lang,
      SqliteStore store,
      NpcService npcs,
      NpcWizard wizard,
      DialogService dialogs,
      Gameplay gameplay,
      LoveBridge love,
      dev.lovelace.loveessential.gui.ControlMenus menus,
      dev.lovelace.loveessential.module.PrivateMessages messages,
      dev.lovelace.loveessential.module.InventoryInspector inventories,
      dev.lovelace.loveessential.module.PlayerProfiles profiles,
      dev.lovelace.loveessential.module.Nicknames nicknames,
      dev.lovelace.loveessential.content.ContentMenus contentMenus,
      Extensions extensions) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.store = store;
    this.npcs = npcs;
    this.wizard = wizard;
    this.dialogs = dialogs;
    this.gameplay = gameplay;
    this.love = love;
    this.menus = menus;
    this.messages = messages;
    this.inventories = inventories;
    this.profiles = profiles;
    this.nicknames = nicknames;
    this.contentMenus = contentMenus;
    this.extensions = extensions;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      if (!plugin.ready()) {
        lang.send(sender, "errors.starting");
        return true;
      }
      String cmd = command.getName();
      boolean inputCommand =
          cmd.equals("loveessential")
              && args.length > 0
              && Set.of("cancel").contains(args[0].toLowerCase(Locale.ROOT));
      if (!inputCommand && sender instanceof Player p) {
        extensions.ui.cancel(p);
        menus.cancelInput(p);
        wizard.cancelInput(p);
      }
      if (cmd.equals("loveessential")) {
        if (args.length == 0) {
          if (sender instanceof Player p) menus.direct(p, () -> menus.home(p));
          else lang.send(sender, "help.overview");
          return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
          case "menu", "меню" -> {
            var p = player(sender);
            menus.direct(p, () -> menus.home(p));
          }
          case "help", "помощь" -> {
            if (sender instanceof Player p) menus.direct(p, () -> menus.commands(p, 0));
            else lang.send(sender, "help.overview");
          }
          case "answer" -> {
            var p = player(sender);
            if (args.length != 4) throw new IllegalArgumentException("errors.input");
            dialogs.answer(p, args[1], args[2], args[3]);
          }
          case "npc" -> npc(player(sender), slice(args, 1));
          case "cancel" -> {
            var p = player(sender);
            if (args.length != 2
                || !menus.cancelPrompt(p, args[1]) && !wizard.cancelPrompt(p, args[1]))
              lang.send(p, "input.stale-cancel");
          }
          case "reload", "перезагрузить" -> {
            admin(sender);
            complete(sender, plugin.reloadSettings(), "common.reloaded");
          }
          case "module", "модуль" -> {
            admin(sender);
            if (args.length < 2) {
              if (sender instanceof Player p) {
                menus.direct(p, () -> menus.modules(p));
                break;
              }
              lang.send(
                  sender,
                  "modules.list",
                  Map.of(
                      "modules",
                      String.join(", ", LoveEssentialPlugin.MODULES.stream().sorted().toList())));
              break;
            }
            String name = args[1];
            if (!LoveEssentialPlugin.MODULES.contains(name))
              throw new IllegalArgumentException("errors.module");
            if (sender instanceof Player p && (args.length < 3 || args[2].equals("settings"))) {
              menus.direct(p, () -> menus.settings(p, name, "", 0));
              break;
            }
            if (args.length < 3 || args[2].equals("info")) {
              lang.send(
                  sender,
                  "modules.state",
                  Map.of("module", name, "state", Boolean.toString(plugin.module(name))));
              break;
            }
            switch (args[2]) {
              case "enable" -> complete(sender, plugin.module(name, true), "common.done");
              case "disable" -> complete(sender, plugin.module(name, false), "common.done");
              case "reload" -> complete(sender, plugin.reloadSettings(), "common.reloaded");
              default -> throw new IllegalArgumentException("errors.input");
            }
          }
          case "integrations", "интеграции" -> {
            admin(sender);
            love.status()
                .forEach(
                    (name, state) ->
                        lang.send(
                            sender, "integrations.entry", Map.of("name", name, "state", state)));
          }
          case "backup" -> {
            admin(sender);
            complete(
                sender,
                store.backup(
                    plugin
                        .getDataFolder()
                        .toPath()
                        .resolve("backups/backup-" + System.currentTimeMillis() + ".db")),
                "chat.saved");
          }
          case "tab", "таб" -> {
            admin(sender);
            if (args.length != 1) throw new IllegalArgumentException("errors.input");
            if (sender instanceof Player p) menus.direct(p, () -> menus.settings(p, "tab", "", 0));
            else lang.send(sender, "help.tab-settings");
          }
          default -> {
            String target = CommandSuggestions.resolve(plugin, args[0]);
            if (target != null && !target.equals("loveessential"))
              return onCommand(
                  sender,
                  Objects.requireNonNull(plugin.getCommand(target)),
                  target,
                  slice(args, 1));
            lang.send(sender, "help.overview");
          }
        }
        return true;
      }
      Player p = player(sender);
      if (!love.authenticated(p)) throw new IllegalArgumentException("errors.access");
      if (Extensions.COMMANDS.contains(cmd)) {
        extensions.command(p, cmd, args);
        return true;
      }
      if (Set.of("recipes", "starter").contains(cmd)) {
        contentMenus.command(p, cmd, args);
        return true;
      }
      if (Set.of("msg", "reply", "ignore", "msgtoggle").contains(cmd)) {
        if (cmd.equals("msg") && args.length == 0) profiles.players(p, "msg", 0);
        else if (cmd.equals("ignore") && args.length == 0) profiles.preferences(p, 0);
        else messages.command(p, cmd, args);
        return true;
      }
      if (cmd.equals("nick")) {
        plugin.requireAccess("nickname", p);
        if (args.length == 0) menus.direct(p, () -> menus.nickname(p));
        else {
          Player target = p;
          String[] options = args;
          if (args[0].equalsIgnoreCase("player")) {
            if (!p.hasPermission("loveessential.nick.others"))
              throw new IllegalArgumentException("errors.permission");
            if (args.length < 3) throw new IllegalArgumentException("nick.usage");
            target = messages.target(p, args[1]);
            options = slice(args, 2);
          }
          complete(p, nicknames.change(p, target, options), "nick.saved");
        }
        return true;
      }
      if (cmd.equals("report")) {
        if (args.length != 1) throw new IllegalArgumentException("profile-reports.usage");
        profiles.reportCommand(p, args[0]);
        return true;
      }
      if (cmd.equals("reports")) {
        profiles.reportsCommand(p, args);
        return true;
      }
      if (cmd.equals("profile")) {
        profiles.command(p, args);
        return true;
      }
      if (cmd.equals("invsee")) {
        if (args.length == 0) profiles.players(p, "invsee", 0);
        else {
          if (args.length > 2
              || args.length == 2
                  && !Set.of("edit", "view").contains(args[1].toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("inventory.usage");
          inventories.open(
              p, messages.target(p, args[0]), args.length == 2 && args[1].equalsIgnoreCase("edit"));
        }
        return true;
      }
      if (cmd.equals("npc")) {
        npc(p, args);
        return true;
      }
      if (cmd.equals("skin") && args.length == 1 && args[0].equalsIgnoreCase("reset")) {
        gameplay.command(p, "skin", new String[] {p.getName()});
        return true;
      }
      if (cmd.equals("hologram") && args.length == 0) {
        menus.direct(p, () -> menus.places(p, cmd, 0));
        return true;
      }
      if (cmd.equals("hologram")) {
        menus.hologramCommand(p, args);
        return true;
      }
      if (cmd.equals("portal")) {
        gameplay.portals().command(p, args);
        return true;
      }
      gameplay.command(p, cmd, args);
    } catch (dev.lovelace.loveessential.module.ModulePolicy.Denied e) {
      lang.send(sender, e.getMessage(), e.variables());
    } catch (RuntimeException e) {
      String key = e.getMessage();
      lang.send(sender, key != null && lang.contains(key) ? key : "errors.input");
    }
    return true;
  }

  private void npc(Player p, String[] args) {
    if (!plugin.module("npc")) throw new IllegalArgumentException("errors.module-disabled");
    if (!love.authenticated(p)) throw new IllegalArgumentException("errors.access");
    if (args.length == 0) {
      menus.direct(p, () -> menus.npcList(p, 0));
      return;
    }
    String op = args[0].toLowerCase(Locale.ROOT);
    switch (op) {
      case "menu", "меню" -> menus.direct(p, () -> menus.npcList(p, 0));
      case "help", "помощь" -> lang.send(p, "help.npc-commands");
      case "wizard", "мастер" -> wizard.open(p);
      case "create", "создать" -> {
        if (args.length > 1) wizard.create(p, args[1]);
        else wizard.open(p);
      }
      case "edit", "изменить" -> wizard.edit(p, args[1]);
      case "set", "настроить" -> wizard.set(p, args[1], String.join(" ", slice(args, 2)));
      case "here", "сюда" -> wizard.spawnHere(p);
      case "page", "шаг" -> wizard.page(p, Integer.parseInt(args[1]) - 1);
      case "save", "сохранить" -> wizard.save(p);
      case "discard", "отмена" -> wizard.discard(p);
      case "publish", "опубликовать" -> wizard.publish(p);
      case "preview", "просмотр" -> wizard.preview(p);
      case "path", "путь" -> {
        if (args.length == 1 || args[1].equals("wizard") || args[1].equals("мастер")) {
          if (args.length > 2) wizard.edit(p, args[2], 4);
          else wizard.page(p, 4);
        } else wizard.path(p, args[1], slice(args, 2));
      }
      case "dialog", "диалог" -> {
        if (args.length == 1 || args[1].equals("wizard") || args[1].equals("мастер")) {
          if (args.length > 2) wizard.edit(p, args[2], 5);
          else wizard.page(p, 5);
        } else
          wizard.dialog(
              p,
              args[1],
              joinTail(
                  args, 2, args[1].equals("answer") ? 3 : args[1].startsWith("answer-") ? 2 : 1));
      }
      case "clone", "копия" -> {
        permission(p, "create");
        var original =
            npcs.get(args[1]).orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
        if (npcs.get(args[2]).isPresent()) throw new IllegalArgumentException("errors.conflict");
        wizard.imported(p, NpcBundle.export(original, lang).draft(args[2], p.getUniqueId()));
      }
      case "export", "экспорт" -> {
        permission(p, "export");
        var n =
            npcs.get(args[1]).orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
        String json =
            new com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(NpcBundle.export(n, lang));
        var path = plugin.getDataFolder().toPath().resolve("exports").resolve(n.id() + ".json");
        complete(
            p,
            CompletableFuture.runAsync(
                () -> {
                  try {
                    java.nio.file.Files.createDirectories(path.getParent());
                    java.nio.file.Files.writeString(path, json);
                  } catch (Exception e) {
                    throw new CompletionException(e);
                  }
                },
                plugin.io()),
            "chat.saved");
      }
      case "import", "импорт" -> {
        permission(p, "import");
        String source = args[1], newId = args[2];
        if (!source.matches("[a-z0-9_-]{1,48}"))
          throw new IllegalArgumentException("validation.id");
        var path = plugin.getDataFolder().toPath().resolve("exports").resolve(source + ".json");
        CompletableFuture.supplyAsync(
                () -> {
                  try {
                    if (java.nio.file.Files.size(path) > 2_000_000)
                      throw new IllegalArgumentException("errors.input");
                    return new com.google.gson.Gson()
                        .fromJson(java.nio.file.Files.readString(path), NpcBundle.class);
                  } catch (Exception e) {
                    throw new CompletionException(e);
                  }
                },
                plugin.io())
            .whenComplete(
                (bundle, error) ->
                    main.execute(
                        () -> {
                          try {
                            if (error != null) {
                              lang.send(p, "errors.input");
                              return;
                            }
                            wizard.imported(p, bundle.draft(newId, p.getUniqueId()));
                          } catch (RuntimeException e) {
                            lang.send(p, "errors.input");
                          }
                        }));
      }
      case "schedule", "расписание" -> wizard.edit(p, args[1], 6);
      case "skin", "скин" -> wizard.edit(p, args[1], 1);
      case "list", "список" -> {
        permission(p, "view");
        menus.direct(p, () -> menus.npcList(p, 0));
        for (var n : npcs.all())
          lang.send(
              p,
              "npc.entry",
              Map.of(
                  "id", n.id(), "type", n.entityType(), "revision", Long.toString(n.revision())));
      }
      case "debug", "отладка" -> {
        permission(p, "debug");
        lang.send(p, "npc.debug", Map.of("state", npcs.debug(args[1])));
      }
      case "select", "выбрать" -> {
        permission(p, "view");
        var target = p.getTargetEntity(8);
        lang.send(
            p,
            "npc.selected",
            Map.of("id", target == null ? "" : npcs.identify(target).orElse("")));
      }
      case "delete", "удалить" -> {
        permission(p, "delete");
        var n =
            npcs.get(args[1]).orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
        if (args.length < 3 || !args[2].equals("confirm")) {
          lang.send(p, "npc.confirm-delete", Map.of("id", n.id()));
          break;
        }
        complete(p, npcs.delete(n.id(), n.revision(), p.getUniqueId().toString()), "common.done");
      }
      case "enable", "disable", "включить", "выключить" -> {
        permission(p, "publish");
        var n =
            npcs.get(args[1]).orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
        boolean value = Set.of("enable", "включить").contains(op);
        complete(
            p,
            npcs.save(
                new NpcDefinition(
                    n.id(),
                    n.revision(),
                    n.owner(),
                    n.nameKey(),
                    n.entityType(),
                    n.spawn(),
                    n.settings(),
                    n.visibility(),
                    n.access(),
                    n.route(),
                    n.dialog(),
                    n.actions(),
                    value),
                n.revision(),
                p.getUniqueId().toString()),
            "common.done");
      }
      default -> lang.send(p, "help.npc-commands");
    }
  }

  private void complete(CommandSender sender, CompletableFuture<?> future, String success) {
    future.whenComplete(
        (v, error) ->
            main.execute(
                () ->
                    lang.send(
                        sender,
                        error == null
                            ? success
                            : root(error) instanceof SqliteStore.Conflict
                                ? "errors.conflict"
                                : "errors.storage")));
  }

  private static Throwable root(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    return e;
  }

  private static Player player(CommandSender s) {
    if (!(s instanceof Player p)) throw new IllegalArgumentException("errors.player-only");
    return p;
  }

  private static void admin(CommandSender s) {
    if (!s.hasPermission("loveessential.admin"))
      throw new IllegalArgumentException("errors.permission");
  }

  private static void permission(Player p, String action) {
    if (!p.hasPermission("loveessential.npc." + action))
      throw new IllegalArgumentException("errors.permission");
  }

  private static String[] slice(String[] a, int from) {
    return Arrays.copyOfRange(a, Math.min(from, a.length), a.length);
  }

  private static String[] joinTail(String[] a, int from, int fixed) {
    var tail = slice(a, from);
    if (tail.length <= fixed) return tail;
    var out = Arrays.copyOf(tail, fixed + 1);
    out[fixed] = String.join(" ", Arrays.copyOfRange(tail, fixed, tail.length));
    return out;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command cmd, String alias, String[] args) {
    var candidates =
        sender instanceof Player p && Extensions.COMMANDS.contains(cmd.getName())
            ? extensions.suggestions(p, cmd.getName(), args)
            : cmd.getName().equals("loveessential")
                    && args.length > 1
                    && sender instanceof Player p
                    && Extensions.COMMANDS.contains(
                        java.util.Objects.toString(CommandSuggestions.resolve(plugin, args[0]), ""))
                ? extensions.suggestions(
                    p, CommandSuggestions.resolve(plugin, args[0]), slice(args, 1))
                : sender instanceof Player p && Set.of("recipes", "starter").contains(cmd.getName())
                    ? contentMenus.suggestions(p, cmd.getName(), args)
                    : cmd.getName().equals("loveessential")
                            && args.length > 1
                            && sender instanceof Player p
                            && Set.of("recipes", "starter")
                                .contains(
                                    java.util.Objects.toString(
                                        CommandSuggestions.resolve(plugin, args[0]), ""))
                        ? contentMenus.suggestions(
                            p, CommandSuggestions.resolve(plugin, args[0]), slice(args, 1))
                        : new CommandSuggestions(plugin, npcs, gameplay, love, messages, profiles)
                            .complete(sender, cmd.getName(), args);
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return candidates.stream()
        .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix))
        .distinct()
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }
}
