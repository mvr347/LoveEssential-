package dev.lovelace.loveessential.command;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.npc.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.TabExport;
import dev.lovelace.loveessential.wizard.NpcWizard;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class Commands implements CommandExecutor, TabCompleter {
  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final Lang lang;
  private final SqliteStore store;
  private final NpcService npcs;
  private final NpcWizard wizard;
  private final DialogService dialogs;
  private final Gameplay gameplay;
  private final LoveBridge love;

  public Commands(
      LoveEssentialPlugin plugin,
      MainThread main,
      Lang lang,
      SqliteStore store,
      NpcService npcs,
      NpcWizard wizard,
      DialogService dialogs,
      Gameplay gameplay,
      LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.store = store;
    this.npcs = npcs;
    this.wizard = wizard;
    this.dialogs = dialogs;
    this.gameplay = gameplay;
    this.love = love;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      if (!plugin.ready()) {
        lang.send(sender, "errors.starting");
        return true;
      }
      String cmd = command.getName();
      if (cmd.equals("loveessential")) {
        if (args.length == 0) {
          lang.send(sender, "help.main");
          return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
          case "answer" -> {
            var p = player(sender);
            if (args.length != 4) throw new IllegalArgumentException("errors.input");
            dialogs.answer(p, args[1], args[2], args[3]);
          }
          case "npc" -> npc(player(sender), slice(args, 1));
          case "input" -> wizard.input(player(sender), String.join(" ", slice(args, 1)));
          case "reload", "перезагрузить" -> {
            admin(sender);
            complete(sender, plugin.reloadSettings(), "common.reloaded");
          }
          case "module", "модуль" -> {
            admin(sender);
            if (args.length < 2) {
              lang.send(
                  sender,
                  "modules.list",
                  Map.of(
                      "modules",
                      String.join(", ", LoveEssentialPlugin.MODULES.stream().sorted().toList())));
              break;
            }
            String name = args[1];
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
                "common.saved");
          }
          case "tab" -> {
            admin(sender);
            if (args.length > 1 && args[1].equals("export")) {
              if (!love.installed("TAB") || !love.installed("PlaceholderAPI"))
                throw new IllegalArgumentException("errors.tab-engine");
              var files = TabExport.snapshot(plugin, lang);
              var target = Bukkit.getPluginManager().getPlugin("TAB").getDataFolder().toPath();
              complete(
                  sender,
                  CompletableFuture.runAsync(
                          () -> {
                            try {
                              TabExport.write(target, files);
                            } catch (Exception e) {
                              throw new CompletionException(e);
                            }
                          },
                          plugin.io())
                      .thenCompose(
                          v ->
                              main.call(
                                  () -> {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tab reload");
                                    return null;
                                  })),
                  "tab.exported");
            } else lang.send(sender, "help.tab");
          }
          default -> lang.send(sender, "help.main");
        }
        return true;
      }
      Player p = player(sender);
      if (!love.authenticated(p)) throw new IllegalArgumentException("errors.access");
      if (cmd.equals("npc")) {
        npc(p, args);
        return true;
      }
      if (cmd.equals("hologram")) {
        if (args.length < 2) throw new IllegalArgumentException("errors.input");
        gameplay.hologram(p, args[0], args[1], String.join(" ", slice(args, 2)));
        return true;
      }
      if (cmd.equals("portal")) {
        if (args.length < 1) throw new IllegalArgumentException("errors.input");
        gameplay.portal(p, args[0], args.length > 1 ? args[1] : "");
        return true;
      }
      gameplay.command(p, cmd, args);
    } catch (RuntimeException e) {
      String key = e.getMessage();
      lang.send(sender, key != null && lang.contains(key) ? key : "errors.input");
    }
    return true;
  }

  private void npc(Player p, String[] args) {
    if (!plugin.module("npc")) throw new IllegalArgumentException("errors.module-disabled");
    if (!love.authenticated(p)) throw new IllegalArgumentException("errors.access");
    String op = args.length == 0 ? "wizard" : args[0].toLowerCase(Locale.ROOT);
    switch (op) {
      case "wizard", "мастер" -> wizard.open(p);
      case "create", "создать" -> {
        if (args.length > 1) wizard.create(p, args[1]);
        else wizard.open(p);
      }
      case "edit", "изменить" -> wizard.edit(p, args[1]);
      case "set", "настроить" -> wizard.set(p, args[1], String.join(" ", slice(args, 2)));
      case "template", "шаблон" -> wizard.template(p, args[1]);
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
            "common.saved");
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
      default -> lang.send(p, "help.npc");
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
    List<String> values = List.of();
    if (cmd.getName().equals("npc") && sender.hasPermission("loveessential.npc.view")) {
      if (args.length == 1)
        values =
            List.of(
                "wizard", "edit", "set", "path", "dialog", "page", "save", "publish", "preview",
                "list", "select", "debug", "delete");
      else if (args.length == 2
          && Set.of("edit", "debug", "delete", "enable", "disable").contains(args[0]))
        values = npcs.all().stream().map(NpcDefinition::id).toList();
      else if (args.length == 2 && args[0].equals("set"))
        values = new ArrayList<>(dev.lovelace.loveessential.npc.NpcValidator.BOOLEAN_OPTIONS);
      else if (args.length == 2 && args[0].equals("path"))
        values =
            List.of(
                "wizard", "add", "point", "mode", "remove", "move", "undo", "redo", "record",
                "tool", "show");
    } else if (cmd.getName().equals("loveessential") && args.length == 1)
      values =
          sender.hasPermission("loveessential.admin")
              ? List.of("npc", "module", "reload", "integrations", "backup", "tab")
              : List.of();
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return values.stream().filter(v -> v.startsWith(prefix)).toList();
  }
}
