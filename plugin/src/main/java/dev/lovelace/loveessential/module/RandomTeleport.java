package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.Position;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.Chunk;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@code /rtp}: a random safe spot in the player's world, in the spirit of BetterRTP. A request
 * waits out a warmup, which a move or a hit cancels; the cooldown and the cost apply only to a move
 * that actually arrived. Spots are searched ahead of time and kept in a small queue per world, so a
 * request usually takes a ready point instead of loading chunks while the player waits.
 *
 * <p>Everything here runs on the server thread: commands and events already do, and asynchronous
 * results come back through {@link MainThread}. Like every module it is switched with
 * {@code /le module}; the queue is kept only while it is enabled.
 */
public final class RandomTeleport implements Listener, AutoCloseable {
  public static final String ID = "rtp";
  /** Profile key holding the epoch millis of the last successful teleport. */
  public static final String COOLDOWN_KEY = "loveessential.rtp.last";
  static final String BYPASS_COOLDOWN = "loveessential.rtp.bypass.cooldown";
  static final String COOLDOWN_GROUP = "loveessential.rtp.cooldown.";
  private static final int TICKS_PER_SECOND = 20;
  private static final int BORDER_DRAWS = 50;

  /** A standing spot: the feet block at {@code (x, y, z)}. */
  record Point(UUID world, int x, int y, int z) {}

  /** The per-world settings, read from config each time they are needed. */
  record Settings(
      boolean centerOnBorder,
      double centerX,
      double centerZ,
      double minRadius,
      double maxRadius,
      boolean circle,
      int maxAttempts,
      int minY,
      int maxY) {}

  /** One request from acceptance until it teleports, is cancelled, or fails. */
  private static final class Session {
    Location start;
    Point point;
    boolean warming;
    long due;
    int shownSecond = -1;
    boolean faded;
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final TeleportService teleport;
  private final Map<UUID, Session> sessions = new HashMap<>();
  private final Map<UUID, Long> invulnerableUntil = new HashMap<>();
  private final Map<String, ArrayDeque<Point>> queues = new HashMap<>();
  private final Random random = new Random();
  /** Replaced in tests: the mock server leaves async chunk loads and async moves unimplemented. */
  private TeleportPlatform platform = TeleportPlatform.PAPER;
  private boolean probing;
  private boolean warnedLoad;
  /** Bumped when the queue is cleared, so a probe started earlier cannot refill it. */
  private int generation;
  private int rotation;
  private long ticks;
  private BukkitTask task;

  public RandomTeleport(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      TeleportService teleport) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.teleport = teleport;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
  }

  @Override
  public void close() {
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    sessions.clear();
    invulnerableUntil.clear();
    clearQueue();
  }

  /** Drops prepared points. Used when the module is disabled and after a settings reload. */
  public void clearQueue() {
    generation++;
    queues.clear();
  }

  // ------------------------------------------------------------------ command

  /**
   * Entry point of {@code /rtp}. Refusals are thrown with message keys, reported the same way as
   * the other commands; the remaining messages are sent directly.
   */
  public void command(Player p, String[] args) {
    MainThread.require();
    if (!enabled()) throw new IllegalArgumentException("errors.module-disabled");
    if (!p.hasPermission("loveessential.rtp"))
      throw new IllegalArgumentException("errors.permission");
    plugin.requireAccess(ID, p);
    if (args.length != 0) throw new IllegalArgumentException("rtp.usage");
    if (sessions.containsKey(p.getUniqueId())) throw new IllegalArgumentException("rtp.busy");
    if (love.combat(p)) throw new IllegalArgumentException("errors.teleport-blocked");
    World destination = destination(p.getWorld());
    plugin.requireDestination(ID, destination.getName());
    var session = new Session();
    session.start = p.getLocation();
    sessions.put(p.getUniqueId(), session);
    store
        .profile(p.getUniqueId(), COOLDOWN_KEY)
        .whenComplete(
            (stored, error) ->
                main.execute(
                    () -> begin(p, session, destination, error == null ? stored : null)));
  }

