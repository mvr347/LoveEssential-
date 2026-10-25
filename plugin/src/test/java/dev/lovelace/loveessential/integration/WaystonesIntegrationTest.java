package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.combat.CombatState;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.Position;
import dev.lovelace.loveessential.module.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.travel.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class WaystonesIntegrationTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  SqliteStore store;
  Waystones travel;
  World world;
  PlayerMock player;
  AtomicLong balance;
  AtomicInteger charges;
  AtomicBoolean combat;
  CompletableFuture<Chunk> chunk;
  boolean success;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("waystones.render.enabled", false);
    plugin.getConfig().set("npc.max-active", 0);
    for (String id : List.of("tab", "scoreboard", "starter")) await(plugin.module(id, false));
    var f = LoveEssentialPlugin.class.getDeclaredField("store");
    f.setAccessible(true);
    store = (SqliteStore) f.get(plugin);
    f = LoveEssentialPlugin.class.getDeclaredField("gameplay");
    f.setAccessible(true);
    travel = ((Gameplay) f.get(plugin)).waystones();
    combat = new AtomicBoolean();
    var state =
        (CombatState)
            Proxy.newProxyInstance(
                CombatState.class.getClassLoader(),
                new Class[] {CombatState.class},
                (o, m, a) ->
                    switch (m.getName()) {
                      case "inCombat" -> combat.get();
                      case "combatEndsAt", "combatSource" -> Optional.empty();
                      default -> null;
                    });
    server.getServicesManager().register(CombatState.class, state, plugin, ServicePriority.Highest);
    balance = new AtomicLong(100);
    charges = new AtomicInteger();
    var money =
        (LoveEconomy)
            Proxy.newProxyInstance(
                LoveEconomy.class.getClassLoader(),
                new Class[] {LoveEconomy.class},
                (o, m, a) ->
                    switch (m.getName()) {
                      case "currencyName" -> "монет";
                      case "has" -> balance.get() >= (long) a[1];
                      case "charge" -> {
                        long amount = (long) a[1];
                        if (balance.get() < amount) yield false;
                        balance.addAndGet(-amount);
                        charges.incrementAndGet();
                        yield true;
                      }
                      case "give" -> {
                        balance.addAndGet((long) a[1]);
                        yield null;
                      }
                      case "balance" -> balance.get();
                      default -> throw new UnsupportedOperationException(m.getName());
                    });
    server.getServicesManager().register(LoveEconomy.class, money, plugin, ServicePriority.Highest);
    success = true;
    f = Waystones.class.getDeclaredField("platform");
    f.setAccessible(true);
    f.set(
        travel,
        new TeleportPlatform() {
          public CompletableFuture<Chunk> loadChunk(World w, int x, int z, boolean generate) {
            assertFalse(generate);
            return chunk != null ? chunk : CompletableFuture.completedFuture(w.getChunkAt(x, z));
          }

          public boolean standable(Location at) {
            return true;
          }

          public CompletableFuture<Boolean> move(Player p, Location at) {
            if (success) p.teleport(at);
            return CompletableFuture.completedFuture(success);
          }
        });
    player = server.addPlayer("Traveler");
    player.teleport(new Location(world, .5, 64, .5));
    waitFor(
        () -> {
          try {
            var field = Waystones.class.getDeclaredField("loading");
            field.setAccessible(true);
            return !((Set<?>) field.get(travel)).contains(player.getUniqueId());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    define(20, 0, "");
  }

  void define(long cost, int seconds, String permission) throws Exception {
    var at = Position.of(new Location(world, .5, 64, .5, 90, 0));
    var to = Position.of(new Location(world, 20.5, 64, .5, 180, 0));
    var r = new Waystone.Route("2", cost, seconds, 30, permission);
    var existing = await(store.get("waystone", "1", Waystone.class));
    await(
        store.save(
            "waystone",
            "1",
            existing.map(SqliteStore.Document::revision).orElse(0L),
            new Waystone("1", "Спавн", at, at, "wood", "", List.of(r)),
            "test"));
    existing = await(store.get("waystone", "2", Waystone.class));
    if (existing.isEmpty())
      await(
          store.save(
              "waystone",
              "2",
              0,
              new Waystone("2", "Деревня", to, to, "rune", "", List.of()),
              "test"));
    await(travel.load());
  }

  void waitFor(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  <T> T await(CompletableFuture<T> f) throws Exception {
    waitFor(f::isDone);
    return f.get();
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  @Test
  void paidArrivalChargesOnceAndPersistsCooldown() throws Exception {
    travel.begin(player, "1", "2");
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    server.getScheduler().performTicks(2);
    assertEquals(20.5, player.getX(), .01);
    assertEquals(80, balance.get());
    assertEquals(1, charges.get());
    var pending = new AtomicReference<CompletableFuture<Optional<String>>>();
    waitFor(
        () -> {
          var query = pending.get();
          if (query == null) {
            pending.set(store.profile(player.getUniqueId(), "waystones-cooldown"));
            return false;
          }
          if (!query.isDone()) return false;
          var value = query.join();
          if (value.isPresent()) return Long.parseLong(value.get()) > System.currentTimeMillis();
          pending.set(null);
          return false;
        });
    player.teleport(new Location(world, .5, 64, .5));
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
  }

  @Test
  void failedMoveRefundsAndDoesNotStartCooldown() throws Exception {
    success = false;
    travel.begin(player, "1", "2");
    server.getScheduler().performTicks(2);
    assertEquals(100, balance.get());
    assertEquals(1, charges.get());
    assertTrue(await(store.profile(player.getUniqueId(), "waystones-cooldown")).isEmpty());
  }

  @Test
  void movementDuringChunkLoadCancelsBeforeChargeAndLateResultCannotMove() throws Exception {
    chunk = new CompletableFuture<>();
    travel.begin(player, "1", "2");
    server.getScheduler().performOneTick();
    player.teleport(new Location(world, 2, 64, 0));
    server.getScheduler().performOneTick();
    chunk.complete(world.getChunkAt(1, 0));
    server.getScheduler().performTicks(2);
    assertEquals(0, charges.get());
    assertEquals(2, player.getX(), .01);
  }

  @Test
  void insufficientFundsCombatPermissionAndRemoteCommandsAreRejected() throws Exception {
    balance.set(0);
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    balance.set(100);
    combat.set(true);
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    combat.set(false);
    define(0, 0, "private.route");
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    player.teleport(new Location(world, 100, 64, 100));
    assertThrows(IllegalArgumentException.class, () -> travel.command(player, new String[] {}));
    assertThrows(
        IllegalArgumentException.class, () -> travel.command(player, new String[] {"edit"}));
    assertEquals(0, charges.get());
  }

  @Test
  void disablingModuleRejectsPendingCompletion() throws Exception {
    chunk = new CompletableFuture<>();
    travel.begin(player, "1", "2");
    server.getScheduler().performOneTick();
    await(plugin.module("waystones", false));
    chunk.complete(world.getChunkAt(1, 0));
    server.getScheduler().performTicks(2);
    assertEquals(0, charges.get());
    assertEquals(.5, player.getX(), .01);
  }

  @Test
  void directedRoutesPersistAndFreeRoutesNeedNoEconomy() throws Exception {
    define(0, 0, "");
    server
        .getServicesManager()
        .unregister(LoveEconomy.class, server.getServicesManager().load(LoveEconomy.class));
    assertTrue(
        travel.all().stream()
            .filter(n -> n.id().equals("2"))
            .findFirst()
            .orElseThrow()
            .routes()
            .isEmpty());
    assertEquals(
        90,
        travel.all().stream()
            .filter(n -> n.id().equals("1"))
            .findFirst()
            .orElseThrow()
            .anchor()
            .yaw());
    travel.begin(player, "1", "2");
    server.getScheduler().performTicks(2);
    assertEquals(20.5, player.getX(), .01);
  }

  @Test
  void wizardCreatesDifferentStylesAndOrientationsAndEditorIsStaffOnly() throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField("extensions");
    f.setAccessible(true);
    var ui = ((Extensions) f.get(plugin)).ui;
    var form = new AtomicReference<dev.lovelace.loveessential.dialog.NativeDialogs.Form>();
    ui.presenter((p, value) -> form.set(value));
    player.setOp(true);
    await(store.nextSequence("waystone", 999));
    await(store.nextSequence("waystone", 999));
    player.teleport(new Location(world, 40.5, 64, .5));
    assertTrue(server.dispatchCommand(player, "le waystone create"));
    assertNotNull(form.get());
    assertTrue(
        ui.respond(
            player,
            form.get().token(),
            "create",
            Map.of(
                "name",
                "Восточный столб",
                "style",
                "Деревянный",
                "direction",
                "Восток",
                "model",
                "")));
    waitFor(() -> travel.all().size() == 3);
    var created = travel.all().stream().filter(n -> n.id().equals("3")).findFirst().orElseThrow();
    assertEquals("wood", created.style());
    assertEquals(270, created.anchor().yaw());
    var inventory = player.getOpenInventory().getTopInventory();
    assertEquals(27, inventory.getSize());
    assertTrue(dev.lovelace.loveessential.gui.GuiRules.violations(inventory, false).isEmpty());
    player.setOp(false);
    assertThrows(
        IllegalArgumentException.class, () -> travel.command(player, new String[] {"edit", "3"}));
  }

  @Test
  void failedItemsAdderModelLeavesNoPartialEntity() throws Exception {
    var at = Position.of(new Location(world, 40.5, 64, .5));
    world.getChunkAt(2, 0);
    await(
        store.save(
            "waystone",
            "3",
            0,
            new Waystone(
                "3", "Недоступная модель", at, at, "itemsadder", "missing:model", List.of()),
            "test"));
    await(travel.load());
    plugin.getConfig().set("waystones.render.enabled", true);
    server.getScheduler().performTicks(25);
    var key = new NamespacedKey(plugin, "waystone");
    assertTrue(
        world.getEntities().stream()
            .noneMatch(
                e ->
                    "3"
                        .equals(
                            e.getPersistentDataContainer()
                                .get(key, org.bukkit.persistence.PersistentDataType.STRING))));
    travel.close();
    assertTrue(
        world.getEntities().stream().noneMatch(e -> e.getPersistentDataContainer().has(key)));
  }

  @Test
  void unavailableWorldIsRejectedBeforePayment() throws Exception {
    var at = new Position(UUID.randomUUID(), 0, 64, 0, 0, 0);
    var current = await(store.get("waystone", "2", Waystone.class)).orElseThrow();
    await(
        store.save(
            "waystone",
            "2",
            current.revision(),
            new Waystone("2", "Другой мир", at, at, "stone", "", List.of()),
            "test"));
    await(travel.load());
    var error = assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    assertEquals("waystones.destination-unavailable", error.getMessage());
    assertEquals(0, charges.get());
  }

  @Test
  void realDamageCancelsBeforePaymentButCancelledDamageDoesNot() throws Exception {
    define(20, 2, "");
    travel.begin(player, "1", "2");
    var ignored =
        new org.bukkit.event.entity.EntityDamageEvent(
            player,
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL,
            org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build(),
            1);
    ignored.setCancelled(true);
    server.getPluginManager().callEvent(ignored);
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    var hit =
        new org.bukkit.event.entity.EntityDamageEvent(
            player,
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL,
            org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build(),
            1);
    server.getPluginManager().callEvent(hit);
    server.getScheduler().performTicks(50);
    assertEquals(0, charges.get());
    assertEquals(.5, player.getX(), .01);
  }

  @Test
  void sequenceNeverReusesAReservedOrDeletedNumber() throws Exception {
    int first = await(store.nextSequence("test-waystone", 2));
    assertEquals(1, first);
    assertEquals(2, await(store.nextSequence("test-waystone", 2)));
    assertThrows(ExecutionException.class, () -> await(store.nextSequence("test-waystone", 2)));
  }
}
