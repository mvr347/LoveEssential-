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
          "vanish",
          "afk",
          "skin",
          "sitride",
          "utilities",
          "nightskip",
          "portal",
          "hologram",
          "traces",
          "joinquit",
          "motd",
          "messages",
          "inventory",
          "profile",
          "nickname",
          "crafting",
          "starter",
          "worlds",
          "scenarios",
          "dialogs",
          "scoreboard",
          "revive",
          "rtp");
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
  private dev.lovelace.loveessential.module.Nicknames nicknames;
  private dev.lovelace.loveessential.module.PrivateMessages messages;
  private dev.lovelace.loveessential.module.InventoryInspector inventories;
  private dev.lovelace.loveessential.module.PlayerProfiles profiles;
  private dev.lovelace.loveessential.content.ContentLibrary content;
  private dev.lovelace.loveessential.content.ContentMenus contentMenus;
  private dev.lovelace.loveessential.content.StarterItems starter;
  private dev.lovelace.loveessential.module.ModulePolicy modulePolicy;

  public boolean environmentAllows(String module, org.bukkit.entity.Player p) {
    return modulePolicy == null ? module(module) : modulePolicy.environmentAllowed(module, p);
  }

  public boolean allows(String module, org.bukkit.entity.Player p) {
    return modulePolicy == null ? module(module) : modulePolicy.allowed(module, p);
  }

  public void requireAccess(String module, org.bukkit.entity.Player p) {
    if (modulePolicy != null) modulePolicy.require(module, p);
    else if (!module(module)) throw new IllegalArgumentException("errors.module-disabled");
  }

  public void requireDestination(String module, String world) {
    if (modulePolicy != null) modulePolicy.requireDestination(module, world);
  }

  private EssentialApi essentialApi;
  private dev.lovelace.loveessential.module.Revive revive;
  private dev.lovelace.loveessential.npc.LoveEssentialNpcProvider npcProvider;
  private dev.lovelace.loveessential.gui.MenuNavigation navigation;
  private Extensions extensions;
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

  public CompletableFuture<Void> editSection(
      String module, String path, Map<String, Object> value) {
    Object expected =
        dev.lovelace.loveessential.config.ModuleConfigs.snapshot(
            getConfig()
                .get(dev.lovelace.loveessential.config.ModuleConfigs.runtimePath(module, path)));
    return editSetting(module, path, expected, value);
  }

  /** Export during startup without restarting modules that are still loading. */
  public CompletableFuture<Void> exportSection(
      String module, String path, Object expected, Map<String, Object> value) {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return configFiles.update(module, path, expected, value);
              } catch (Exception error) {
                throw new CompletionException(error);
              }
            },
            io)
        .thenCompose(
            candidate ->
                main.<Void>call(
                    () -> {
                      settings = candidate;
                      return null;
                    }));
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
                    try {
                      setup();
                    } catch (RuntimeException e) {
                      // Without this the plugin stays enabled but half-built, answering "starting".
                      getLogger()
                          .log(java.util.logging.Level.SEVERE, "[loveessential.setup]", e);
                      getServer().getPluginManager().disablePlugin(this);
                    }
                  });
            });
  }

  private void setup() {
    dev.lovelace.loveessential.gui.MenuStyle.configure(
        new dev.lovelace.loveessential.gui.GuiSettings(this, lang));
    love = new LoveBridge();
    conditions = new ConditionEngine(love);
    actions = new ActionEngine(this, lang, love);
    new dev.lovelace.loveessential.integration.NeighbourMenus(this, lang, love, actions);
    npcs = new NpcService(this, main, store, lang, conditions, actions, love);
    dialogs = new DialogService(this, npcs, conditions, actions, lang, store, main, love);
    npcs.dialogs(dialogs);
    wizard = new NpcWizard(this, main, store, lang, npcs, love);
    gameplay = new Gameplay(this, main, store, lang, love, this::module, actions);
    modulePolicy = new dev.lovelace.loveessential.module.ModulePolicy(this, love, gameplay, lang);
    placeholders = new Placeholders(lang, love, gameplay::afk);
    gameplay.placeholders(placeholders);
    gameplay.holograms().setup(placeholders, actions);
    getServer()
        .getServicesManager()
        .register(HologramService.class, gameplay.holograms(), this, ServicePriority.Normal);
    nicknames = new dev.lovelace.loveessential.module.Nicknames(this, main, lang, store, love);
    placeholders.nicknames(nicknames::display, nicknames::tabNames);
    messages =
        new dev.lovelace.loveessential.module.PrivateMessages(
            this, main, lang, store, love, gameplay, nicknames);
    inventories = new dev.lovelace.loveessential.module.InventoryInspector(this, lang, love);
    revive = new dev.lovelace.loveessential.module.Revive(this, lang, love, actions, inventories);
    profiles =
        new dev.lovelace.loveessential.module.PlayerProfiles(
            this, lang, love, gameplay, store, placeholders, messages, inventories);
    placeholders.playerValues(
        id -> {
          var values = new HashMap<>(profiles.values(id));
          values.putAll(nicknames.values(id));
          values.putAll(revive.values(id));
          return Map.copyOf(values);
        });
    npcs.vanish(gameplay::vanished);
    tab = new TabModule(this, lang, placeholders, conditions);
    navigation = new dev.lovelace.loveessential.gui.MenuNavigation(this);
    dev.lovelace.loveessential.gui.MenuStyle.navigation(navigation);
    var api =
        new EssentialApi(
            npcs,
            conditions,
            actions,
            store,
            Math.clamp(getConfig().getLong("storage.profile-cache-mib", 16), 1, 256) * 1024 * 1024);
    essentialApi = api;
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
            this, main, lang, love, npcs, wizard, gameplay, nicknames);
    content = new dev.lovelace.loveessential.content.ContentLibrary(this, main, store, lang, love);
    starter =
        new dev.lovelace.loveessential.content.StarterItems(this, main, store, lang, love, content);
    contentMenus =
        new dev.lovelace.loveessential.content.ContentMenus(
            this, main, lang, love, content, starter, menus, actions);
    extensions =
        new Extensions(
            this, main, store, lang, love, conditions, actions, content, tab, placeholders);
    extensions.ui.beforeShow(
        p -> {
          menus.clearInput(p);
          dev.lovelace.loveessential.gui.MenuStyle.suspend(p);
        });
    menus.nativeDialogs(extensions.ui);
    profiles.nativeDialogs(extensions.ui);
    extensions.sidebar.moduleSettings(p -> menus.settings(p, "scoreboard", "", 0));
    menus.scoreboardEditor(extensions.sidebar::settings);
    extensions.tabSettings.advanced = p -> menus.settings(p, "tab", "", 0);
    dialogs.nativeDialogs(extensions.ui);
    gameplay.portals().ui(extensions.ui, content, p -> menus.modules(p));
    gameplay.motd(extensions.windows::motd);
    var commands =
        new Commands(
            this,
            main,
            lang,
            store,
            npcs,
            wizard,
            dialogs,
            gameplay,
            love,
            menus,
            messages,
            inventories,
            profiles,
            nicknames,
            contentMenus,
            extensions);
    commands.revive(revive);
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
        .thenCompose(
            v ->
                CompletableFuture.allOf(
                    npcs.load(),
                    gameplay.load(),
                    content.load(),
                    starter.load(),
                    extensions.load()))
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
                      try {
                        startServices();
                      } catch (RuntimeException e) {
                        ready = false;
                        getLogger()
                            .log(java.util.logging.Level.SEVERE, lang.text("logs.start-failed"), e);
                        getServer().getPluginManager().disablePlugin(this);
                      }
                    }));
  }

  /** Starts every service once the stored data is loaded; any failure disables the plugin. */
  private void startServices() {
    if (module("npc")) npcs.start();
    gameplay.start();
    nicknames.start();
    messages.start();
    inventories.start();
    revive.start();
    profiles.start();
    if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      expansion =
          new LoveExpansion(placeholders, lang, love, gameplay::afk, gameplay::vanished);
      expansion.register();
    }
    if (module("tab")) tab.start();
    content.refresh();
    ready = true;
    starter.start();
    extensions.start();
    // Other plugins pick these by name in their config (LoveCore.provider), next to Citizens/CMI.
    npcProvider = new dev.lovelace.loveessential.npc.LoveEssentialNpcProvider(this, main, npcs, gameplay.holograms());
    npcProvider.start();
    getServer()
        .getServicesManager()
        .register(
            dev.lovelace.lovecore.api.npc.NpcProvider.class,
            npcProvider,
            this,
            ServicePriority.Normal);
    getServer()
        .getServicesManager()
        .register(
            dev.lovelace.lovecore.api.presence.PresenceOracle.class,
            new dev.lovelace.loveessential.module.LoveEssentialPresence(gameplay, nicknames),
            this,
            ServicePriority.Normal);
    getServer()
        .getServicesManager()
        .register(
            dev.lovelace.lovecore.api.revive.DownedOracle.class, revive, this, ServicePriority.Normal);
    getLogger().info(lang.text("logs.ready"));
    Bukkit.getPluginManager().callEvent(new LoveEssentialReadyEvent(essentialApi));
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
                      if (name.equals("nickname")) nicknames.refresh();
                      if (name.equals("crafting")) content.refresh();
                      if (name.equals("scoreboard")) extensions.refresh();
                      if (name.equals("rtp") && !value) gameplay.randomTeleport().clearQueue();
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
                      gameplay.randomTeleport().clearQueue();
                      nicknames.refresh();
                      content.refresh();
                      extensions.refresh();
                      return null;
                    }))
        .thenCompose(v -> main.call(() -> extensions.sidebar.reloadCatalog()))
        .thenCompose(future -> future)
        .whenComplete((v, error) -> reloading.set(false));
  }

  public static void validateConfig(org.bukkit.configuration.ConfigurationSection config) {
    dev.lovelace.loveessential.scoreboard.Sidebar.rowSlots(config);
    for (String module : MODULES)
      dev.lovelace.loveessential.module.ModulePolicy.validate(config, module);
    var sidebarCatalog = config.getConfigurationSection("scoreboard.catalog");
    if (sidebarCatalog != null && !sidebarCatalog.getKeys(false).isEmpty())
      dev.lovelace.loveessential.scoreboard.SidebarConfig.read(sidebarCatalog);
    bounded(config, "storage.write-queue", 1, 65536);
    bounded(config, "storage.backup-keep", 1, 1000);
    bounded(config, "crafting.max-recipes", 1, 10000);
    bounded(config, "worlds.max-worlds", 1, 256);
    bounded(config, "scenarios.max-rules", 1, 10000);
    bounded(config, "scenarios.max-steps", 1, 128);
    bounded(config, "scenarios.max-starts-per-minute", 1, 10000);
    bounded(config, "dialogs.max-definitions", 1, 10000);
    bounded(config, "scoreboard.refresh-ticks", 2, 1200);
    bounded(config, "scoreboard.max-selected-lines", 1, 8);
    bounded(config, "scoreboard.overrides.max-seconds", 1, 86400);
    bounded(config, "scoreboard.overrides.per-player-limit", 1, 32);
    for (String mode : config.getStringList("scoreboard.disabled-gamemodes")) {
      try {
        org.bukkit.GameMode.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("errors.config");
      }
    }
    bounded(config, "nickname.min-length", 1, 32);
    bounded(config, "nickname.max-length", 1, 32);
    if (config.getInt("nickname.min-length", 3) > config.getInt("nickname.max-length", 24))
      throw new IllegalArgumentException("errors.config");
    String nickColor = config.getString("nickname.default-color", "");
    if (!nickColor.isBlank()) dev.lovelace.loveessential.module.Nicknames.color(nickColor);
    var gradients = config.getConfigurationSection("nickname.gradients");
    if (gradients != null)
      for (String key : gradients.getKeys(false)) {
        var colors = gradients.getStringList(key);
        if (colors.size() < 2 || colors.size() > 5)
          throw new IllegalArgumentException("errors.config");
        colors.forEach(dev.lovelace.loveessential.module.Nicknames::color);
      }
    bounded(config, "messages.max-length", 1, 4096);
    bounded(config, "messages.cooldown-ms", 0, 60000);
    bounded(config, "messages.max-ignored", 1, 10000);
    bounded(config, "inventory.refresh-ticks", 1, 200);
    for (String path :
        List.of("profile.privacy.default-visible", "profile.privacy.default-activity-visible"))
      if (config.contains(path) && !(config.get(path) instanceof Boolean))
        throw new IllegalArgumentException("errors.config");
    bounded(config, "profile.refresh-ticks", 20, 1200);
    bounded(config, "profile.persist-seconds", 60, 86400);
    bounded(config, "tab.refresh-ticks", 2, 1200);
    bounded(config, "npc.max-active", 0, 10000);
    bounded(config, "npc.tracking.update-ticks", 1, 20);
    bounded(config, "npc.tracking.max-turn-degrees", 1, 180);
    bounded(config, "npc.max-definitions", 1, 100000);
    bounded(config, "npc.max-route-points", 1, 10000);
    bounded(config, "npc.pathfinding.threads", 1, 4);
    bounded(config, "npc.pathfinding.radius", 4, 24);
    bounded(config, "npc.pathfinding.max-nodes", 1, 100000);
    bounded(config, "npc.pathfinding.plans-per-tick", 1, 100);
    bounded(config, "hologram.max-holograms", 1, 4096);
    bounded(config, "hologram.max-visible-per-player", 1, 256);
    bounded(config, "hologram.max-display-entities", 33, 50000);
    bounded(config, "traces.interval-ms", 50, 5000);
    decimal(config, "hologram.creation-height", .5, 10);
    decimal(config, "traces.stride", .2, 3);
    decimal(config, "traces.view-distance", 4, 64);
    decimal(config, "sitride.ride-range", 1, 8);
    if (config.contains("sitride.ride-denied-types")) {
      if (!(config.get("sitride.ride-denied-types") instanceof java.util.List<?> denied))
        throw new IllegalArgumentException("errors.config");
      for (Object type : denied)
        try {
          org.bukkit.entity.EntityType.valueOf(type.toString().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("errors.config", e);
        }
    }
    dev.lovelace.loveessential.module.RandomTeleport.validate(config);
    bounded(config, "portal.max-portals", 1, 1024);
    bounded(config, "portal.max-volume", 1, 262144);
    bounded(config, "portal.default-cooldown-seconds", 0, 86400);
    bounded(config, "portal.particles.refresh-ticks", 2, 1200);
    bounded(config, "portal.particles.per-player-budget", 1, 1024);
    bounded(config, "portal.particles.per-portal-budget", 1, 256);
    bounded(config, "portal.particles.creation-ticks", 0, 1200);
    decimal(config, "portal.particles.view-distance", 4, 96);
    if (config.contains("traces.color")
        && !config.getString("traces.color", "").matches("[0-9a-fA-F]{6}"))
      throw new IllegalArgumentException("errors.config");
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

  private static void decimal(
      org.bukkit.configuration.ConfigurationSection config, String path, double min, double max) {
    if (!config.contains(path)) return;
    Object value = config.get(path);
    if (!(value instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() < min
        || n.doubleValue() > max) throw new IllegalArgumentException("errors.config");
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
    MainThread.stop(this);
    getServer().getServicesManager().unregisterAll(this);
    if (expansion != null) expansion.unregister();
    if (npcProvider != null) npcProvider.close();
    if (revive != null) revive.close();
    if (extensions != null) extensions.close();
    if (contentMenus != null) contentMenus.close();
    if (starter != null) starter.close();
    if (content != null) content.close();
    if (menus != null) menus.close();
    if (wizard != null) wizard.close();
    if (dialogs != null) dialogs.close();
    if (npcs != null) npcs.close();
    if (tab != null) tab.close();
    if (profiles != null) profiles.close();
    if (inventories != null) inventories.close();
    if (messages != null) messages.close();
    if (nicknames != null) nicknames.close();
    if (gameplay != null) gameplay.close();
    if (navigation != null) navigation.close();
    dev.lovelace.loveessential.gui.MenuStyle.navigation(null);
    dev.lovelace.loveessential.gui.MenuStyle.configure(null);
    if (essentialApi != null) essentialApi.close();
    if (store != null) store.close();
    io.shutdown();
    MainThread.detach(this);
  }
}