  private void begin(Player p, Session session, World world, Optional<String> stored) {
    if (sessions.get(p.getUniqueId()) != session) return;
    if (stored == null || !p.isOnline()) {
      sessions.remove(p.getUniqueId());
      if (stored == null) lang.send(p, "rtp.failed");
      return;
    }
    long wait = stored.map(RandomTeleport::millis).orElse(0L) + cooldownMillis(p);
    wait -= System.currentTimeMillis();
    if (wait > 0) {
      sessions.remove(p.getUniqueId());
      lang.send(p, "rtp.cooldown", Map.of("seconds", Long.toString(seconds(wait))));
      return;
    }
    long cost = cost();
    if (cost > 0 && !affordable(p, cost)) {
      sessions.remove(p.getUniqueId());
      lang.send(p, "rtp.no-funds", Map.of("amount", Long.toString(cost)));
      return;
    }
    Point ready = poll(world);
    if (ready != null) {
      startWarmup(p, session, ready);
      return;
    }
    var settings = settings(world.getName());
    if (settings == null) {
      sessions.remove(p.getUniqueId());
      lang.send(p, "rtp.world-disabled");
      return;
    }
    search(world, settings, settings.maxAttempts(), new CompletableFuture<Point>())
        .whenComplete(
            (point, error) ->
                main.execute(
                    () -> {
                      if (sessions.get(p.getUniqueId()) != session) return;
                      if (point == null) {
                        sessions.remove(p.getUniqueId());
                        lang.send(p, "rtp.no-point");
                      } else startWarmup(p, session, point);
                    }));
  }

  private void startWarmup(Player p, Session session, Point point) {
    if (!p.isOnline()) {
      sessions.remove(p.getUniqueId());
      return;
    }
    session.point = point;
    session.start = p.getLocation();
    session.warming = true;
    session.due = ticks + (long) warmupSeconds() * TICKS_PER_SECOND;
    effectsStart(p, warmupSeconds());
  }

  // ------------------------------------------------------------------ session events

  @EventHandler(ignoreCancelled = true)
  public void onMove(PlayerMoveEvent e) {
    var session = sessions.get(e.getPlayer().getUniqueId());
    if (session == null || !session.warming || e.getTo() == null) return;
    var to = e.getTo();
    boolean moved =
        !to.getWorld().equals(session.start.getWorld())
            || to.distance(session.start) > cancelDistance();
    if (moved) cancel(e.getPlayer(), "rtp.moved");
  }

  @EventHandler(ignoreCancelled = true)
  public void onHurt(EntityDamageEvent e) {
    if (e.getEntity() instanceof Player p) cancel(p, "rtp.damaged");
  }

  /**
   * For a short time after arrival, falls and suffocation do not hurt. Runs before the other damage
   * listeners, so a protected hit is not seen as a hit.
   */
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onProtected(EntityDamageEvent e) {
    if (!(e.getEntity() instanceof Player p)) return;
    var cause = e.getCause();
    if (cause != EntityDamageEvent.DamageCause.FALL
        && cause != EntityDamageEvent.DamageCause.SUFFOCATION) return;
    Long until = invulnerableUntil.get(p.getUniqueId());
    if (until == null) return;
    if (until <= System.currentTimeMillis()) {
      invulnerableUntil.remove(p.getUniqueId());
      return;
    }
    e.setCancelled(true);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent e) {
    sessions.remove(e.getPlayer().getUniqueId());
    invulnerableUntil.remove(e.getPlayer().getUniqueId());
  }

  private void cancel(Player p, String message) {
    var session = sessions.remove(p.getUniqueId());
    if (session == null) return;
    if (session.faded) removeFade(p);
    lang.send(p, message);
  }

  // ------------------------------------------------------------------ ticking

  private void tick() {
    ticks++;
    if (!enabled()) {
      sessions.clear();
      return;
    }
    for (var entry : List.copyOf(sessions.entrySet())) {
      var session = entry.getValue();
      if (!session.warming) continue;
      var p = Bukkit.getPlayer(entry.getKey());
      if (p == null || !p.isOnline()) {
        sessions.remove(entry.getKey());
        continue;
      }
      if (ticks >= session.due) {
        sessions.remove(entry.getKey());
        finish(p, session.point);
        continue;
      }
      effectsFade(p, session, session.due - ticks);
      int seconds = (int) Math.ceil((session.due - ticks) / (double) TICKS_PER_SECOND);
      if (seconds != session.shownSecond) {
        session.shownSecond = seconds;
        p.sendActionBar(lang.render("rtp.warming", Map.of("seconds", Integer.toString(seconds))));
      }
    }
    int interval = Math.max(1, plugin.getConfig().getInt("rtp.queue.interval-ticks", 20));
    if (ticks % interval == 0 && !probing) fillOne();
  }

