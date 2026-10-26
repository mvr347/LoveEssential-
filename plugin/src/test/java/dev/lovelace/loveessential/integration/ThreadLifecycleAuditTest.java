package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.Revive;
import dev.lovelace.loveessential.npc.NpcService;
import dev.lovelace.loveessential.tab.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ThreadLifecycleAuditTest {
  ServerMock server;
  LoveEssentialPlugin plugin;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    for (String module : List.of("tab", "scoreboard", "starter", "rtp"))
      await(plugin.module(module, false));
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  void waitFor(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < until) {
      server.getScheduler().performOneTick();
      Thread.sleep(3);
    }
    assertTrue(condition.getAsBoolean());
  }

  <T> T await(CompletableFuture<T> future) throws Exception {
    waitFor(future::isDone);
    return future.get(1, TimeUnit.SECONDS);
  }

  <T> T internal(String name, Class<T> type) throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField(name);
    f.setAccessible(true);
    return type.cast(f.get(plugin));
  }

  public static class GuardedPlayer extends PlayerMock {
    GuardedPlayer(ServerMock server, String name) {
      super(server, name);
    }

    @Override
    public String getName() {
      assertTrue(Bukkit.isPrimaryThread(), "live player name read outside main thread");
      return super.getName();
    }
  }

  GuardedPlayer player(String name) throws Exception {
    var p = new GuardedPlayer(server, name);
    server.addPlayer(p);
    await(plugin.async(() -> null));
    server.getScheduler().performTicks(4);
    return p;
  }

  @Test
  void lifecycleAndEntityEntryPointsRejectOffThreadCallsBeforeChangingState() throws Exception {
    var npcs = internal("npcs", NpcService.class);
    var revive = internal("revive", Revive.class);
    await(
        plugin.asyncRun(
            () -> {
              assertThrows(IllegalStateException.class, plugin::onEnable);
              assertThrows(IllegalStateException.class, plugin::onDisable);
              assertThrows(IllegalStateException.class, revive::start);
              assertThrows(IllegalStateException.class, revive::close);
              assertThrows(IllegalStateException.class, () -> npcs.entity("missing"));
            }));
    assertTrue(plugin.ready());
    assertTrue(plugin.isEnabled());
  }

  @Test
  void revivePublishesImmutableValuesWithoutLookingUpLiveHelpersOffThread() throws Exception {
    await(plugin.module("revive", true));
    plugin.getConfig().set("revive.reliving-count", 10);
    var downed = player("Downed");
    var helper = player("Helper");
    helper.teleport(downed.getLocation());
    helper.setSneaking(true);
    server.dispatchCommand(server.getConsoleSender(), "revive down Downed");
    server.getScheduler().performTicks(10);
    var revive = internal("revive", Revive.class);
    var snapshot = plugin.async(() -> revive.values(downed.getUniqueId())).get(2, TimeUnit.SECONDS);
    assertEquals("Helper", snapshot.get("revive_reviver"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", "y"));
  }

  @Test
  void asynchronousTextUsesBoundedCacheAndOnlyRendersOnMainThread() throws Exception {
    var p = player("Reader");
    var lang = internal("lang", Lang.class);
    await(
        plugin.asyncRun(
            () -> {
              try {
                lang.putAll(Map.of("audit.thread.a", "<player>", "audit.thread.b", "<player>"));
              } catch (Exception failure) {
                throw new CompletionException(failure);
              }
            }));
    var expansion =
        new LoveExpansion(
            plugin,
            internal("placeholders", Placeholders.class),
            lang,
            internal("love", LoveBridge.class),
            who -> false,
            who -> false);
    try {
      assertEquals("Reader", expansion.onRequest(p, "text_audit.thread.a"));
      assertEquals(
          "Reader",
          plugin
              .async(() -> expansion.onRequest(p, "text_audit.thread.a"))
              .get(2, TimeUnit.SECONDS));
      assertEquals(
          "",
          plugin
              .async(() -> expansion.onRequest(p, "text_audit.thread.b"))
              .get(2, TimeUnit.SECONDS));
      server.getScheduler().performTicks(2);
      assertEquals(
          "Reader",
          plugin
              .async(() -> expansion.onRequest(p, "text_audit.thread.b"))
              .get(2, TimeUnit.SECONDS));
      expansion.close();
      server.getScheduler().performTicks(2);
      var field = LoveExpansion.class.getDeclaredField("texts");
      field.setAccessible(true);
      var cache = (com.github.benmanes.caffeine.cache.Cache<?, ?>) field.get(expansion);
      assertEquals(0, cache.estimatedSize());
      assertEquals(
          "",
          plugin
              .async(() -> expansion.onRequest(p, "text_audit.thread.a"))
              .get(2, TimeUnit.SECONDS));
    } finally {
      expansion.close();
    }
  }

  @Test
  void pendingMainThreadCallsAreCancelledAndTheirBukkitWorkNeverRunsAfterStop() throws Exception {
    var main = new MainThread(plugin);
    var ran = new AtomicBoolean();
    CompletableFuture<Void> pending =
        plugin
            .async(
                () ->
                    main.<Void>call(
                        () -> {
                          MainThread.require();
                          ran.set(true);
                          return null;
                        }))
            .get(2, TimeUnit.SECONDS);
    assertFalse(pending.isDone());
    MainThread.stop(plugin);
    assertTrue(pending.isCompletedExceptionally());
    server.getScheduler().performTicks(2);
    assertFalse(ran.get());
  }

  @Test
  void guiFilterDoesNotRetainPlayerIdsAfterQuit() throws Exception {
    var p = player("Admin");
    var menus = internal("menus", dev.lovelace.loveessential.gui.ControlMenus.class);
    var field = menus.getClass().getDeclaredField("vanishFilter");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var filters = (Map<UUID, Integer>) field.get(menus);
    filters.put(p.getUniqueId(), 1);
    p.disconnect();
    assertTrue(filters.isEmpty());
  }

  @Test
  void shutdownClosesOpenMenusAndLeavesNoPluginTasksOrGuiRoutes() throws Exception {
    var p = player("Admin");
    p.setOp(true);
    server.dispatchCommand(p, "le");
    server.getPluginManager().disablePlugin(plugin);
    assertFalse(plugin.ready());
    assertFalse(
        p.getOpenInventory().getTopInventory() != null
            && p.getOpenInventory().getTopInventory().getHolder()
                instanceof dev.lovelace.loveessential.gui.GuiLayout,
        "disabled plugin must not leave an unprotected GUI open");
    assertTrue(plugin.io().isShutdown());
    var navigation = internal("navigation", dev.lovelace.loveessential.gui.MenuNavigation.class);
    var field = navigation.getClass().getDeclaredField("routes");
    field.setAccessible(true);
    var cache = (com.github.benmanes.caffeine.cache.Cache<?, ?>) field.get(navigation);
    assertEquals(0, cache.estimatedSize());
    assertTrue(
        server.getScheduler().getPendingTasks().stream().noneMatch(t -> t.getOwner() == plugin));
  }

  @Test
  void failingModuleCloseCannotSkipIoOrDatabaseShutdown() throws Exception {
    var visibility =
        new dev.lovelace.loveessential.module.Visibility(
            new dev.lovelace.loveessential.module.Visibility.Effect() {
              public void hide(UUID viewer, UUID target) {}

              public void show(UUID viewer, UUID target) {
                throw new IllegalStateException("injected-close-failure");
              }
            });
    visibility.set("vanish", UUID.randomUUID(), UUID.randomUUID(), true);
    internal("gameplay", dev.lovelace.loveessential.module.Gameplay.class).visibility(visibility);
    var store = internal("store", dev.lovelace.loveessential.storage.SqliteStore.class);
    assertDoesNotThrow(plugin::onDisable);
    assertTrue(plugin.io().isShutdown());
    var poolField = store.getClass().getDeclaredField("pool");
    poolField.setAccessible(true);
    assertTrue(((com.zaxxer.hikari.HikariDataSource) poolField.get(store)).isClosed());
  }

  @Test
  void moduleChangesRequestedOffThreadAreMarshalledToMain() throws Exception {
    var pending = plugin.async(() -> plugin.module("home", false)).get(2, TimeUnit.SECONDS);
    assertFalse(pending.isDone());
    await(pending);
    assertFalse(plugin.module("home"));
  }
}
