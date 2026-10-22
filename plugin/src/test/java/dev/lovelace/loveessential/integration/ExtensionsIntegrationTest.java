package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.auth.AuthOracle;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.content.ContentLibrary;
import dev.lovelace.loveessential.dialog.*;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.scenario.Scenarios;
import dev.lovelace.loveessential.scoreboard.ScoreboardModule;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.world.Worlds;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ExtensionsIntegrationTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  Extensions ext;
  SqliteStore store;
  Lang lang;
  final Map<UUID, NativeDialogs.Form> forms = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    await(plugin.module("tab", false));
    await(plugin.module("starter", false));
    ext = internal("extensions", Extensions.class);
    var timer = ScoreboardModule.class.getDeclaredField("task");
    timer.setAccessible(true);
    ((org.bukkit.scheduler.BukkitTask) timer.get(ext.scoreboard)).cancel();
    store = internal("store", SqliteStore.class);
    lang = internal("lang", Lang.class);
    ext.ui.presenter((p, f) -> forms.put(p.getUniqueId(), f));
  }

  @AfterEach
  void end() {
    MockBukkit.unmock();
  }

  <T> T internal(String name, Class<T> type) throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField(name);
    f.setAccessible(true);
    return type.cast(f.get(plugin));
  }

  void waitFor(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean(), "async operation unfinished");
  }

  <T> T await(CompletableFuture<T> f) throws Exception {
    waitFor(f::isDone);
    return f.get(1, TimeUnit.SECONDS);
  }

  PlayerMock admin() {
    var p = server.addPlayer("Builder");
    p.setOp(true);
    return p;
  }

  NativeDialogs.Form form(Player p) {
    return Objects.requireNonNull(forms.get(p.getUniqueId()));
  }

  void respond(PlayerMock p, String button, Map<String, String> values) {
    assertTrue(ext.ui.respond(p, form(p).token(), button, values));
  }

  String plain(Component c) {
    return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        .serialize(c);
  }

  // MockBukkit lacks only the modern formatting accessors: delegate all scoreboard state to its
  // real model.
  org.bukkit.scoreboard.Scoreboard paperBoard(org.bukkit.scoreboard.Scoreboard board) {
    var wrappers = new IdentityHashMap<Object, Object>();
    var formats = new IdentityHashMap<Object, Object>();
    var names = new IdentityHashMap<Object, Component>();
    class Adapter {
      Object wrap(Object target, Class<?> type) {
        return wrappers.computeIfAbsent(
            target,
            t ->
                java.lang.reflect.Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    (proxy, method, args) -> {
                      if (method.getName().equals("numberFormat")) {
                        if (args != null && args.length == 1) {
                          formats.put(t, args[0]);
                          return null;
                        }
                        return formats.get(t);
                      }
                      if (method.getName().equals("customName")) {
                        if (args != null && args.length == 1) {
                          names.put(t, (Component) args[0]);
                          return null;
                        }
                        return names.get(t);
                      }
                      try {
                        Object result = method.invoke(t, args);
                        if (result instanceof org.bukkit.scoreboard.Objective)
                          return wrap(result, org.bukkit.scoreboard.Objective.class);
                        if (result instanceof org.bukkit.scoreboard.Score)
                          return wrap(result, org.bukkit.scoreboard.Score.class);
                        return result;
                      } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                      }
                    }));
      }
    }
    return (org.bukkit.scoreboard.Scoreboard)
        new Adapter().wrap(board, org.bukkit.scoreboard.Scoreboard.class);
  }

  @Test
  void nativeResponsesAreBoundToPlayerTokenAndCurrentPermissions() {
    var p = admin();
    var hits = new AtomicInteger();
    ext.ui.show(
        p,
        "ui.confirm",
        "ui.empty",
        List.of(),
        List.of(ext.ui.button("yes", "gui.confirm", v -> hits.incrementAndGet())),
        who -> who.hasPermission("test.allow"));
    var old = form(p).token();
    var other = server.addPlayer("Other");
    assertFalse(ext.ui.respond(other, old, "yes", Map.of()));
    assertTrue(ext.ui.respond(p, old, "yes", Map.of()));
    assertFalse(ext.ui.respond(p, old, "yes", Map.of()));
    assertEquals(1, hits.get());
    ext.ui.show(
        p,
        "ui.confirm",
        "ui.empty",
        List.of(),
        List.of(ext.ui.button("yes", "gui.confirm", v -> hits.incrementAndGet())),
        who -> who.isOp());
    p.setOp(false);
    assertFalse(ext.ui.respond(p, form(p).token(), "yes", Map.of()));
    assertEquals(1, hits.get());
  }

  @Test
  void motdIsEditedAndPersistedThroughNativeDialog() throws Exception {
    var p = admin();
    assertTrue(server.dispatchCommand(p, "le motd"));
    respond(
        p, "save", Map.of("first", "<gold>Новое королевство", "second", "<green>Добро пожаловать"));
    waitFor(() -> plain(ext.windows.motd()).contains("Новое королевство"));
    assertTrue(plain(ext.windows.motd()).contains("Добро пожаловать"));
    var d = await(store.get("motd", "default", Windows.Motd.class)).orElseThrow();
    assertTrue(lang.text(d.value().firstKey()).contains("Новое королевство"));
    assertTrue(
        java.nio.file.Files.readString(
                plugin.getDataFolder().toPath().resolve("modules/motd/motd.yml"))
            .contains("Добро пожаловать"));
    var visitor = server.addPlayer("Visitor");
    assertThrows(IllegalArgumentException.class, () -> ext.windows.motdEditor(visitor));
  }

  @Test
  void customDialogCreationEditingAndActionAreNativeAndRevisionChecked() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le dialog create greeting");
    waitFor(
        () -> ext.windows.definition("greeting").isPresent() && forms.containsKey(p.getUniqueId()));
    respond(
        p,
        "save",
        Map.of(
            "title",
            "<gold>Приветствие",
            "body",
            "<gray>Здравствуйте, <player>",
            "permission",
            ""));
    waitFor(() -> ext.windows.definition("greeting").orElseThrow().revision() > 1);
    var d = ext.windows.definition("greeting").orElseThrow();
    String key = "windows-test.message";
    await(
        ext.windows.save(
            new Windows.Definition(
                d.id(),
                d.value().titleKey(),
                d.value().bodyKey(),
                "",
                List.of(new Windows.Input("world", "ui.close", "world", 48)),
                List.of(
                    new Windows.Answer(
                        "hello", "ui.close", "", new Action("message", Map.of("key", key))))),
            d.revision(),
            "test",
            Map.of(key, "<green>Ответ принят")));
    ext.windows.open(p, "greeting");
    assertTrue(plain(form(p).body()).contains(p.getName()));
    respond(p, "hello", Map.of("world", "world"));
    boolean found = false;
    Component msg;
    while ((msg = p.nextComponentMessage()) != null)
      if (plain(msg).contains("Ответ принят")) found = true;
    assertTrue(found);
    ext.windows.open(p, "greeting");
    var old = form(p).token();
    var current = ext.windows.definition("greeting").orElseThrow();
    await(ext.windows.save(current.value(), current.revision(), "test", Map.of()));
    assertFalse(ext.ui.respond(p, old, "hello", Map.of()));
  }

  @Test
  void firstJoinRuleWaitsForAuthAndNeverRepeats() throws Exception {
    var auth = MockBukkit.createMockPlugin("LoveAuth");
    var logged = new AtomicBoolean();
    server
        .getServicesManager()
        .register(AuthOracle.class, id -> logged.get(), auth, ServicePriority.Normal);
    var hits = new AtomicInteger();
    var actions = internal("actions", dev.lovelace.loveessential.condition.ActionEngine.class);
    actions.register(
        "test:first",
        (p, a) -> {
          hits.incrementAndGet();
          return true;
        });
    await(
        ext.scenarios.save(
            new Scenarios.Rule(
                "welcome",
                true,
                Scenarios.Trigger.FIRST_JOIN,
                true,
                "",
                Condition.always(),
                List.of(
                    new Scenarios.Step(0, Condition.always(), new Action("test:first", Map.of())))),
            0,
            "test",
            Map.of()));
    class NewPlayer extends PlayerMock {
      boolean saved;

      NewPlayer() {
        super(ExtensionsIntegrationTest.this.server, "Newbie");
      }

      @Override
      public boolean hasPlayedBefore() {
        return saved;
      }
    }
    var p = new NewPlayer();
    server.addPlayer(p);
    waitFor(
        () -> {
          try {
            return store
                .get("scenario-run", p.getUniqueId() + "/welcome", Scenarios.Run.class)
                .get(1, TimeUnit.SECONDS)
                .isPresent();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    assertEquals(0, hits.get());
    p.saved = true;
    logged.set(true);
    waitFor(() -> hits.get() == 1);
    waitFor(
        () -> {
          try {
            return store
                .get("scenario-run", p.getUniqueId() + "/welcome", Scenarios.Run.class)
                .get()
                .orElseThrow()
                .value()
                .state()
                .equals("DONE");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    ext.scenarios.trigger(p, Scenarios.Trigger.FIRST_JOIN);
    server.getScheduler().performTicks(30);
    assertEquals(1, hits.get());
  }

  @Test
  void scenarioStepsKeepOrderAndPersistCursorBeforeAction() throws Exception {
    var p = admin();
    var order = new ArrayList<String>();
    var actions = internal("actions", dev.lovelace.loveessential.condition.ActionEngine.class);
    actions.register(
        "test:order",
        (player, a) -> {
          order.add(a.get("value"));
          return true;
        });
    await(
        ext.scenarios.save(
            new Scenarios.Rule(
                "sequence",
                true,
                Scenarios.Trigger.MANUAL,
                false,
                "",
                Condition.always(),
                List.of(
                    new Scenarios.Step(
                        2, Condition.always(), new Action("test:order", Map.of("value", "first"))),
                    new Scenarios.Step(
                        2,
                        Condition.always(),
                        new Action("test:order", Map.of("value", "second"))))),
            0,
            "test",
            Map.of()));
    ext.scenarios.command(p, new String[] {"run", "sequence"});
    waitFor(() -> order.size() == 2);
    assertEquals(List.of("first", "second"), order);
    var d =
        await(store.get("scenario-run", p.getUniqueId() + "/sequence", Scenarios.Run.class))
            .orElseThrow();
    assertEquals(2, d.value().cursor());
  }

  @Test
  void editedScenarioDoesNotExecuteStaleScheduledAction() throws Exception {
    var p = admin();
    var hit = new AtomicInteger();
    internal("actions", dev.lovelace.loveessential.condition.ActionEngine.class)
        .register(
            "test:stale",
            (player, a) -> {
              hit.incrementAndGet();
              return true;
            });
    var r =
        new Scenarios.Rule(
            "stale",
            true,
            Scenarios.Trigger.MANUAL,
            true,
            "",
            Condition.always(),
            List.of(
                new Scenarios.Step(100, Condition.always(), new Action("test:stale", Map.of()))));
    await(ext.scenarios.save(r, 0, "test", Map.of()));
    ext.scenarios.command(p, new String[] {"run", "stale"});
    waitFor(
        () -> {
          try {
            return store
                .get("scenario-run", p.getUniqueId() + "/stale", Scenarios.Run.class)
                .get()
                .isPresent();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    await(
        ext.scenarios.save(
            r, ext.scenarios.rule("stale").orElseThrow().revision(), "edit", Map.of()));
    server.getScheduler().performTicks(120);
    assertEquals(0, hit.get());
  }

  @Test
  void worldDialogPersistsSettingsAndTeleportChecksAccess() throws Exception {
    var p = admin();
    var def =
        new Worlds.Definition(
            "restricted",
            "NORMAL",
            "FLAT",
            42,
            true,
            false,
            "world.enter",
            null,
            Worlds.Options.defaults());
    await(ext.worlds.save(def, 0, "test"));
    ext.worlds.edit(p, "restricted");
    respond(
        p,
        "save",
        Map.of(
            "permission",
            "world.changed",
            "autoload",
            "true",
            "pvp",
            "false",
            "difficulty",
            "HARD",
            "mode",
            "ADVENTURE",
            "enforce",
            "true"));
    waitFor(() -> ext.worlds.definition("restricted").orElseThrow().revision() > 1);
    var saved =
        await(store.get("world", "restricted", Worlds.Definition.class)).orElseThrow().value();
    assertFalse(saved.options().pvp());
    assertEquals("HARD", saved.options().difficulty());
    assertEquals("world.changed", saved.permission());
    var w = server.addSimpleWorld("restricted");
    var visitor = server.addPlayer("Visitor");
    var e = new PlayerTeleportEvent(visitor, visitor.getLocation(), w.getSpawnLocation());
    server.getPluginManager().callEvent(e);
    assertTrue(e.isCancelled());
  }

  @Test
  void leavingAnEnforcingWorldRestoresThePreviousGameMode() throws Exception {
    var options = Worlds.Options.defaults();
    var adventure =
        new Worlds.Options(
            options.pvp(),
            options.difficulty(),
            "ADVENTURE",
            true,
            options.time(),
            options.freezeTime(),
            options.weather(),
            options.borderSize(),
            options.borderX(),
            options.borderZ(),
            options.rules(),
            options.respawnWorld());
    await(
        ext.worlds.save(
            new Worlds.Definition("lobby", "NORMAL", "NORMAL", 0, true, false, "", null, adventure),
            0,
            "test"));
    var lobby = server.addSimpleWorld("lobby");
    var player = server.addPlayer("Traveler");
    player.setGameMode(GameMode.SURVIVAL);
    player.teleport(lobby.getSpawnLocation());
    server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, server.getWorld("world")));
    assertEquals(GameMode.ADVENTURE, player.getGameMode());
    player.teleport(server.getWorld("world").getSpawnLocation());
    server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, lobby));
    assertEquals(GameMode.SURVIVAL, player.getGameMode());
  }

  @Test
  void previousGameModeSurvivesARelogInsideTheEnforcingWorld() throws Exception {
    var options = Worlds.Options.defaults();
    var adventure =
        new Worlds.Options(
            options.pvp(),
            options.difficulty(),
            "ADVENTURE",
            true,
            options.time(),
            options.freezeTime(),
            options.weather(),
            options.borderSize(),
            options.borderX(),
            options.borderZ(),
            options.rules(),
            options.respawnWorld());
    await(
        ext.worlds.save(
            new Worlds.Definition("lobby", "NORMAL", "NORMAL", 0, true, false, "", null, adventure),
            0,
            "test"));
    var lobby = server.addSimpleWorld("lobby");
    var player = server.addPlayer("Traveler");
    var key = "loveessential.worlds.mode-before";
    player.setGameMode(GameMode.SURVIVAL);
    player.teleport(lobby.getSpawnLocation());
    server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, server.getWorld("world")));
    assertEquals(GameMode.ADVENTURE, player.getGameMode());
    waitFor(() -> store.profile(player.getUniqueId(), key).join().orElse("").equals("SURVIVAL"));

    // Relog while still inside: the in-memory record is gone, the saved one brings it back.
    server
        .getPluginManager()
        .callEvent(
            new org.bukkit.event.player.PlayerQuitEvent(
                player,
                Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
    ext.worlds.restore(player);
    server.getScheduler().performTicks(5);
    Thread.sleep(200);
    server.getScheduler().performTicks(5);
    assertEquals(GameMode.ADVENTURE, player.getGameMode(), "still inside the lobby");

    player.teleport(server.getWorld("world").getSpawnLocation());
    server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, lobby));
    assertEquals(GameMode.SURVIVAL, player.getGameMode());
    waitFor(() -> store.profile(player.getUniqueId(), key).join().orElse("x").isEmpty());
  }

  @Test
  void worldNamesAndSettingsRejectInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> Worlds.name("../outside"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ext.worlds.create("world", "NORMAL", "NORMAL", 0, true, false, "test"));
    assertThrows(IllegalArgumentException.class, () -> Worlds.name("world/nested"));
    var o = Worlds.Options.defaults();
    var bad =
        new Worlds.Options(
            o.pvp(),
            o.difficulty(),
            o.gameMode(),
            false,
            0,
            false,
            "CLEAR",
            Double.NaN,
            0,
            0,
            Map.of(),
            "");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Worlds.validate(
                new Worlds.Definition("valid", "NORMAL", "NORMAL", 0, true, true, "", null, bad)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Scenarios.validate(
                new Scenarios.Rule(
                    "bad",
                    true,
                    Scenarios.Trigger.JOIN,
                    true,
                    "",
                    Condition.always(),
                    List.of(
                        new Scenarios.Step(
                            -1, Condition.always(), new Action("message", Map.of())))),
                32));
  }

  @Test
  void tabCommandsWriteGroupPropertiesIntoModuleConfig() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le tab group staff tabprefix <red>[Страж]");
    waitFor(() -> plugin.getConfig().contains("tab.groups.staff.tabprefix"));
    waitFor(() -> !settingsBusy());
    var file = plugin.getDataFolder().toPath().resolve("modules/tab/tab.yml");
    var saved = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
    assertEquals("<red>[Страж]", saved.getString("groups.staff.tabprefix"));
    server.dispatchCommand(p, "le tab player Builder tagprefix <gold>[Строитель]");
    waitFor(() -> plugin.getConfig().contains("tab.users.Builder.tagprefix"));
    waitFor(() -> !settingsBusy());
    // Unknown properties and invalid flag values are rejected before anything is written.
    server.dispatchCommand(p, "le tab group staff visibility sometimes");
    server.dispatchCommand(p, "le tab group staff nonsense value");
    assertFalse(settingsBusy());
    assertFalse(plugin.getConfig().contains("tab.groups.staff.visibility"));
    assertFalse(plugin.getConfig().contains("tab.groups.staff.nonsense"));
    server.dispatchCommand(p, "le tab group staff tabprefix");
    waitFor(() -> !plugin.getConfig().contains("tab.groups.staff.tabprefix"));
    waitFor(() -> !settingsBusy());
    assertEquals("<gold>[Строитель]", plugin.getConfig().getString("tab.users.Builder.tagprefix"));
  }

  /** Whether a settings write is still running; the plugin serializes writes behind this guard. */
  boolean settingsBusy() {
    try {
      var f = LoveEssentialPlugin.class.getDeclaredField("reloading");
      f.setAccessible(true);
      return ((java.util.concurrent.atomic.AtomicBoolean) f.get(plugin)).get();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void commandPortalIsPersistentEdgeTriggeredAndHonorsModuleWorldRules() throws Exception {
    var p = admin();
    var portals = internal("gameplay", dev.lovelace.loveessential.module.Gameplay.class).portals();
    plugin
        .getConfig()
        .set(
            "portal.particles.per-player-budget",
            0); // geometry is tested separately; MockBukkit cannot display particles
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    server
        .getCommandMap()
        .register(
            "test",
            new org.bukkit.command.Command("portalprobe") {
              public boolean execute(
                  org.bukkit.command.CommandSender sender, String label, String[] args) {
                assertSame(p, sender);
                assertArrayEquals(new String[] {p.getName()}, args);
                calls.incrementAndGet();
                return true;
              }
            });
    plugin.getConfig().set("portal.allowed-player-commands", List.of("portalprobe"));
    var at = p.getLocation();
    var first = Position.of(at.clone().subtract(1, 0, 1));
    var second = Position.of(at.clone().add(1, 2, 1));
    var definition =
        new dev.lovelace.loveessential.portal.PortalDefinition(
            "portal_probe",
            "common.done",
            first,
            second,
            null,
            true,
            "",
            List.of("SURVIVAL"),
            0,
            0,
            true,
            false,
            "DUST",
            "B68CFF",
            true,
            .5,
            List.of(
                new Action(
                    "portal-command",
                    Map.of("sender", "player", "value", "portalprobe {player}"))));
    await(portals.save(definition, 0, p.getUniqueId().toString()));
    assertTrue(
        await(
                store.get(
                    "portal",
                    "portal_probe",
                    dev.lovelace.loveessential.portal.PortalDefinition.class))
            .isPresent());
    var outside = at.clone().add(20, 0, 0);
    plugin.getConfig().set("portal.access.denied-worlds", List.of("world"));
    portals.move(new org.bukkit.event.player.PlayerMoveEvent(p, outside, at));
    server.getScheduler().performTicks(4);
    assertEquals(0, calls.get());
    portals.move(new org.bukkit.event.player.PlayerMoveEvent(p, at, outside));
    plugin.getConfig().set("portal.access.denied-worlds", List.of());
    portals.move(new org.bukkit.event.player.PlayerMoveEvent(p, outside, at));
    server.getScheduler().performTicks(4);
    assertEquals(1, calls.get());
    portals.move(new org.bukkit.event.player.PlayerMoveEvent(p, at, at.clone().add(.1, 0, 0)));
    server.getScheduler().performTicks(4);
    assertEquals(1, calls.get(), "moving inside the same portal must not repeat its action");
    plugin.getConfig().set("portal.allowed-player-commands", List.of());
    assertThrows(IllegalArgumentException.class, () -> portals.validate(definition, true));
  }


  @Test
  void motdPreviewKeepsDraftAndDoesNotPublishIt() throws Exception {
    var p = admin();
    String before = plain(ext.windows.motd());
    ext.windows.motdEditor(p);
    respond(p, "preview", Map.of("first", "<gold>Черновик", "second", "<gray>Не опубликован"));
    assertEquals(before, plain(ext.windows.motd()));
    respond(p, "back", Map.of());
    assertEquals("<gold>Черновик", form(p).fields().getFirst().initial());
    respond(p, "save", Map.of());
    waitFor(() -> plain(ext.windows.motd()).contains("Черновик"));
  }

  @Test
  void scenarioFailureStopsFollowingActions() throws Exception {
    var p = admin();
    var hits = new AtomicInteger();
    var actions = internal("actions", dev.lovelace.loveessential.condition.ActionEngine.class);
    actions.register("test:fail", (player, args) -> false);
    actions.register(
        "test:after",
        (player, args) -> {
          hits.incrementAndGet();
          return true;
        });
    await(
        ext.scenarios.save(
            new Scenarios.Rule(
                "fails",
                true,
                Scenarios.Trigger.MANUAL,
                true,
                "",
                Condition.always(),
                List.of(
                    new Scenarios.Step(0, Condition.always(), new Action("test:fail", Map.of())),
                    new Scenarios.Step(0, Condition.always(), new Action("test:after", Map.of())))),
            0,
            "test",
            Map.of()));
    ext.scenarios.command(p, new String[] {"run", "fails"});
    waitFor(
        () -> {
          try {
            return store
                .get("scenario-run", p.getUniqueId() + "/fails", Scenarios.Run.class)
                .get()
                .map(d -> d.value().state().equals("FAILED"))
                .orElse(false);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    assertEquals(0, hits.get());
    assertEquals(
        1,
        await(store.get("scenario-run", p.getUniqueId() + "/fails", Scenarios.Run.class))
            .orElseThrow()
            .value()
            .cursor());
  }

  public static class LegacyTweaks extends org.bukkit.plugin.java.JavaPlugin {
    private org.bukkit.scheduler.BukkitTask scoreboardTask;
    org.bukkit.scheduler.BukkitTask other;
    final Display display = new Display();
    final LegacyConfig config = new LegacyConfig();
    int unrelated;

    public static class Display {
      int updates, clears;

      public void updateAll() {
        updates++;
      }

      public void removeAll() {
        clears++;
      }
    }

    public static class LegacyConfig {
      public int getUpdateInterval() {
        return 2;
      }
    }

    public Display getScoreboardDisplayManager() {
      return display;
    }

    public LegacyConfig getScoreboardConfig() {
      return config;
    }

    public void restart() {
      scoreboardTask = getServer().getScheduler().runTaskTimer(this, display::updateAll, 1, 2);
    }

    @Override
    public void onEnable() {
      restart();
      other = getServer().getScheduler().runTaskTimer(this, () -> unrelated++, 1, 2);
    }

    @Override
    public void onDisable() {
      if (scoreboardTask != null) scoreboardTask.cancel();
      if (other != null) other.cancel();
    }
  }

  @Test
  void scoreboardLegacyTaskOwnershipSurvivesTweaksReloadAndRestores() throws Exception {
    var tweaks =
        MockBukkit.loadWith(
            LegacyTweaks.class,
            new org.bukkit.plugin.PluginDescriptionFile(
                "LoveTweaks", "1", LegacyTweaks.class.getName()));
    var old = tweaks.scoreboardTask;
    var bridge = new dev.lovelace.loveessential.scoreboard.TweaksScoreboardBridge(plugin);
    try {
      assertTrue(bridge.take((sender, command, label, args) -> true));
      assertTrue(old.isCancelled());
      assertFalse(tweaks.other.isCancelled());
      server.getScheduler().performTicks(3);
      assertEquals(0, tweaks.display.updates);
      assertTrue(tweaks.unrelated > 0);
      tweaks.restart();
      var restarted = tweaks.scoreboardTask;
      assertTrue(bridge.poll());
      assertTrue(restarted.isCancelled());
    } finally {
      bridge.close();
    }
    assertNotNull(tweaks.scoreboardTask);
    server.getScheduler().performTicks(3);
    assertTrue(tweaks.display.updates > 0);
  }

  @Test
  void scenarioResetRequiresConfirmationAndClearsOnlyOwnJournal() throws Exception {
    var p = admin();
    await(
        ext.scenarios.save(
            new Scenarios.Rule(
                "resettable",
                true,
                Scenarios.Trigger.MANUAL,
                true,
                "",
                Condition.always(),
                List.of()),
            0,
            "test",
            Map.of()));
    await(
        store.save(
            "scenario-run",
            p.getUniqueId() + "/resettable",
            0,
            new Scenarios.Run(1, 0, 0, "DONE"),
            "test"));
    String other = UUID.randomUUID() + "/resettable";
    await(store.save("scenario-run", other, 0, new Scenarios.Run(1, 0, 0, "DONE"), "test"));
    ext.scenarios.command(p, new String[] {"reset", "resettable"});
    assertTrue(
        await(store.get("scenario-run", p.getUniqueId() + "/resettable", Scenarios.Run.class))
            .isPresent());
    respond(p, "yes", Map.of());
    waitFor(
        () -> {
          try {
            return store
                .get("scenario-run", p.getUniqueId() + "/resettable", Scenarios.Run.class)
                .get()
                .isEmpty();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    assertTrue(await(store.get("scenario-run", other, Scenarios.Run.class)).isPresent());
  }

  @Test
  void newCommandsHaveRootRoutingAndGuideIsNotRegistered() {
    var p = admin();
    var names = plugin.getCommand("loveessential").tabComplete(p, "le", new String[] {""});
    assertTrue(names.containsAll(List.of("worlds", "scenario", "dialog", "motd", "scoreboard")));
    assertNull(plugin.getCommand("guide"));
    assertTrue(
        plugin
            .getCommand("loveessential")
            .tabComplete(p, "le", new String[] {"worlds", ""})
            .containsAll(List.of("create", "import", "tp", "unload")));
  }

  @Test
  void prefixReadsAreLiteralAndDoNotMixPlayerIds() throws Exception {
    await(store.save("prefix", "player_1/a", 0, true, "test"));
    await(store.save("prefix", "player_11/a", 0, true, "test"));
    await(store.save("prefix", "playerX1/a", 0, true, "test"));
    assertEquals(
        List.of("player_1/a"),
        await(store.listPrefix("prefix", "player_1/", Boolean.class)).stream()
            .map(SqliteStore.Document::id)
            .toList());
  }

}
