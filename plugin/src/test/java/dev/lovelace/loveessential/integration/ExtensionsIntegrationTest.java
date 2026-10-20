package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.auth.AuthOracle;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.content.ContentLibrary;
import dev.lovelace.loveessential.dialog.*;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.scenario.Scenarios;
import dev.lovelace.loveessential.scoreboard.Sidebar;
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
    var timer = Sidebar.class.getDeclaredField("task");
    timer.setAccessible(true);
    ((org.bukkit.scheduler.BukkitTask) timer.get(ext.sidebar)).cancel();
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
        java.nio.file.Files.readString(plugin.getDataFolder().toPath().resolve("lang.yml"))
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
  void scoreboardKeepsOtherTeamsAndSavesPlayerToggle() throws Exception {
    var p = admin();
    waitFor(() -> ext.sidebar.state(p.getUniqueId()) != null);
    var board = paperBoard(Bukkit.getScoreboardManager().getNewScoreboard());
    var team = board.registerNewTeam("existing_tab");
    team.addEntry(p.getName());
    ext.sidebar.render(p, board);
    assertNotNull(board.getObjective("le_sidebar"));
    assertSame(team, board.getTeam("existing_tab"));
    server.dispatchCommand(p, "le scoreboard");
    var event =
        new InventoryClickEvent(
            p.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            1,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    server.getScheduler().performOneTick();
    assertTrue(event.isCancelled());
    waitFor(() -> !ext.sidebar.state(p.getUniqueId()).value().enabled());
    assertFalse(
        await(store.get("scoreboard-player", p.getUniqueId().toString(), Sidebar.State.class))
            .orElseThrow()
            .value()
            .enabled());
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
  void scoreboardWorldRulesGamemodesPermissionsAndForcedApiAreEnforced() throws Exception {
    var p = admin();
    waitFor(() -> ext.sidebar.state(p.getUniqueId()) != null);
    assertTrue(ext.sidebar.eligible(p));
    plugin.getConfig().set("scoreboard.disabled-worlds", List.of("WORLD"));
    assertFalse(ext.sidebar.eligible(p));
    var service =
        Bukkit.getServicesManager().load(dev.lovelace.loveessential.api.ScoreboardService.class);
    assertNotNull(service);
    var owner = MockBukkit.createMockPlugin("LoveArena");
    var layout =
        new dev.lovelace.loveessential.api.ScoreboardService.Board(
            Component.text("Arena"), List.of(Component.text("Round 1")));
    var lease = service.show(owner, p.getUniqueId(), 10, Duration.ofMinutes(1), true, layout);
    assertTrue(ext.sidebar.lines(p).isEmpty());
    plugin.getConfig().set("scoreboard.disabled-worlds", List.of());
    assertEquals(layout.lines(), ext.sidebar.lines(p));
    plugin.getConfig().set("scoreboard.enabled-worlds", List.of("other"));
    assertFalse(ext.sidebar.eligible(p));
    plugin.getConfig().set("scoreboard.enabled-worlds", List.of("WORLD"));
    assertTrue(ext.sidebar.eligible(p));
    plugin.getConfig().set("scoreboard.disabled-gamemodes", List.of("SURVIVAL"));
    assertFalse(ext.sidebar.eligible(p));
    plugin.getConfig().set("scoreboard.disabled-gamemodes", List.of());
    plugin.getConfig().set("scoreboard.display-permission", "test.sidebar");
    p.addAttachment(plugin, "test.sidebar", false);
    assertFalse(ext.sidebar.eligible(p));
    p.addAttachment(plugin, "test.sidebar", true);
    assertTrue(ext.sidebar.eligible(p));
    var board = paperBoard(Bukkit.getScoreboardManager().getNewScoreboard());
    ext.sidebar.render(p, board);
    assertEquals("Arena", plain(board.getObjective("le_sidebar").displayName()));
    lease.close();
    ext.sidebar.render(p, board);
    assertNotEquals("Arena", plain(board.getObjective("le_sidebar").displayName()));
  }

  @Test
  void scoreboardEditorWorksForOpWhenModuleIsDisabled() throws Exception {
    var p = admin();
    await(plugin.module("scoreboard", false));
    assertTrue(p.isOp());
    assertTrue(p.hasPermission("loveessential.admin"));
    server.dispatchCommand(p, "le scoreboard edit");
    assertNotNull(p.getOpenInventory().getTopInventory());
    assertEquals(
        Material.WRITABLE_BOOK, p.getOpenInventory().getTopInventory().getItem(20).getType());
    var event =
        new InventoryClickEvent(
            p.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            20,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    server.getScheduler().performOneTick();
    assertNotNull(forms.get(p.getUniqueId()));
    assertFalse(plugin.module("scoreboard"));
  }

  @Test
  void scoreboardAdminCanEditWithoutPersonalPermissionAndToggleDisabledModule() throws Exception {
    var p = server.addPlayer("Staff");
    p.addAttachment(plugin, "loveessential.admin", true);
    p.addAttachment(plugin, "loveessential.scoreboard", false);
    await(plugin.module("scoreboard", false));
    assertFalse(p.isOp());
    server.dispatchCommand(p, "le scoreboard worlds");
    assertNotNull(p.getOpenInventory().getTopInventory());
    var edit =
        new InventoryClickEvent(
            p.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            19,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(edit);
    server.getScheduler().performOneTick();
    waitFor(() -> ext.sidebar.catalog().disabledWorlds().contains("world"));
    server.dispatchCommand(p, "le scoreboard edit");
    assertEquals(Material.GRAY_DYE, p.getOpenInventory().getTopInventory().getItem(4).getType());
    var enable =
        new InventoryClickEvent(
            p.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            4,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(enable);
    server.getScheduler().performOneTick();
    waitFor(() -> plugin.module("scoreboard"));
    assertEquals(Material.LIME_DYE, p.getOpenInventory().getTopInventory().getItem(4).getType());
    assertTrue(await(store.get("module", "scoreboard", Boolean.class)).orElseThrow().value());
  }

  @Test
  void scoreboardDisabledMessageAndAdminPermissionAreDistinct() throws Exception {
    var p = server.addPlayer("Player");
    await(plugin.module("scoreboard", false));
    assertEquals(
        "errors.module-disabled",
        assertThrows(IllegalArgumentException.class, () -> ext.sidebar.open(p, "", 0))
            .getMessage());
    assertEquals(
        "errors.permission",
        assertThrows(IllegalArgumentException.class, () -> ext.sidebar.settings(p)).getMessage());
    p.setOp(true);
    p.addAttachment(plugin, "loveessential.admin", false);
    assertEquals(
        "errors.permission",
        assertThrows(IllegalArgumentException.class, () -> ext.sidebar.settings(p)).getMessage());
  }

  @Test
  void scoreboardHasSeparateAdminMenuAndSqlWorldToggle() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le scoreboard settings");
    assertEquals(
        Material.GRASS_BLOCK, p.getOpenInventory().getTopInventory().getItem(22).getType());
    ext.sidebar.worlds(p, 0);
    var event =
        new InventoryClickEvent(
            p.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            19,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    server.getScheduler().performOneTick();
    assertTrue(event.isCancelled());
    waitFor(() -> ext.sidebar.catalog().disabledWorlds().contains("world"));
    assertFalse(ext.sidebar.eligible(p));
    assertTrue(
        await(store.get("scoreboard-catalog", "default", Sidebar.Catalog.class))
            .orElseThrow()
            .value()
            .disabledWorlds()
            .contains("world"));
    p.setOp(false);
    assertThrows(IllegalArgumentException.class, () -> ext.sidebar.settings(p));
  }

  @Test
  void scoreboardCatalogCanBeEditedThroughNativeForm() throws Exception {
    var p = admin();
    ext.sidebar.editCatalog(p, 0);
    respond(
        p,
        "save",
        Map.of(
            "title",
            "<gold>Наш сервер",
            "separator",
            "",
            "bottom",
            "<gray>Королевство",
            "max",
            "6",
            "worlds",
            "arena"));
    waitFor(() -> lang.text(ext.sidebar.catalog().titleKey()).contains("Наш сервер"));
    assertEquals(6, ext.sidebar.catalog().max());
    assertEquals(List.of("arena"), ext.sidebar.catalog().disabledWorlds());
    assertTrue(
        await(store.get("scoreboard-catalog", "default", Sidebar.Catalog.class)).isPresent());
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

  @Test
  void sidebarMigratesLegacyCatalogAndSqliteProfilesOnlyOnce() throws Exception {
    var tweaks = MockBukkit.createMockPlugin("LoveTweaks");
    var folder = tweaks.getDataFolder().toPath();
    java.nio.file.Files.createDirectories(folder);
    java.nio.file.Files.writeString(
        folder.resolve("config.yml"),
        "scoreboard:\n"
            + "  title: '&6Старое табло'\n"
            + "  max-placeholders: 8\n"
            + "  categories:\n"
            + "    general:\n"
            + "      display-name: '&bОбщее'\n"
            + "  placeholders:\n"
            + "    coords:\n"
            + "      display-name: '&aКоординаты'\n"
            + "      category: general\n"
            + "      variants:\n"
            + "      - {name: 'Первый', template: '&7XYZ'}\n"
            + "      - {name: 'Второй', template: '&7Позиция'}\n");
    String yamlId = UUID.randomUUID().toString();
    java.nio.file.Files.writeString(
        folder.resolve("scoreboard_data.yml.bak"),
        yamlId + ":\n  enabled: false\n  sections: [coords]\n");
    String id = UUID.randomUUID().toString();
    try (var c =
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("lovetweaks.db"));
        var st = c.createStatement()) {
      st.execute(
          "CREATE TABLE scoreboard_players(uuid TEXT PRIMARY KEY, enabled INTEGER, view_all"
              + " INTEGER)");
      st.execute(
          "CREATE TABLE scoreboard_placeholders(uuid TEXT, position INTEGER, placeholder_id TEXT,"
              + " variant INTEGER)");
      st.execute("INSERT INTO scoreboard_players VALUES('" + id + "',0,1)");
      st.execute("INSERT INTO scoreboard_placeholders VALUES('" + id + "',0,'coords',1)");
    }
    var catalog =
        await(store.get("scoreboard-catalog", "default", Sidebar.Catalog.class)).orElseThrow();
    await(store.delete("scoreboard-catalog", "default", catalog.revision(), "test"));
    var marker = await(store.get("migration", "scoreboard-players", Boolean.class)).orElseThrow();
    await(store.delete("migration", "scoreboard-players", marker.revision(), "test"));
    var side =
        new Sidebar(
            plugin,
            new MainThread(plugin),
            store,
            lang,
            new LoveBridge(),
            internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class),
            internal("tab", dev.lovelace.loveessential.tab.TabModule.class),
            internal("content", ContentLibrary.class));
    try {
      await(side.load());
      assertEquals(2, side.catalog().lines().getFirst().variants().size());
      assertEquals(
          "Первый", lang.text(side.catalog().lines().getFirst().variantNames().getFirst()));
      assertTrue(lang.text(side.catalog().titleKey()).contains("Старое табло"));
      var state =
          await(store.get("scoreboard-player", id, Sidebar.State.class)).orElseThrow().value();
      assertFalse(state.enabled());
      assertTrue(state.viewAll());
      assertEquals(1, state.entries().getFirst().variant());
      var yamlState =
          await(store.get("scoreboard-player", yamlId, Sidebar.State.class)).orElseThrow().value();
      assertFalse(yamlState.enabled());
      assertEquals("coords", yamlState.entries().getFirst().id());
      await(side.load());
      assertEquals(
          state,
          await(store.get("scoreboard-player", id, Sidebar.State.class)).orElseThrow().value());
    } finally {
      side.close();
    }
  }
}
