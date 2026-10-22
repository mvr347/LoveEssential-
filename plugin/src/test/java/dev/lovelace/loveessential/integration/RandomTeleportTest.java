package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.module.RandomTeleport;
import dev.lovelace.loveessential.module.TeleportService;
import dev.lovelace.loveessential.module.TeleportPlatform;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.io.File;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The /rtp flow: warmup, cooldown across relogs, the charge, and the point queue. */
class RandomTeleportTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  World world;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    for (var module : List.of("scoreboard", "tab", "starter")) await(plugin.module(module, false));
    // A small, predictable area: a stone floor around the origin, with air above it.
    // The same values are written to the module file, so /le reload in a test keeps them.
    setConfig("warmup-seconds", 1);
    setConfig("queue.size", 3);
    setConfig("queue.interval-ticks", 1);
    setConfig("worlds.world.center-on-border", false);
    setConfig("worlds.world.min-radius", 0);
    setConfig("worlds.world.max-radius", 24);
    setField(rtp(), "platform", platform());
    setField(teleportService(), "platform", platform());
    floor(Material.STONE);
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  void setConfig(String path, Object value) throws Exception {
    plugin.getConfig().set("rtp." + path, value);
    var file = new File(plugin.getDataFolder(), "modules/rtp/rtp.yml");
    var yaml = YamlConfiguration.loadConfiguration(file);
    yaml.set(path, value);
    yaml.save(file);
  }

  void floor(Material ground) {
    for (int x = -26; x <= 26; x++)
      for (int z = -26; z <= 26; z++) {
        world.getBlockAt(x, 63, z).setType(ground);
        for (int y = 64; y <= 66; y++) world.getBlockAt(x, y, z).setType(Material.AIR);
      }
  }

  void waitFor(BooleanSupplier test) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!test.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(test.getAsBoolean(), "async server operation did not finish");
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

  /** Reflection reads are used inside wait predicates too, so they cannot throw checked errors. */
  RandomTeleport rtp() {
    try {
      var f = LoveEssentialPlugin.class.getDeclaredField("gameplay");
      f.setAccessible(true);
      return ((Gameplay) f.get(plugin)).randomTeleport();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The mock server leaves async chunk loads and async moves unimplemented, and cannot answer block
   * passability. Here chunks load at once, moves use the synchronous teleport, and a spot is
   * standable when stone is underfoot and air is at feet and head. The real rules run on Paper.
   */
  static TeleportPlatform platform() {
    return new TeleportPlatform() {
      @Override
      public CompletableFuture<Chunk> loadChunk(World w, int x, int z, boolean generate) {
        return CompletableFuture.completedFuture(w.getChunkAt(x, z, generate));
      }

      @Override
      public boolean standable(Location at) {
        return at.clone().subtract(0, 1, 0).getBlock().getType() == Material.STONE
            && at.getBlock().getType() == Material.AIR
            && at.clone().add(0, 1, 0).getBlock().getType() == Material.AIR;
      }

      @Override
      public CompletableFuture<Boolean> move(Player player, Location to) {
        return CompletableFuture.completedFuture(player.teleport(to));
      }
    };
  }

  TeleportService teleportService() {
    try {
      var f = Gameplay.class.getDeclaredField("teleport");
      f.setAccessible(true);
      var gameplay = LoveEssentialPlugin.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      return (TeleportService) f.get(gameplay.get(plugin));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  static void setField(Object target, String name, Object value) {
    try {
      var f = target.getClass().getDeclaredField(name);
      f.setAccessible(true);
      f.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  Object state(String field) {
    try {
      var f = RandomTeleport.class.getDeclaredField(field);
      f.setAccessible(true);
      return f.get(rtp());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  Map<?, ?> sessions() {
    return (Map<?, ?>) state("sessions");
  }

  int queued(String world) {
    var queues = (Map<?, ?>) state("queues");
    var queue = (Collection<?>) queues.get(world);
    return queue == null ? 0 : queue.size();
  }

  PlayerMock join(String name, Location at) {
    var p = server.addPlayer(name);
    p.teleport(at);
    return p;
  }

  Location origin() {
    return new Location(world, .5, 64, .5);
  }

  List<String> messages(PlayerMock p) {
    var out = new ArrayList<String>();
    net.kyori.adventure.text.Component m;
    while ((m = p.nextComponentMessage()) != null)
      out.add(PlainTextComponentSerializer.plainText().serialize(m));
    return out;
  }

  List<String> actionBars(PlayerMock p) {
    var out = new ArrayList<String>();
    net.kyori.adventure.text.Component m;
    while ((m = p.nextActionBar()) != null)
      out.add(PlainTextComponentSerializer.plainText().serialize(m));
    return out;
  }

  /** Reads the saved cooldown, waiting for the store's writer; empty while it is not there yet. */
  static Optional<String> savedCooldown(SqliteStore store, UUID uuid) {
    try {
      return store.profile(uuid, RandomTeleport.COOLDOWN_KEY).get(1, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      return Optional.empty();
    }
  }

  /** Everything any player was told, across calls: the tests read it in waits. */
  final List<String> seen = new ArrayList<>();

  boolean heard(PlayerMock p, String fragment) {
    seen.addAll(messages(p));
    return seen.stream().anyMatch(m -> m.contains(fragment));
  }

  LoveEconomy economy(AtomicLong balance) {
    return (LoveEconomy)
        Proxy.newProxyInstance(
            LoveEconomy.class.getClassLoader(),
            new Class<?>[] {LoveEconomy.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "charge":
                  long amount = (Long) args[1];
                  if (balance.get() < amount) return false;
                  balance.addAndGet(-amount);
                  return true;
                case "give":
                  balance.addAndGet((Long) args[1]);
                  return null;
                case "has":
                  return balance.get() >= (Long) args[1];
                case "canFit":
                  return true;
                case "balance":
                  return balance.get();
                case "currencyName":
                  return "coin";
                case "denominations":
                case "allDenominations":
                  return List.of();
                case "valueOf":
                  return 0L;
                default:
                  if (method.isDefault())
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                  return null;
              }
            });
  }

  void payWith(AtomicLong balance) {
    server
        .getServicesManager()
        .register(LoveEconomy.class, economy(balance), plugin, ServicePriority.Normal);
  }

  /** Waits until the request has passed its search and the countdown has started. */
  void untilWarming(PlayerMock p) throws Exception {
    waitFor(
        () -> {
          seen.addAll(actionBars(p));
          return seen.stream().anyMatch(m -> m.contains("Перенос через"));
        });
  }

  // --- warmup ----------------------------------------------------------------------------

  @Test
  void aMoveDuringTheWarmupCancelsTheRequestAndTakesNoMoney() throws Exception {
    var balance = new AtomicLong(1000);
    payWith(balance);
    setConfig("cost", 100);
    var p = join("Walker", origin());
    server.dispatchCommand(p, "rtp");
    untilWarming(p);

    var from = p.getLocation();
    var to = from.clone().add(2, 0, 0);
    server.getPluginManager().callEvent(new PlayerMoveEvent(p, from, to));

    assertTrue(heard(p, "Перенос отменён: вы сдвинулись"));
    assertTrue(sessions().isEmpty(), "the request must be gone after the move");
    server.getScheduler().performTicks(60);
    assertEquals(1000, balance.get(), "a cancelled request charges nothing");
    assertFalse(heard(p, "Вы перенесены"));
  }

  @Test
  void aHitDuringTheWarmupCancelsTheRequest() throws Exception {
    var p = join("Hurt", origin());
    server.dispatchCommand(p, "rtp");
    untilWarming(p);

    var hit =
        new EntityDamageEvent(
            p,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            DamageSource.builder(DamageType.GENERIC).build(),
            1);
    server.getPluginManager().callEvent(hit);

    assertTrue(heard(p, "Перенос отменён: вас ранили"));
    assertTrue(sessions().isEmpty());
  }

  @Test
  void aStandingPlayerArrivesAfterTheWarmupAndIsProtectedAfterwards() throws Exception {
    var p = join("Settler", origin());
    server.dispatchCommand(p, "rtp");
    waitFor(() -> heard(p, "Вы перенесены"));

    var at = p.getLocation();
    assertEquals(64, at.getBlockY(), "the spot is on the floor, one block above it");
    assertTrue(at.distance(origin()) <= 26, "the spot lies inside the search ring: " + at);
    var hit =
        new EntityDamageEvent(
            p,
            EntityDamageEvent.DamageCause.FALL,
            DamageSource.builder(DamageType.FALL).build(),
            6);
    server.getPluginManager().callEvent(hit);
    assertTrue(hit.isCancelled(), "a fresh arrival takes no environmental damage");
  }

  // --- cooldown and cost -------------------------------------------------------------------

  @Test
  void aSuccessfulArrivalIsChargedOnce() throws Exception {
    var balance = new AtomicLong(1000);
    payWith(balance);
    setConfig("cost", 100);
    var p = join("Payer", origin());
    server.dispatchCommand(p, "rtp");
    waitFor(() -> heard(p, "Вы перенесены"));
    assertEquals(900, balance.get());
  }

  @Test
  void aFailedMoveIsNotChargedWhenTheSpotBecameUnsafeDuringTheWarmup() throws Exception {
    var balance = new AtomicLong(1000);
    payWith(balance);
    setConfig("cost", 100);
    var p = join("Unlucky", origin());
    server.dispatchCommand(p, "rtp");
    untilWarming(p);
    floor(Material.AIR); // the ground under every candidate is gone before the warmup ends

    waitFor(() -> heard(p, "Точка больше не подходит"));
    assertEquals(1000, balance.get(), "a refused move must not take money");
    assertTrue(sessions().isEmpty());
  }

  @Test
  void theCooldownSurvivesARelog() throws Exception {
    var uuid = UUID.randomUUID();
    var first = new PlayerMock(server, "Returner", uuid);
    server.addPlayer(first);
    first.teleport(origin());
    server.dispatchCommand(first, "rtp");
    waitFor(() -> heard(first, "Вы перенесены"));
    var store = internal("store", SqliteStore.class);
    waitFor(() -> savedCooldown(store, uuid).isPresent());

    first.disconnect();
    var again = new PlayerMock(server, "Returner", uuid);
    server.addPlayer(again);
    again.teleport(origin());
    server.dispatchCommand(again, "rtp");
    waitFor(() -> heard(again, "Следующий перенос через"));
    assertTrue(sessions().isEmpty(), "a cooling player gets no warmup");
  }

  // --- queue ------------------------------------------------------------------------------

  @Test
  void theQueueNeverExceedsItsSizeAndIsEmptiedByReloadAndByDisable() throws Exception {
    String name = world.getName();
    for (int i = 0; i < 80; i++) {
      server.getScheduler().performOneTick();
      assertTrue(queued(name) <= 3, "queue grew past rtp.queue.size: " + queued(name));
    }
    waitFor(() -> queued(name) == 3);

    // The interval is part of the file the reload reads, so set it there first.
    setConfig("queue.interval-ticks", 1200);
    await(plugin.reloadSettings());
    assertEquals(0, queued(name), "a settings reload drops the prepared points");

    await(plugin.module("rtp", false));
    assertEquals(0, queued(name), "disabling the module drops the prepared points");
    server.getScheduler().performTicks(40);
    assertEquals(0, queued(name), "a disabled module does not refill the queue");
  }
}