  /** Adds at most one point per interval, to the next world whose queue is not full. */
  private void fillOne() {
    int size = plugin.getConfig().getInt("rtp.queue.size", 10);
    if (size <= 0) return;
    var worlds = enabledWorlds();
    for (int i = 0; i < worlds.size(); i++) {
      World world = worlds.get((rotation + i) % worlds.size());
      var queue = queues.computeIfAbsent(world.getName(), name -> new ArrayDeque<>());
      if (queue.size() >= size) continue;
      rotation = (rotation + i + 1) % worlds.size();
      var settings = settings(world.getName());
      int[] column = column(world, settings);
      if (column == null) continue; // this world's ring has no free column right now; try the next
      probing = true;
      int started = generation;
      probe(world, settings, column[0], column[1])
          .whenComplete(
              (point, error) ->
                  main.execute(
                      () -> {
                        probing = false;
                        if (point == null || started != generation) return;
                        var target =
                            queues.computeIfAbsent(world.getName(), n -> new ArrayDeque<>());
                        if (target.size() < plugin.getConfig().getInt("rtp.queue.size", 10))
                          target.add(point);
                      }));
      return;
    }
  }

  private Point poll(World world) {
    var queue = queues.get(world.getName());
    return queue == null ? null : queue.poll();
  }

  // ------------------------------------------------------------------ search

  /** Tries up to {@code attemptsLeft} columns, one chunk load at a time. */
  private CompletableFuture<Point> search(
      World world, Settings settings, int attemptsLeft, CompletableFuture<Point> result) {
    if (attemptsLeft <= 0) {
      result.complete(null);
      return result;
    }
    int[] column = column(world, settings);
    if (column == null) {
      result.complete(null);
      return result;
    }
    probe(world, settings, column[0], column[1])
        .whenComplete(
            (point, error) -> {
              if (point != null) result.complete(point);
              else search(world, settings, attemptsLeft - 1, result);
            });
    return result;
  }

  /** A column inside the ring and the border, or null if none was found. */
  private int[] column(World world, Settings settings) {
    if (settings == null) return null;
    var border = world.getWorldBorder();
    var center = border.getCenter();
    double cx = settings.centerOnBorder() ? center.getX() : settings.centerX();
    double cz = settings.centerOnBorder() ? center.getZ() : settings.centerZ();
    for (int i = 0; i < BORDER_DRAWS; i++) {
      int[] column =
          RandomTeleportGeometry.sample(
              settings.circle(),
              cx,
              cz,
              settings.minRadius(),
              settings.maxRadius(),
              random);
      if (column == null) return null;
      if (RandomTeleportGeometry.insideBorder(
          column[0], column[1], center.getX(), center.getZ(), border.getSize(), borderMargin()))
        return column;
    }
    return null;
  }

  /** Loads the chunk asynchronously, then judges the column on the server thread. */
  private CompletableFuture<Point> probe(World world, Settings settings, int x, int z) {
    var result = new CompletableFuture<Point>();
    CompletableFuture<Chunk> loading;
    try {
      loading = platform.loadChunk(world, x >> 4, z >> 4, true);
    } catch (RuntimeException e) {
      // A failing load must not break the tick that started it: the column is simply skipped.
      if (!warnedLoad) plugin.getLogger().warning("RTP chunk load failed: " + e);
      warnedLoad = true;
      result.complete(null);
      return result;
    }
    loading
        .whenComplete(
            (chunk, error) ->
                main.execute(
                    () -> {
                      Point point = null;
                      if (error == null && chunk != null) {
                        try {
                          point = evaluate(world, settings, x, z);
                        } catch (RuntimeException e) {
                          plugin.getLogger().warning("RTP probe failed: " + e);
                        }
                      }
                      result.complete(point);
                    }));
    return result;
  }

