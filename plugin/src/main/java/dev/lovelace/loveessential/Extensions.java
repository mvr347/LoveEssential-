package dev.lovelace.loveessential;

import dev.lovelace.loveessential.condition.*;
import dev.lovelace.loveessential.content.ContentLibrary;
import dev.lovelace.loveessential.dialog.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.scenario.Scenarios;
import dev.lovelace.loveessential.scoreboard.Sidebar;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.*;
import dev.lovelace.loveessential.world.Worlds;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.entity.Player;

/** Lifecycle and command routing for optional world/content modules. */
public final class Extensions implements AutoCloseable {
  public static final Set<String> COMMANDS =
      Set.of("worlds", "scenario", "dialog", "motd", "scoreboard");
  private final LoveEssentialPlugin plugin;
  public final NativeDialogs ui;
  public final Worlds worlds;
  public final Scenarios scenarios;
  public final Windows windows;
  public final Sidebar sidebar;
  private org.bukkit.scheduler.BukkitTask task;

  public Extensions(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      ConditionEngine conditions,
      ActionEngine actions,
      ContentLibrary texts,
      TabModule tab,
      Placeholders placeholders) {
    this.plugin = plugin;
    ui = new NativeDialogs(plugin, main, lang, love);
    worlds = new Worlds(plugin, main, store, lang, love, ui);
    windows = new Windows(plugin, main, store, lang, love, ui, actions, texts, placeholders);
    scenarios = new Scenarios(plugin, main, store, lang, love, conditions, actions, ui, texts);
    sidebar = new Sidebar(plugin, main, store, lang, love, placeholders, tab, texts);
    sidebar.nativeDialogs(ui);
    actions.register(
        "world",
        (p, a) -> {
          worlds.teleport(p, a.get("id"));
          return true;
        });
    actions.register(
        "dialog",
        (p, a) -> {
          windows.open(p, a.get("id"));
          return true;
        });
  }

  public CompletableFuture<Void> load() {
    return CompletableFuture.allOf(worlds.load(), windows.load(), scenarios.load(), sidebar.load());
  }

  public void start() {
    scenarios.start();
    sidebar.start();
    task = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, scenarios::tick, 20, 20);
  }

  public void refresh() {
    windows.refreshMotd();
    sidebar.refresh();
  }

  public void command(Player p, String command, String[] args) {
    switch (command) {
      case "worlds" -> worlds.command(p, args);
      case "scenario" -> scenarios.command(p, args);
      case "dialog" -> windows.command(p, args);
      case "motd" -> {
        if (args.length != 0) throw new IllegalArgumentException("errors.input");
        windows.motdEditor(p);
      }
      case "scoreboard" -> {
        if (args.length == 1 && args[0].equals("edit")) sidebar.editCatalog(p, 0);
        else {
          if (args.length != 0) throw new IllegalArgumentException("errors.input");
          sidebar.open(p, "", 0);
        }
      }
      default -> throw new IllegalArgumentException("errors.input");
    }
  }

  public List<String> suggestions(Player p, String command, String[] args) {
    return switch (command) {
      case "worlds" -> worlds.suggestions(p, args);
      case "scenario" -> scenarios.suggestions(p, args);
      case "dialog" -> windows.suggestions(p, args);
      case "scoreboard" ->
          p.hasPermission("loveessential.admin") && args.length == 1 ? List.of("edit") : List.of();
      default -> List.of();
    };
  }

  @Override
  public void close() {
    if (task != null) task.cancel();
    scenarios.close();
    worlds.close();
    sidebar.close();
    ui.close();
  }
}
