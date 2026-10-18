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
  private dev.lovelace.loveessential.gui.ControlMenus menus;
  private DialogService dialogs;
  private Gameplay gameplay;
  private TabModule tab;
  private LoveExpansion expansion;
  private ConditionEngine conditions;
  private ActionEngine actions;
  private LoveBridge love;
  private Placeholders placeholders;
  private boolean ready;
  private dev.lovelace.loveessential.config.ModuleConfigs configFiles;
  private volatile org.bukkit.configuration.file.YamlConfiguration settings;
  private final java.util.concurrent.atomic.AtomicBoolean reloading =
      new java.util.concurrent.atomic.AtomicBoolean();

  @Override
  public org.bukkit.configuration.file.FileConfiguration getConfig() {
    return settings == null ? super.getConfig() : settings;
  }

  @Override
  public void saveConfig() {
    if (settings == null || configFiles == null) {
      super.saveConfig();
      return;
    }
    try {
      configFiles.save(settings);
    } catch (Exception e) {
      throw new IllegalStateException("errors.config", e);
    }
  }

  public CompletableFuture<Void> editSetting(
      String module, String path, Object expected, Object value) {
    return changeSettings(() -> configFiles.update(module, path, expected, value));
  }

  private record Loaded(SqliteStore store, Lang lang) {}

  @Override
  public void onEnable() {
    main = new MainThread(this);
    configFiles = new dev.lovelace.loveessential.config.ModuleConfigs(this);
    CompletableFuture.supplyAsync(
            () -> {
              try {
                saveDefaultConfig();
                settings = configFiles.load();
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
    menus =
        new dev.lovelace.loveessential.gui.ControlMenus(
            this, main, lang, love, npcs, wizard, gameplay);
    var commands =
        new Commands(this, main, lang, store, npcs, wizard, dialogs, gameplay, love, menus);
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
    return changeSettings(configFiles::load);
  }

  @FunctionalInterface
  private interface SettingsLoad {
    org.bukkit.configuration.file.YamlConfiguration load() throws Exception;
  }

  private CompletableFuture<Void> changeSettings(SettingsLoad loader) {
    if (!reloading.compareAndSet(false, true))
      return CompletableFuture.failedFuture(new IllegalArgumentException("wizard.busy"));
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                var candidate = loader.load();
                lang.reload();
                return candidate;
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            },
            io)
        .thenCompose(
            candidate ->
                main.<Void>call(
                    () -> {
                      settings = candidate;
                      return null;
                    }))
        .thenCompose(v -> store.list("module", Boolean.class))
        .thenCompose(
            saved ->
                main.<Void>call(
                    () -> {
                      boolean previousNpc = module("npc");
                      for (String name : MODULES)
                        modules.put(name, getConfig().getBoolean("modules." + name, true));
                      for (var d : saved) {
                        modules.put(d.id(), d.value());
                        moduleRevisions.put(d.id(), d.revision());
                      }
                      if (previousNpc != module("npc")) npcs.enabled(module("npc"));
                      placeholders.clear();
                      tab.stop();
                      if (module("tab")) tab.start();
                      gameplay.applyVanish();
                      return null;
                    }))
        .whenComplete((v, error) -> reloading.set(false));
  }

  public static void validateConfig(org.bukkit.configuration.ConfigurationSection config) {
    if (!Set.of("native", "tab").contains(config.getString("tab.engine", "native")))
      throw new IllegalArgumentException("errors.config");
    bounded(config, "storage.write-queue", 1, 65536);
    bounded(config, "tab.refresh-ticks", 2, 1200);
    bounded(config, "npc.max-active", 0, 10000);
    bounded(config, "npc.max-definitions", 1, 100000);
    bounded(config, "npc.max-route-points", 1, 10000);
    bounded(config, "npc.pathfinding.threads", 1, 4);
    bounded(config, "npc.pathfinding.radius", 4, 24);
    bounded(config, "npc.pathfinding.max-nodes", 1, 100000);
    bounded(config, "npc.pathfinding.plans-per-tick", 1, 100);
    bounded(config, "home.default-limit", 1, 1000);
    bounded(config, "afk.timeout-seconds", 1, 31536000);
    bounded(config, "afk.kick-after-seconds", 0, 31536000);
    bounded(config, "night-skip.max-multiplier", 1, 1000);
    bounded(config, "actions.max-economy-amount", 1, 1000000000);
    if (config.contains("npc.allowed-types")) {
      if (!(config.get("npc.allowed-types") instanceof java.util.List<?> types) || types.isEmpty())
        throw new IllegalArgumentException("errors.config");
      for (Object value : config.getList("npc.allowed-types")) {
        try {
          var type = org.bukkit.entity.EntityType.valueOf(value.toString());
          if (!type.isAlive() || !type.isSpawnable())
            throw new IllegalArgumentException("errors.config");
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("errors.config", e);
        }
      }
    }
    var bars = config.getConfigurationSection("tab.bossbars");
    if (bars != null)
      for (String id : bars.getKeys(false)) {
        try {
          net.kyori.adventure.bossbar.BossBar.Color.valueOf(
              bars.getString(id + ".color", "YELLOW"));
          net.kyori.adventure.bossbar.BossBar.Overlay.valueOf(
              bars.getString(id + ".overlay", "PROGRESS"));
          double progress = bars.getDouble(id + ".progress", 1);
          if (!Double.isFinite(progress) || progress < 0 || progress > 1)
            throw new IllegalArgumentException();
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("errors.config", e);
        }
      }
  }

  private static void bounded(
      org.bukkit.configuration.ConfigurationSection config, String path, double min, double max) {
    if (!config.contains(path)) return;
    Object value = config.get(path);
    if (!(value instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() < min
        || n.doubleValue() > max
        || (!path.equals("night-skip.max-multiplier")
            && n.doubleValue() != Math.rint(n.doubleValue())))
      throw new IllegalArgumentException("errors.config");
  }

  @Override
  public void onDisable() {
    ready = false;
    getServer().getServicesManager().unregisterAll(this);
    if (expansion != null) expansion.unregister();
    if (menus != null) menus.close();
    if (wizard != null) wizard.close();
    if (dialogs != null) dialogs.close();
    if (npcs != null) npcs.close();
    if (tab != null) tab.close();
    if (gameplay != null) gameplay.close();
    if (store != null) store.close();
    io.shutdown();
  }
}