  /** Server thread only. Returns the standing point for a column, or null if it is unsuitable. */
  private Point evaluate(World world, Settings settings, int x, int z) {
    var border = world.getWorldBorder();
    var center = border.getCenter();
    if (!RandomTeleportGeometry.insideBorder(
        x, z, center.getX(), center.getZ(), border.getSize(), borderMargin())) return null;
    var blocked = blacklistBlocks();
    Integer y;
    if (world.getEnvironment() == World.Environment.NETHER) {
      // The Nether has no surface to read: look down from the ceiling for the first floor.
      y =
          RandomTeleportGeometry.highestStandable(
              settings.maxY(), settings.minY(), c -> spot(world, blocked, x, c, z));
    } else {
      y = world.getHighestBlockYAt(x, z) + 1;
      if (!RandomTeleportGeometry.inRange(y, settings.minY(), settings.maxY())
          || !spot(world, blocked, x, y, z)) y = null;
    }
    if (y == null) return null;
    if (blockedBiome(world.getBiome(x, y, z))) return null;
    if (love.claimed(new Location(world, x + .5, y, z + .5))) return null;
    return new Point(world.getUID(), x, y, z);
  }

  /** Server thread only: the feet spot is safe for a player and no blacklisted block touches it. */
  private boolean spot(World world, Set<Material> blocked, int x, int y, int z) {
    if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) return false;
    if (!platform.standable(new Location(world, x + .5, y, z + .5))) return false;
    return !blocked.contains(world.getBlockAt(x, y - 1, z).getType())
        && !blocked.contains(world.getBlockAt(x, y, z).getType())
        && !blocked.contains(world.getBlockAt(x, y + 1, z).getType());
  }

  // ------------------------------------------------------------------ finishing

  /** Server thread only: the warmup has ended. The point is checked again before the move. */
  private void finish(Player p, Point point) {
    if (!enabled() || !p.isOnline()) return;
    World world = Bukkit.getWorld(point.world());
    var settings = world == null ? null : settings(world.getName());
    if (settings == null) {
      removeFade(p);
      lang.send(p, "rtp.world-disabled");
      return;
    }
    long cost = cost();
    if (cost > 0 && !affordable(p, cost)) {
      removeFade(p);
      lang.send(p, "rtp.no-funds", Map.of("amount", Long.toString(cost)));
      return;
    }
    probe(world, settings, point.x(), point.z())
        .whenComplete((fresh, error) -> main.execute(() -> move(p, fresh, cost)));
  }

  private void move(Player p, Point point, long cost) {
    if (!p.isOnline()) return;
    World world = point == null ? null : Bukkit.getWorld(point.world());
    if (point == null || world == null) {
      removeFade(p);
      lang.send(p, "rtp.failed");
      return;
    }
    var at = new Location(world, point.x() + .5, point.y(), point.z() + .5, p.getYaw(), 0);
    teleport.teleport(
        p,
        Position.of(at),
        arrived -> {
          if (arrived) succeeded(p, cost);
        });
  }

  /** Charged, cooled down and protected only once the player has actually arrived. */
  private void succeeded(Player p, long cost) {
    long now = System.currentTimeMillis();
    store
        .profile(p.getUniqueId(), COOLDOWN_KEY, Long.toString(now))
        .whenComplete(
            (ok, error) -> {
              if (error != null)
                plugin.getLogger().warning("RTP cooldown was not saved: " + error);
            });
    int protectedSeconds = plugin.getConfig().getInt("rtp.invulnerable-seconds", 5);
    if (protectedSeconds > 0)
      invulnerableUntil.put(p.getUniqueId(), now + protectedSeconds * 1000L);
    if (cost > 0) {
      var economy = love.economy();
      if (economy.isEmpty() || !economy.get().charge(p, cost))
        plugin.getLogger().warning("RTP arrival of " + p.getName() + " was not charged " + cost);
    }
    lang.send(p, "rtp.done");
    effectsArrived(p);
  }

  // ------------------------------------------------------------------ settings

  private boolean enabled() {
    return plugin.module(ID);
  }

  private int warmupSeconds() {
    return plugin.getConfig().getInt("rtp.warmup-seconds", 5);
  }

  private double cancelDistance() {
    return plugin.getConfig().getDouble("rtp.cancel-distance", 0.5);
  }

  private int borderMargin() {
    return plugin.getConfig().getInt("rtp.border-margin", 16);
  }

  private long cost() {
    return plugin.getConfig().getLong("rtp.cost", 0);
  }

  private static long seconds(long millis) {
    return (millis + 999) / 1000;
  }

  private static long millis(String stored) {
    try {
      return Long.parseLong(stored);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /** The shortest cooldown the player qualifies for; the bypass permission removes it. */
  long cooldownMillis(Player p) {
    if (p.hasPermission(BYPASS_COOLDOWN)) return 0;
    long seconds = plugin.getConfig().getLong("rtp.cooldown-seconds", 300);
    var groups = plugin.getConfig().getConfigurationSection("rtp.cooldown-groups");
    if (groups != null)
      for (String group : groups.getKeys(false))
        if (p.hasPermission(COOLDOWN_GROUP + group))
          seconds = Math.min(seconds, groups.getLong(group));
    return seconds * 1000L;
  }

  private boolean affordable(Player p, long cost) {
    return love.economy().map(economy -> economy.has(p, cost)).orElse(false);
  }

  /** The world a player teleports within: their own, or the one {@code world-map} names for it. */
  private World destination(World from) {
    var map = plugin.getConfig().getConfigurationSection("rtp.world-map");
    String name = map == null ? from.getName() : map.getString(from.getName(), from.getName());
    World world = Bukkit.getWorld(name);
    if (world == null || settings(world.getName()) == null)
      throw new IllegalArgumentException("rtp.world-disabled");
    return world;
  }

  /** Settings of an enabled world, or null when random teleport is off there. */
  Settings settings(String world) {
    var section = plugin.getConfig().getConfigurationSection("rtp.worlds." + world);
    if (section == null) {
      // A world without its own entry uses rtp.default-world, but only an ordinary (overworld-like) one:
      // the Nether and the End need their own height range.
      World loaded = Bukkit.getWorld(world);
      if (loaded != null && loaded.getEnvironment() == World.Environment.NORMAL)
        section = plugin.getConfig().getConfigurationSection("rtp.default-world");
    }
    if (section == null || !section.getBoolean("enabled")) return null;
    return new Settings(
        section.getBoolean("center-on-border", true),
        section.getDouble("center-x"),
        section.getDouble("center-z"),
        section.getDouble("min-radius", 200),
        section.getDouble("max-radius", 2000),
        !"square".equals(section.getString("shape", "circle")),
        section.getInt("max-attempts", 20),
        section.getInt("min-y", 60),
        section.getInt("max-y", 220));
  }

  private List<World> enabledWorlds() {
    var out = new ArrayList<World>();
    var section = plugin.getConfig().getConfigurationSection("rtp.worlds");
    if (section == null) return out;
    for (String name : new TreeSet<>(section.getKeys(false))) {
      World world = Bukkit.getWorld(name);
      if (world != null && settings(name) != null) out.add(world);
    }
    return out;
  }

  private Set<Material> blacklistBlocks() {
    var out = EnumSet.noneOf(Material.class);
    for (String name : plugin.getConfig().getStringList("rtp.blacklist-blocks")) {
      Material material = Material.matchMaterial(name);
      if (material != null) out.add(material);
    }
    return out;
  }

  private boolean blockedBiome(Biome biome) {
    String key = biome.getKey().getKey();
    return plugin.getConfig().getStringList("rtp.blacklist-biomes").stream()
        .anyMatch(name -> name.equalsIgnoreCase(key));
  }

  // ------------------------------------------------------------------ validation

  /** Rejects values that would break the search, so a bad edit never reaches the running module. */
  public static void validate(ConfigurationSection c) {
    bounded(c, "rtp.warmup-seconds", 0, 60);
    bounded(c, "rtp.invulnerable-seconds", 0, 60);
    bounded(c, "rtp.cooldown-seconds", 0, 86400);
    bounded(c, "rtp.cost", 0, 1_000_000_000);
    bounded(c, "rtp.border-margin", 0, 1000);
    bounded(c, "rtp.queue.size", 0, 64);
    bounded(c, "rtp.queue.interval-ticks", 1, 1200);
    decimal(c, "rtp.cancel-distance", .1, 5);
    var groups = c.getConfigurationSection("rtp.cooldown-groups");
    if (groups != null)
      for (String group : groups.getKeys(false)) {
        if (!group.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("errors.config");
        bounded(c, "rtp.cooldown-groups." + group, 0, 86400);
      }
    var map = c.getConfigurationSection("rtp.world-map");
    if (map != null)
      for (String from : map.getKeys(false))
        if (from.isBlank() || !(map.get(from) instanceof String to) || to.isBlank())
          throw new IllegalArgumentException("errors.config");
    for (String name : c.getStringList("rtp.blacklist-blocks"))
      try {
        Material.valueOf(name);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("errors.config", e);
      }
    for (String biome : c.getStringList("rtp.blacklist-biomes"))
      if (!biome.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("errors.config");
    validateEffects(c);
    var worlds = c.getConfigurationSection("rtp.worlds");
    if (worlds != null)
      for (String world : worlds.getKeys(false)) validateWorld(c, "rtp.worlds." + world + ".");
    if (c.isConfigurationSection("rtp.default-world")) validateWorld(c, "rtp.default-world.");
  }

  private static void validateEffects(ConfigurationSection c) {
    bounded(c, "rtp.effects.fade-ticks", 0, 200);
    bounded(c, "rtp.effects.arrive-fade-ticks", 0, 200);
    for (String key : List.of("rtp.effects.fade-effect"))
      if (c.contains(key) && fadeEffect(c.getString(key, "")) == null)
        throw new dev.lovelace.loveessential.config.ConfigError(key + ": BLINDNESS или DARKNESS");
    for (String key : List.of("rtp.effects.start-sound", "rtp.effects.arrive-sound"))
      if (c.contains(key) && !c.getString(key, "").isBlank() && sound(c.getString(key, "")) == null)
        throw new dev.lovelace.loveessential.config.ConfigError(key + ": неизвестный звук «" + c.getString(key) + "»");
    String particle = c.getString("rtp.effects.arrive-particles", "");
    if (!particle.isBlank()) {
      try {
        Particle.valueOf(particle.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new dev.lovelace.loveessential.config.ConfigError("rtp.effects.arrive-particles: неизвестные частицы «" + particle + "»");
      }
    }
  }

  private static void validateWorld(ConfigurationSection c, String base) {
    if (!(c.get(base + "enabled", false) instanceof Boolean))
      throw new dev.lovelace.loveessential.config.ConfigError(base + "enabled: ожидается true или false");
    decimal(c, base + "center-x", -30_000_000, 30_000_000);
    decimal(c, base + "center-z", -30_000_000, 30_000_000);
    decimal(c, base + "min-radius", 0, 100_000);
    decimal(c, base + "max-radius", 1, 100_000);
    if (c.getDouble(base + "min-radius", 200) >= c.getDouble(base + "max-radius", 2000))
      throw new dev.lovelace.loveessential.config.ConfigError(base + "min-radius должен быть меньше max-radius");
    String shape = c.getString(base + "shape", "circle");
    if (!shape.equals("circle") && !shape.equals("square"))
      throw new dev.lovelace.loveessential.config.ConfigError(base + "shape: circle или square");
    bounded(c, base + "max-attempts", 1, 200);
    bounded(c, base + "min-y", -2032, 2031);
    bounded(c, base + "max-y", -2032, 2031);
    if (c.getInt(base + "min-y", 60) >= c.getInt(base + "max-y", 220))
      throw new dev.lovelace.loveessential.config.ConfigError(base + "min-y должен быть меньше max-y");
  }

  private static void bounded(ConfigurationSection c, String path, double min, double max) {
    if (!c.contains(path)) return;
    Object value = c.get(path);
    if (!(value instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() < min
        || n.doubleValue() > max
        || n.doubleValue() != Math.rint(n.doubleValue()))
      throw dev.lovelace.loveessential.config.ConfigError.range(path, value, min, max);
  }

  private static void decimal(ConfigurationSection c, String path, double min, double max) {
    if (!c.contains(path)) return;
    Object value = c.get(path);
    if (!(value instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() < min
        || n.doubleValue() > max) throw dev.lovelace.loveessential.config.ConfigError.range(path, value, min, max);
  }

  // ------------------------------------------------------------------ effects

  /** Fade used for the warmup and the arrival: BLINDNESS (black) or DARKNESS (pulsing dark). */
  static PotionEffectType fadeEffect(String name) {
    return switch (name == null ? "" : name.trim().toUpperCase(Locale.ROOT)) {
      case "BLINDNESS" -> PotionEffectType.BLINDNESS;
      case "DARKNESS" -> PotionEffectType.DARKNESS;
      default -> null;
    };
  }

  /** A sound by its Minecraft name (BLOCK_BEACON_ACTIVATE or minecraft:block.beacon.activate), or null. */
  static Sound sound(String name) {
    if (name == null || name.isBlank()) return null;
    String key = name.trim().toLowerCase(Locale.ROOT).replace('_', '.');
    if (key.contains(":")) key = key.substring(key.indexOf(':') + 1);
    return Registry.SOUNDS.get(NamespacedKey.minecraft(key));
  }

  private boolean effectsOn() {
    return plugin.getConfig().getBoolean("rtp.effects.enabled", true);
  }

  private void play(Player p, String path) {
    var sound = sound(plugin.getConfig().getString("rtp.effects." + path, ""));
    if (sound != null) p.playSound(p.getLocation(), sound, 1f, 1f);
  }

  /** Warmup starts: a sound and a title with the countdown. */
  private void effectsStart(Player p, int seconds) {
    if (!effectsOn()) return;
    play(p, "start-sound");
    if (plugin.getConfig().getBoolean("rtp.effects.title", true))
      p.showTitle(
          net.kyori.adventure.title.Title.title(
              lang.render("rtp.warming-title"),
              lang.render("rtp.warming-subtitle", Map.of("seconds", Integer.toString(seconds))),
              net.kyori.adventure.title.Title.Times.times(
                  java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(Math.max(1, seconds)), java.time.Duration.ofMillis(300))));
  }

  /** Fade to black for the last ticks of the warmup; it also covers the arrival fade. */
  private void effectsFade(Player p, Session session, long ticksLeft) {
    if (!effectsOn() || session.faded) return;
    int fade = plugin.getConfig().getInt("rtp.effects.fade-ticks", 40);
    if (fade <= 0 || ticksLeft > fade) return;
    var type = fadeEffect(plugin.getConfig().getString("rtp.effects.fade-effect", "BLINDNESS"));
    if (type == null) return;
    int after = Math.max(0, plugin.getConfig().getInt("rtp.effects.arrive-fade-ticks", 40));
    p.addPotionEffect(new PotionEffect(type, (int) ticksLeft + after + 10, 0, false, false, false));
    session.faded = true;
  }

  private void removeFade(Player p) {
    p.removePotionEffect(PotionEffectType.BLINDNESS);
    p.removePotionEffect(PotionEffectType.DARKNESS);
  }

  /** Arrival: sound, portal particles and a title with the coordinates. */
  private void effectsArrived(Player p) {
    if (!effectsOn()) return;
    play(p, "arrive-sound");
    String particle = plugin.getConfig().getString("rtp.effects.arrive-particles", "PORTAL");
    if (!particle.isBlank()) {
      try {
        p.getWorld().spawnParticle(Particle.valueOf(particle.toUpperCase(Locale.ROOT)), p.getLocation().add(0, 1, 0), 60, .4, .8, .4, .2);
      } catch (IllegalArgumentException ignored) {
        // Checked when the settings are loaded.
      }
    }
    var at = p.getLocation();
    var vars = Map.of("x", Integer.toString(at.getBlockX()), "y", Integer.toString(at.getBlockY()), "z", Integer.toString(at.getBlockZ()));
    if (plugin.getConfig().getBoolean("rtp.effects.title", true))
      p.showTitle(net.kyori.adventure.title.Title.title(lang.render("rtp.arrived-title"), lang.render("rtp.arrived-subtitle", vars)));
    p.sendActionBar(lang.render("rtp.arrived-actionbar", vars));
  }
}
