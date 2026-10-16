package dev.lovelace.loveessential;

import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.command.Commands;
import dev.lovelace.loveessential.condition.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.npc.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.*;
import dev.lovelace.loveessential.wizard.NpcWizard;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;

public class LoveEssentialPlugin extends JavaPlugin {
  public static final Set<String> MODULES =
      Set.of(
          "npc",
          "tab",
          "home",
          "spawn",
          "teleport",
          "vanish",
          "afk",
          "skin",
          "sitride",
          "nightskip",
          "portal",
          "hologram",
          "traces",
          "joinquit",
          "motd");
  private final ExecutorService io =
      new ThreadPoolExecutor(
          8,
          8,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(256),
          Thread.ofVirtual().name("love-io-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());
  private final Map<String, Boolean> modules = new ConcurrentHashMap<>();
  private final Map<String, Long> moduleRevisions = new HashMap<>();
  private MainThread main;
  private volatile SqliteStore store;
  private volatile Lang lang;
  private NpcService npcs;
  private NpcWizard wizard;
  private DialogService dialogs;
  private Gameplay gameplay;
  private TabModule tab;
  private LoveExpansion expansion;
  private ConditionEngine conditions;
  private ActionEngine actions;
  private LoveBridge love;
  private Placeholders placeholders;
  private boolean ready;

  private record Loaded(SqliteStore store, Lang lang) {}

  @Override
  public void onEnable() {
    main = new MainThread(this);
    CompletableFuture.supplyAsync(
            () -> {
              try {
                saveDefaultConfig();
                validateConfig(getConfig());
                var language = new Lang(this);
                var database =
                    new SqliteStore(
                        getDataFolder().toPath().resolve("loveessential.db"),
                        getConfig().getInt("storage.write-queue", 256));
                store = database;
                lang = language;
                return new Loaded(database, language);
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            },
            io)
        .thenCompose(
            bundle ->
                bundle
                    .store()
                    .publications()
                    .thenCompose(
                        pending ->
                            CompletableFuture.runAsync(
                                    () -> {
                                      try {
                                        for (var e : pending.entrySet())
                                          bundle.lang().putAll(e.getValue());
                                      } catch (Exception e) {
                                        throw new CompletionException(e);
                                      }
                                    },
                                    io)
                                .thenCompose(
                                    v -> {
                                      CompletableFuture<Void> chain =
                                          CompletableFuture.completedFuture(null);
                                      for (String id : pending.keySet())
                                        chain =
                                            chain.thenCompose(
                                                ignored -> bundle.store().complete(id));
                                      return chain;
                                    }))
                    .thenApply(v -> bundle))
        .whenComplete(
            (bundle, error) -> {
              if (!isEnabled()) {
                if (bundle != null) bundle.store().close();
                return;
              }
              main.execute(
                  () -> {
                    if (error != null) {
                      getLogger()
                          .log(
                              java.util.logging.Level.SEVERE,
                              lang == null
                                  ? "[loveessential.startup]"
                                  : lang.text("logs.start-failed"),
                              error);
                      getServer().getPluginManager().disablePlugin(this);
                      return;
                    }
                    setup();
                  });
            });
  }

  private void setup() {
    love = new LoveBridge();
    conditions = new ConditionEngine(love);
    actions = new ActionEngine(this, lang, love);
    new dev.lovelace.loveessential.integration.NeighbourMenus(this, lang, love, actions);
    npcs = new NpcService(this, main, store, lang, conditions, actions, love);
    dialogs = new DialogService(this, npcs, conditions, actions, lang, store, main, love);
    npcs.dialogs(dialogs);
    wizard = new NpcWizard(this, main, store, lang, npcs, love);
    gameplay = new Gameplay(this, main, store, lang, love, this::module);
    placeholders = new Placeholders(lang, love, gameplay::afk);
    gameplay.placeholders(placeholders);
    npcs.vanish(gameplay::vanished);
    tab = new TabModule(this, lang, placeholders, conditions);
    var api = new EssentialApi(npcs, conditions, actions, store);
    conditions.register(
        "flag",
        (p, a) -> {
          var result = api.profile(p.getUniqueId(), a.get("key"));
          if (!result.isDone() || result.isCompletedExceptionally())
            return LoveEssentialService.Verdict.UNAVAILABLE;
          return result
                  .getNow(Optional.empty())
                  .map(v -> v.equals(a.getOrDefault("value", "true")))
                  .orElse(false)
              ? LoveEssentialService.Verdict.ALLOW
              : LoveEssentialService.Verdict.DENY;
        });
    getServer()
        .getServicesManager()
        .register(LoveEssentialService.class, api, this, ServicePriority.Normal);
    var commands = new Commands(this, main, lang, store, npcs, wizard, dialogs, gameplay, love);
    for (String name : getDescription().getCommands().keySet()) {
      var command = getCommand(name);
      if (command != null) {
        command.setExecutor(commands);
        command.setTabCompleter(commands);
      }
    }
    store
        .list("module", Boolean.class)
        .thenCompose(
            saved ->
                main.call(
                    () -> {
                      for (String name : MODULES)
                        modules.put(name, getConfig().getBoolean("modules." + name, true));
                      for (var d : saved) {
                        modules.put(d.id(), d.value());
                        moduleRevisions.put(d.id(), d.revision());
                      }
                      return null;
                    }))
        .thenCompose(v -> CompletableFuture.allOf(npcs.load(), gameplay.load()))
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      if (error != null) {
                        getLogger()
                            .log(
                                java.util.logging.Level.SEVERE,
                                lang.text("logs.start-failed"),
                                error);
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                      }
                      if (module("npc")) npcs.start();
                      gameplay.start();
                      if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                        expansion =
                            new LoveExpansion(
                                placeholders, lang, love, gameplay::afk, gameplay::vanished);
                        expansion.register();
                      }
                      if (module("tab")) tab.start();
                      ready = true;
                      getLogger().info(lang.text("logs.ready"));
                    }));
  }

  public boolean ready() {
    return ready;
  }

  public boolean module(String name) {
    return modules.getOrDefault(name, false);
  }

  public ExecutorService io() {
    return io;
  }

  public CompletableFuture<Void> module(String name, boolean value) {
    if (!MODULES.contains(name))
      return CompletableFuture.failedFuture(new IllegalArgumentException("errors.module"));
    long revision = moduleRevisions.getOrDefault(name, 0L);
    return store
        .save("module", name, revision, value, "admin")
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      moduleRevisions.put(name, rev);
                      modules.put(name, value);
                      if (name.equals("tab")) {
                        if (value) tab.start();
                        else tab.stop();
                      }
                      if (name.equals("npc")) npcs.enabled(value);
                      gameplay.applyVanish();
                      return null;
                    }));
  }

  public CompletableFuture<Void> reloadSettings() {
    return CompletableFuture.runAsync(
            () -> {
              try {
                var candidate = new org.bukkit.configuration.file.YamlConfiguration();
                candidate.load(getDataFolder().toPath().resolve("config.yml").toFile());
                validateConfig(candidate);
                lang.reload();
                reloadConfig();
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            },
            io)
        .thenCompose(
            v ->
                main.call(
                    () -> {
                      placeholders.clear();
                      tab.stop();
                      if (module("tab")) tab.start();
                      return null;
                    }));
  }

  public static void validateConfig(org.bukkit.configuration.file.FileConfiguration config) {
    String engine = config.getString("tab.engine", "native");
    if (!Set.of("native", "tab").contains(engine))
      throw new IllegalArgumentException("errors.config");
    if (config.getLong("tab.refresh-ticks", 10) < 2
        || config.getInt("npc.max-route-points", 128) < 1
        || config.getInt("storage.write-queue", 256) < 1)
      throw new IllegalArgumentException("errors.config");
  }

  @Override
  public void onDisable() {
    ready = false;
    getServer().getServicesManager().unregisterAll(this);
    if (expansion != null) expansion.unregister();
    if (wizard != null) wizard.close();
    if (dialogs != null) dialogs.close();
    if (npcs != null) npcs.close();
    if (tab != null) tab.close();
    if (gameplay != null) gameplay.close();
    if (store != null) store.close();
    io.shutdown();
  }
}
