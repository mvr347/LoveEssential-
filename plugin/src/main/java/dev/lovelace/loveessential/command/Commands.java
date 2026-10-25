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
  private final NpcCommandSelection npcSelection;

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
    this.npcSelection = new NpcCommandSelection(plugin, npcs);
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

  private dev.lovelace.loveessential.module.DisplayPreferences displayPrefs;

  public void display(dev.lovelace.loveessential.module.DisplayPreferences displayPrefs) {
    this.displayPrefs = displayPrefs;
  }

  private dev.lovelace.loveessential.module.Revive revive;

  public void revive(dev.lovelace.loveessential.module.Revive revive) {
    this.revive = revive;
  }

  private dev.lovelace.loveessential.tab.TabCommands tabCommands;

  public void tab(dev.lovelace.loveessential.tab.TabModule tab) {
    this.tabCommands =
        new dev.lovelace.loveessential.tab.TabCommands(
            plugin, lang, love, tab, extensions.tabSettings);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      if (!plugin.ready()) {
        lang.send(sender, "errors.starting");
        return true;
      }
      String cmd = command.getName();
      if (sender instanceof Player actor) dev.lovelace.loveessential.gui.MenuStyle.command(actor);
      boolean inputCommand =
          cmd.equals("loveessential")
              && args.length > 0
              && Set.of("cancel").contains(args[0].toLowerCase(Locale.ROOT));
      if (!inputCommand && sender instanceof Player p) {
        profiles.cancelPending(p);
        extensions.ui.cancel(p);
        menus.cancelInput(p);
        wizard.cancelInput(p);
      }
      if (cmd.equals("revive")) {
        revive.command(sender, args);
        return true;
      }
      if (cmd.equals("loveessential")) {
        if (args.length == 0) {
          if (sender instanceof Player p && p.hasPermission("loveessential.admin"))
            menus.direct(p, () -> menus.home(p));
          else if (sender instanceof Player p) lang.send(p, "help.player");
          else lang.send(sender, "help.overview");
          return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
          case "menu", "меню" -> {
            var p = player(sender);
            menus.direct(p, () -> menus.home(p));
          }
          case "help", "помощь" -> {
            lang.send(
                sender, sender.hasPermission("loveessential.admin") ? "help.admin" : "help.player");
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
            reload(sender);
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
              case "reload" -> reload(sender);
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
            var folder = plugin.getDataFolder().toPath().resolve("backups");
            complete(
                sender,
                store
                    .backup(folder.resolve("backup-" + System.currentTimeMillis() + ".db"))
                    .thenApply(
                        saved -> {
                          try {
                            SqliteStore.pruneBackups(
                                folder, plugin.getConfig().getInt("storage.backup-keep", 10));
                          } catch (java.io.IOException | RuntimeException e) {
                            plugin.getLogger().warning("Backup rotation failed: " + e);
                          }
                          return saved;
                        }),
                "chat.saved");
          }
          case "tab", "таб" -> {
            if (tabCommands == null) throw new IllegalArgumentException("errors.starting");
            tabCommands.run(sender, slice(args, 1));
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
      if (cmd.equals("rtp")) {
        gameplay.randomTeleport().command(p, args);
        return true;
      }
      if (cmd.equals("display")) {
        display(player(sender), args);
        return true;
      }
      if (cmd.equals("vanish")
          && args.length == 0
          && plugin.module("vanish")
          && p.hasPermission("loveessential.vanish.gui")) {
        menus.direct(p, () -> menus.vanishMenu(p, 0));
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
      case "edit", "изменить" -> wizard.edit(p, npcSelection.target(p, args.length > 1 ? args[1] : null).id());
      case "set", "настроить" -> wizard.set(p, args[1], String.join(" ", slice(args, 2)));
      case "here", "сюда" -> wizard.spawnHere(p);
      case "page", "шаг" -> wizard.page(p, Integer.parseInt(args[1]) - 1);
      case "save", "сохранить" -> wizard.save(p);
      case "discard", "отмена" -> wizard.discard(p);
      case "publish", "опубликовать" -> wizard.publish(p);
      case "preview", "просмотр" -> wizard.preview(p);
      case "path", "путь" -> {
        if (args.length == 1 || args[1].equals("wizard") || args[1].equals("мастер")) {
          if (args.length > 2) wizard.edit(p, npcSelection.target(p, args[2]).id(), 4);
          else if (wizard.editing(p)) wizard.page(p, 4);
          else wizard.edit(p, npcSelection.target(p, null).id(), 4);
        } else wizard.path(p, args[1], slice(args, 2));
      }
      case "dialog", "диалог" -> {
        if (args.length == 1 || args[1].equals("wizard") || args[1].equals("мастер")) {
          if (args.length > 2) wizard.edit(p, npcSelection.target(p, args[2]).id(), 5);
          else if (wizard.editing(p)) wizard.page(p, 5);
          else wizard.edit(p, npcSelection.target(p, null).id(), 5);
        } else
          wizard.dialog(
              p,
              args[1],
              joinTail(
                  args, 2, args[1].equals("answer") ? 3 : args[1].startsWith("answer-") ? 2 : 1));
      }
      case "clone", "копия", "копировать" -> {
        permission(p, "create");
        if (args.length > 3) throw new IllegalArgumentException("errors.input");
        var original = npcSelection.target(p, args.length > 1 ? args[1] : null);
        String newId = args.length > 2 ? args[2]
            : dev.lovelace.loveessential.wizard.NpcDraft.create(p.getUniqueId(), Position.of(p.getLocation())).npc().id();
        if (npcs.get(newId).isPresent()) throw new IllegalArgumentException("errors.conflict");
        wizard.imported(p, NpcBundle.export(original, lang).draft(newId, p.getUniqueId()));
      }
      case "export", "экспорт" -> {
        permission(p, "export");
        var n = npcSelection.target(p, args.length > 1 ? args[1] : null);
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
        if (args.length > 1 && args[1].equalsIgnoreCase("citizens")) {
          importCitizens(p, args);
          return;
        }
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
      case "schedule", "расписание" -> wizard.edit(p, npcSelection.target(p, args.length > 1 ? args[1] : null).id(), 6);
      case "skin", "скин" -> wizard.edit(p, npcSelection.target(p, args.length > 1 ? args[1] : null).id(), 1);
      case "list", "список" -> {
        permission(p, "view");
        menus.direct(p, () -> menus.npcList(p, 0));
      }
      case "debug", "отладка" -> {
        permission(p, "debug");
        lang.send(p, "npc.debug", Map.of("state", npcs.debug(npcSelection.target(p, args.length > 1 ? args[1] : null).id())));
      }
      case "sel", "select", "выбрать" -> {
        permission(p, "view");
        String id = null;
        if (args.length > 1) id = args[1];
        else {
          id = npcs.lookedAt(p, 8).orElse(null);
        }
        if (id == null) throw new IllegalArgumentException("npc.none-targeted");
        final String reference = id;
        var npc = args.length > 1 ? npcSelection.target(p, reference)
            : npcs.get(reference).filter(n -> n.id().equals(reference))
                .orElseThrow(() -> new IllegalArgumentException("npc.none-targeted"));
        npcSelection.select(p, npc);
        npcSummary(p, npc, "npc.selected");
      }
      case "info", "инфо" -> {
        permission(p, "view");
        npcSummary(p, npcSelection.target(p, args.length > 1 ? args[1] : null), "npc.info");
      }
      case "deselect", "unselect", "снять", "сбросить" -> {
        permission(p, "view");
        npcSelection.clear(p);
        lang.send(p, "npc.deselected");
      }
      case "rename", "переименовать" -> {
        permission(p, "edit");
        if (args.length < 2) throw new IllegalArgumentException("npc.rename-usage");
        var n = npcSelection.target(p, null);
        String text = String.join(" ", slice(args, 1)).strip();
        if (text.isEmpty() || text.length() > 256) throw new IllegalArgumentException("npc.rename-usage");
        // "&eБанкир" is read like "<yellow>Банкир"; the name is kept inline, so it shows at once.
        String named = dev.lovelace.loveessential.tab.TabSyntax.legacy(text);
        npcUpdated(p, npcs.save(n.withNameKey("@inline:" + named), n.revision(), p.getUniqueId().toString()));
      }
      case "delete", "удалить" -> {
        permission(p, "delete");
        if (args.length > 3) throw new IllegalArgumentException("errors.input");
        if (args.length > 1 && Set.of("cancel", "отмена").contains(args[1].toLowerCase(Locale.ROOT))) {
          npcSelection.cancel(p, args.length > 2 ? args[2] : null);
          lang.send(p, "npc.delete-cancelled");
        } else if (args.length > 1 && Set.of("confirm", "подтвердить").contains(args[1].toLowerCase(Locale.ROOT))) {
          var confirmation = npcSelection.confirm(p, args.length > 2 ? args[2] : null);
          complete(p, npcs.delete(confirmation.id(), confirmation.revision(), p.getUniqueId().toString()), "npc.deleted");
        } else {
          var n = npcSelection.target(p, args.length > 1 ? args[1] : null);
          if (args.length > 2 && Set.of("confirm", "подтвердить").contains(args[2].toLowerCase(Locale.ROOT))) {
            complete(p, npcs.delete(n.id(), n.revision(), p.getUniqueId().toString()), "npc.deleted");
          } else {
            var confirmation = npcSelection.request(p, n);
            npcDeletePrompt(p, n, confirmation.token());
          }
        }
      }
      case "enable", "disable", "включить", "выключить" -> {
        permission(p, "publish");
        var n = npcSelection.target(p, args.length > 1 ? args[1] : null);
        boolean value = Set.of("enable", "включить").contains(op);
        npcUpdated(
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
                p.getUniqueId().toString()));
      }
      default -> lang.send(p, "help.npc-commands");
    }
  }

  private Map<String, String> npcVariables(NpcDefinition npc) {
    return Map.of(
        "id", Integer.toString(npcs.number(npc.id())),
        "state", lang.text(npc.enabled() ? "npc.state-enabled" : "npc.state-disabled"));
  }

  private void npcUpdated(Player player, CompletableFuture<NpcDefinition> change) {
    change.whenComplete((saved, error) -> main.execute(() -> {
      if (!player.isOnline()) return;
      if (error != null) lang.error(player, error);
      else npcSummary(player, saved, "npc.info");
    }));
  }

  private void npcSummary(Player player, NpcDefinition npc, String title) {
    var variables = npcVariables(npc);
    player.sendMessage(lang.renderText(lang.text(title), variables, Map.of("name", lang.render(npc.nameKey()))));
    var buttons = net.kyori.adventure.text.Component.empty();
    String number = variables.get("id");
    if (player.hasPermission("loveessential.npc.edit")) {
      buttons = buttons.append(npcButton("npc.buttons.edit", "/npc edit " + number, false));
      if (npcSelection.selected(player, npc.id()))
        buttons = buttons.append(npcButton("npc.buttons.rename", "/npc rename ", true));
      buttons = buttons.append(npcButton("npc.buttons.skin", "/npc skin " + number, false));
    }
    if (player.hasPermission("loveessential.npc.path") && player.hasPermission("loveessential.npc.edit"))
      buttons = buttons.append(npcButton("npc.buttons.path", "/npc path wizard " + number, false));
    if (player.hasPermission("loveessential.npc.publish"))
      buttons = buttons.append(npcButton(npc.enabled() ? "npc.buttons.disable" : "npc.buttons.enable",
          "/npc " + (npc.enabled() ? "disable " : "enable ") + number, false));
    if (player.hasPermission("loveessential.npc.delete"))
      buttons = buttons.append(npcButton("npc.buttons.delete", "/npc delete " + number, false));
    // Rename works on the selection, so the summary offers selection when queried by explicit ID.
    buttons = buttons.append(npcButton(npcSelection.selected(player, npc.id()) ? "npc.buttons.deselect" : "npc.buttons.select",
        npcSelection.selected(player, npc.id()) ? "/npc deselect" : "/npc sel " + number, false));
    player.sendMessage(buttons);
  }

  private net.kyori.adventure.text.Component npcButton(String key, String command, boolean suggest) {
    return lang.render(key)
        .clickEvent(suggest
            ? net.kyori.adventure.text.event.ClickEvent.suggestCommand(command)
            : net.kyori.adventure.text.event.ClickEvent.runCommand(command))
        .hoverEvent(lang.render(key + "-hover"));
  }

  private void npcDeletePrompt(Player player, NpcDefinition npc, String token) {
    player.sendMessage(lang.renderText(lang.text("npc.confirm-delete-selected"), npcVariables(npc),
        Map.of("name", lang.render(npc.nameKey()))));
    player.sendMessage(npcButton("npc.buttons.confirm-delete", "/npc delete confirm " + token, false)
        .append(npcButton("npc.buttons.cancel-delete", "/npc delete cancel " + token, false)));
  }


  /**
   * {@code /npc import citizens [number|all]}: copies the NPCs of {@code plugins/Citizens/saves.yml}
   * into LoveEssential and points the config bindings of the other Love plugins at the new NPCs.
   */
  private void importCitizens(Player p, String[] args) {
    var provider = plugin.npcProvider();
    if (provider == null || !provider.bound()) throw new IllegalArgumentException("errors.access");
    var file = plugin.getDataFolder().toPath().toAbsolutePath().getParent().resolve("Citizens").resolve("saves.yml");
    String only = args.length > 2 && !args[2].equalsIgnoreCase("all") ? args[2] : null;
    CompletableFuture.supplyAsync(
            () -> {
              if (!java.nio.file.Files.isRegularFile(file)) throw new IllegalArgumentException("npc.import-nofile");
              return dev.lovelace.loveessential.npc.CitizensImport.parse(
                  org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile()));
            },
            plugin.io())
        .whenComplete(
            (result, error) ->
                main.execute(
                    () -> {
                      if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        lang.send(p, cause instanceof IllegalArgumentException ? cause.getMessage() : "errors.input");
                        return;
                      }
                      try {
                        runCitizensImport(p, provider, result, only);
                      } catch (RuntimeException e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Citizens import failed", e);
                        lang.send(p, "errors.input");
                      }
                    }));
  }

  private void runCitizensImport(
      Player p,
      dev.lovelace.loveessential.npc.LoveEssentialNpcProvider provider,
      dev.lovelace.loveessential.npc.CitizensImport.Result result,
      String only) {
    var known = new HashMap<Integer, String>();
    for (var handle : provider.all())
      handle.tag("citizens-id").ifPresent(id -> {
        try {
          known.put(Integer.parseInt(id), handle.key());
        } catch (NumberFormatException ignored) {
          // Not one of ours.
        }
      });
    var mapping = new LinkedHashMap<Integer, String>();
    int created = 0, existing = 0;
    var problems = new ArrayList<String>(result.skipped());
    var mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
    for (var e : result.entries()) {
      if (only != null && !only.equals(Integer.toString(e.id()))) continue;
      if (known.containsKey(e.id())) {
        mapping.put(e.id(), known.get(e.id()));
        existing++;
        continue;
      }
      var world = Bukkit.getWorld(e.world());
      if (world == null) {
        problems.add(e.id() + ": world " + e.world() + " is not loaded");
        continue;
      }
      org.bukkit.entity.EntityType type;
      try {
        type = org.bukkit.entity.EntityType.valueOf(e.type());
      } catch (IllegalArgumentException unknown) {
        problems.add(e.id() + ": unknown type " + e.type());
        continue;
      }
      var spec =
          dev.lovelace.lovecore.api.npc.NpcSpec.at(
                  mini.deserialize(dev.lovelace.loveessential.tab.TabSyntax.legacy(e.name())),
                  new org.bukkit.Location(world, e.x(), e.y(), e.z(), e.yaw(), e.pitch()))
              .withType(type)
              .withLookClose(e.lookClose())
              .withHologram(
                  e.hologram().stream()
                      .map(line -> mini.deserialize(dev.lovelace.loveessential.tab.TabSyntax.legacy(line)))
                      .toList())
              .withTags(Map.of("citizens-id", Integer.toString(e.id())));
      if (!e.skinValue().isBlank())
        spec = spec.withSkin(new dev.lovelace.lovecore.api.presence.SkinTexture(e.skinValue(), e.skinSignature()));
      else if (!e.skinName().isBlank()) spec = spec.withSkinName(e.skinName());
      mapping.put(e.id(), provider.create(spec).key());
      created++;
    }
    lang.send(
        p,
        "npc.import-done",
        Map.of(
            "created", Integer.toString(created),
            "existing", Integer.toString(existing),
            "skipped", Integer.toString(problems.size())));
    mapping.forEach(
        (old, key) -> lang.send(p, "npc.import-line", Map.of("old", Integer.toString(old), "new", key)));
    problems.forEach(problem -> lang.send(p, "npc.import-problem", Map.of("problem", problem)));
    if (mapping.isEmpty()) return;
    var table = new org.bukkit.configuration.file.YamlConfiguration();
    var tableFile = plugin.getDataFolder().toPath().resolve("citizens-import.yml").toFile();
    if (tableFile.isFile()) {
      try {
        table.load(tableFile);
      } catch (Exception unreadable) {
        // Start a fresh table; the old file is overwritten below.
      }
    }
    mapping.forEach((old, key) -> table.set(Integer.toString(old), key));
    try {
      table.save(tableFile);
    } catch (java.io.IOException failed) {
      plugin.getLogger().warning("Cannot write citizens-import.yml: " + failed.getMessage());
    }
    var changed = new TreeSet<String>();
    for (var target : dev.lovelace.loveessential.npc.CitizensRebind.TARGETS) {
      var other = Bukkit.getPluginManager().getPlugin(target.plugin());
      if (other == null || !other.isEnabled()) continue;
      Object current = other.getConfig().get(target.path());
      var replacement = dev.lovelace.loveessential.npc.CitizensRebind.replacement(current, mapping);
      if (replacement.isEmpty()) continue;
      other.getConfig().set(target.path(), replacement.get());
      other.saveConfig();
      changed.add(target.plugin());
      lang.send(
          p,
          "npc.import-rebound",
          Map.of("plugin", target.plugin(), "path", target.path(), "old", String.valueOf(current), "new", replacement.get()));
    }
    lang.send(p, "npc.import-manual", Map.of("reload", changed.isEmpty() ? "-" : String.join(", ", changed)));
  }

  /** {@code /le reload}: failures are logged in full and named in the chat instead of "could not save". */
  private void reload(CommandSender sender) {
    plugin
        .reloadSettings()
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      if (error == null) {
                        lang.send(sender, "common.reloaded");
                        return;
                      }
                      plugin.getLogger().log(java.util.logging.Level.WARNING, "Reload failed; the previous settings stay active", error);
                      lang.error(sender, error);
                    }));
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

  /**
   * {@code /display}: the menu, or {@code [own] <clan|suffix> <chat|tab|nametag> [on|off]}.
   * Changes the player's own signature for every viewer, including themselves.
   */
  private void display(Player p, String[] args) {
    if (!plugin.module("display") || !love.authenticated(p))
      throw new IllegalArgumentException("errors.permission");
    if (!displayPrefs.loaded(p.getUniqueId())) throw new IllegalArgumentException("errors.starting");
    if (args.length == 0) {
      menus.direct(p, () -> menus.displayMenu(p));
      return;
    }
    var choice = dev.lovelace.loveessential.module.DisplayPreferences.parse(args);
    boolean value =
        choice.value() != null
            ? choice.value()
            : !displayPrefs.shown(p.getUniqueId(), choice.surface(), choice.element(), choice.scope());
    displayPrefs.set(p.getUniqueId(), choice.surface(), choice.element(), choice.scope(), value);
    lang.send(p, value ? "display.changed-on" : "display.changed-off", displayVariables(choice));
  }

  private Map<String, String> displayVariables(dev.lovelace.loveessential.module.DisplayPreferences.Choice choice) {
    return Map.of(
        "scope", lang.text("display.scope-" + choice.scope().name().toLowerCase(Locale.ROOT)),
        "element", lang.text("display.element-" + choice.element().name().toLowerCase(Locale.ROOT)),
        "surface", lang.text("display.surface-" + choice.surface().name().toLowerCase(Locale.ROOT)));
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
                            .revive(revive)
                            .complete(sender, cmd.getName(), args);
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return candidates.stream()
        .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix))
        .distinct()
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }
}
