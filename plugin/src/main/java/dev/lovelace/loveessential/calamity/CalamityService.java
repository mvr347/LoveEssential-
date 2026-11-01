package dev.lovelace.loveessential.calamity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.calamity.CalamityRules.Severity;
import dev.lovelace.loveessential.calamity.CalamitySettings.Mode;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * World calamities (bugs, infertile land, livestock plague, undead invasion) and the two diseases
 * they spread (hunger syndrome, scurvy). All penalties are probabilities taken from
 * {@link CalamityRules}; this class only wires them to Bukkit events and keeps the state: which
 * calamity runs, how long it has left, how much threat each kind has gathered, who is sick.
 *
 * <p>Time only passes while somebody is online, so a calamity cannot run out unseen.
 */
public final class CalamityService implements Listener, AutoCloseable {
  /** The four world calamities. */
  public enum Type {
    BUGS("bugs", BossBar.Color.GREEN),
    INFERTILE("infertile", BossBar.Color.YELLOW),
    PLAGUE("plague", BossBar.Color.RED),
    UNDEAD("undead", BossBar.Color.PURPLE);

    public final String key;
    final BossBar.Color color;

    Type(String key, BossBar.Color color) {
      this.key = key;
      this.color = color;
    }

    public static Type of(String name) {
      if (name == null) return null;
      for (Type t : values()) if (t.key.equalsIgnoreCase(name)) return t;
      return null;
    }
  }

  /** The two diseases. */
  public enum Disease {
    HUNGER("hunger"),
    SCURVY("scurvy");

    public final String key;

    Disease(String key) {
      this.key = key;
    }

    public static Disease of(String name) {
      if (name == null) return null;
      for (Disease d : values()) if (d.key.equalsIgnoreCase(name)) return d;
      return null;
    }
  }

  public enum Phase {
    IDLE,
    OMEN,
    ACTIVE
  }

  /** World state as stored. Plain fields so Gson reads and writes it. */
  static final class Saved {
    String phase = Phase.IDLE.name();
    String type = "";
    String severity = Severity.NORMAL.name();
    long elapsed;
    long duration;
    long cooldown;
    long dayClock;
    long clockAt;
    long nextAutomaticAt;
    int quietDays;
    int killCredit;
    Map<String, Double> threat = new HashMap<>();
    Set<String> serverWide = new HashSet<>();
    List<String> history = new ArrayList<>();

    void repair() {
      if (threat == null) threat = new HashMap<>();
      if (serverWide == null) serverWide = new HashSet<>();
      if (history == null) history = new ArrayList<>();
      if (phase == null) phase = Phase.IDLE.name();
      if (severity == null) severity = Severity.NORMAL.name();
      if (type == null) type = "";
    }
  }

  /** Per-player illness state as stored; the values are play-time ticks, 0 meaning "not sick". */
  static final class Health {
    long hunger;
    long scurvy;
    long freshAt;
    long hungerImmune;
    long scurvyImmune;
    int cure;
  }

  private static final Gson GSON = new Gson();
  private static final UUID WORLD_ID = new UUID(0L, 0xCA1A0001L);
  private static final String WORLD_KEY = "calamity.world";
  private static final String HEALTH_KEY = "calamity.health";
  private static final int HISTORY = 20;

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private java.util.function.LongSupplier nowMillis = System::currentTimeMillis;
  private final Random random = new Random();
  private final NamespacedKey invasionKey;
  private final Set<UUID> invasionMobs = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Health> health = new HashMap<>();
  private final Map<UUID, Deque<Material>> eaten = new HashMap<>();
  private final Map<UUID, long[]> credit = new HashMap<>();
  private final Set<UUID> barShown = new HashSet<>();
  private final Map<Material, Integer> harvested = new EnumMap<>(Material.class);
  private Cache<Long, Boolean> protection;

  private Saved saved = new Saved();
  private CalamitySettings settings;
  private BossBar bar;
  private BukkitTask task;
  private boolean loaded;
  private boolean closed;
  private boolean dirty;
  private int harvestTotal;
  private int bornToday;
  private int fertilizedToday;
  private int nightDeaths;
  private int ticks;
  private int waveClock;
  private Phase phase = Phase.IDLE;
  private Type type;
  private Severity severity = Severity.NORMAL;
  private double strength;

  public CalamityService(
      LoveEssentialPlugin plugin, MainThread main, SqliteStore store, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.invasionKey = new NamespacedKey(plugin, "invasion");
  }

  // ---------------------------------------------------------------- lifecycle

  public void start() {
    settings = new CalamitySettings(plugin.getConfig());
    protection =
        Caffeine.newBuilder()
            .maximumSize(8192)
            .expireAfterWrite(Duration.ofSeconds(settings.i("protection.cache-seconds", 30)))
            .build();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    store
        .profile(WORLD_ID, WORLD_KEY)
        .whenComplete(
            (value, error) ->
                main.execute(
                    () -> {
                      if (closed) return;
                      if (error != null)
                        plugin.getLogger().warning("calamities: state not loaded: " + error);
                      else value.ifPresent(this::restore);
                      loaded = true;
                    }));
    Bukkit.getOnlinePlayers().forEach(this::loadHealth);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
  }

  private void restore(String json) {
    try {
      var read = GSON.fromJson(json, Saved.class);
      if (read == null) return;
      read.repair();
      saved = read;
      phase = Phase.valueOf(saved.phase);
      type = Type.of(saved.type);
      severity = Severity.valueOf(saved.severity);
      if (type == null) phase = Phase.IDLE;
    } catch (RuntimeException e) {
      plugin.getLogger().warning("calamities: stored state is unreadable, starting fresh: " + e);
      saved = new Saved();
      phase = Phase.IDLE;
      type = null;
    }
  }

  @Override
  public void close() {
    closed = true;
    if (task != null) task.cancel();
    hideBar();
    removeInvasionMobs();
    org.bukkit.event.HandlerList.unregisterAll(this);
    if (loaded) {
      try {
        store
            .profile(WORLD_ID, WORLD_KEY, GSON.toJson(snapshot()))
            .get(2, TimeUnit.SECONDS);
      } catch (Exception e) {
        plugin.getLogger().warning("calamities: state not saved on shutdown: " + e);
      }
    }
  }

  private Saved snapshot() {
    saved.phase = phase.name();
    saved.type = type == null ? "" : type.key;
    saved.severity = severity.name();
    return saved;
  }

  private void flush() {
    dirty = false;
    store
        .profile(WORLD_ID, WORLD_KEY, GSON.toJson(snapshot()))
        .exceptionally(
            error -> {
              plugin.getLogger().warning("calamities: state not saved: " + error);
              return null;
            });
  }

  private boolean on() {
    return !closed && loaded && plugin.ready() && plugin.module("calamities");
  }

  // ---------------------------------------------------------------- timer

  private List<Player> eligible() {
    var out = new ArrayList<Player>();
    for (Player p : Bukkit.getOnlinePlayers())
      if (p.getGameMode() != GameMode.SPECTATOR && settings.worldAllowed(p.getWorld().getName()))
        out.add(p);
    return out;
  }

  private void tick() {
    if (closed || !plugin.ready()) return;
    try {
      settings = new CalamitySettings(plugin.getConfig());
    } catch (RuntimeException e) {
      // A broken edit keeps the last good settings until it is fixed.
    }
    if (!on()) {
      hideBar();
      if (!invasionMobs.isEmpty()) removeInvasionMobs();
      return;
    }
    var players = eligible();
    long now = nowMillis.getAsLong();
    if (saved.clockAt == 0) {
      saved.clockAt = now;
      saved.nextAutomaticAt = CalamityClock.deadline(now, settings.d("cooldown-days", 14));
      dirty = true;
    }
    long elapsed = CalamityClock.elapsedTicks(saved.clockAt, now);
    // Retain sub-tick milliseconds and do not replay time after a backwards clock adjustment.
    if (now >= saved.clockAt) saved.clockAt += elapsed * 50;
    ticks++;
    saved.dayClock += elapsed;
    switch (phase) {
      case IDLE -> {
        if (saved.cooldown > 0) saved.cooldown = Math.max(0, saved.cooldown - elapsed);
      }
      case OMEN -> {
        saved.elapsed += elapsed;
        if (saved.elapsed >= saved.duration) {
          long overdue = saved.elapsed - saved.duration;
          begin(type, severity, 0);
          saved.nextAutomaticAt = CalamityClock.deadline(now - overdue * 50, settings.d("cooldown-days", 14));
          saved.elapsed = overdue;
          if (saved.elapsed >= saved.duration) end(saved.elapsed - saved.duration);
        }
      }
      case ACTIVE -> {
        saved.elapsed += elapsed;
        if (saved.elapsed >= saved.duration) end(saved.elapsed - saved.duration);
      }
    }
    if (saved.dayClock >= CalamityClock.DAY_TICKS && !players.isEmpty()) {
      saved.dayClock %= CalamityClock.DAY_TICKS;
      daily(players); // At most one decision after an offline period, never a catch-up burst.
    }
    strength =
        phase == Phase.ACTIVE
            ? CalamityRules.strength(
                CalamityRules.progress(saved.elapsed, saved.duration),
                severity,
                CalamityRules.onlineScale(players.size(), settings.i("online-full-strength", 8)))
            : 0;
    updateBar(players);
    if (phase == Phase.ACTIVE && type == Type.UNDEAD) wave(players);
    if (ticks % 5 == 0) weakness(players);
    if (ticks % 60 == 0 || dirty && phase != Phase.ACTIVE) flush();
  }

  // ---------------------------------------------------------------- calamity flow

  private void daily(List<Player> players) {
    // 1. What the players did over the last day raises the threat of the matching calamity.
    double decay = settings.d("threat-decay-percent", 15);
    Map<Type, Double> gain = new EnumMap<>(Type.class);
    if (harvestTotal >= settings.i("threat.bugs.min-harvest", 200)) {
      int top = harvested.values().stream().mapToInt(Integer::intValue).max().orElse(0);
      if (top >= harvestTotal * settings.d("threat.bugs.monoculture-share", 0.9))
        gain.put(Type.BUGS, settings.d("threat.bugs.gain", 40));
    }
    int perDay = settings.i("threat.infertile.harvest-per-day", 600);
    if (harvestTotal > perDay && fertilizedToday * 4 < harvestTotal)
      gain.put(Type.INFERTILE, settings.d("threat.infertile.gain", 40));
    if (bornToday > settings.i("threat.plague.breeds-per-day", 40) || crowdedChunks() > 0)
      gain.put(Type.PLAGUE, settings.d("threat.plague.gain", 40));
    if (nightDeaths > 0)
      gain.put(Type.UNDEAD, nightDeaths * settings.d("threat.undead.night-death-gain", 15));
    for (Type t : Type.values())
      saved.threat.put(
          t.key,
          CalamityRules.threat(saved.threat.getOrDefault(t.key, 0.0), gain.getOrDefault(t, 0.0), decay));
    harvested.clear();
    harvestTotal = 0;
    bornToday = 0;
    fertilizedToday = 0;
    nightDeaths = 0;
    if (phase == Phase.IDLE) saved.quietDays++;

    // 2. Diseases spread while a carrier calamity runs and from a diet without fresh food.
    for (Player p : players) infections(p);

    // 3. A new calamity may be announced.
    if (phase == Phase.IDLE && saved.cooldown <= 0 && nowMillis.getAsLong() >= saved.nextAutomaticAt) {
      var kinds = new ArrayList<>(Arrays.asList(Type.values()));
      Collections.shuffle(kinds, random);
      for (Type t : kinds) {
        if (!settings.typeEnabled(t)) continue;
        double threat = saved.threat.getOrDefault(t.key, 0.0);
        double chance =
            CalamityRules.dailyStartChance(
                threat, settings.d("base-daily-chance", 0.08), settings.d("threat-weight", 0.5));
        if (CalamityRules.roll(chance, random)) {
          omen(
              t,
              CalamityRules.severity(
                  threat, saved.quietDays, settings.d("heavy-after-quiet-days", 12)));
          break;
        }
      }
    }
    dirty = true;
  }

  private int crowdedChunks() {
    int limit = settings.i("threat.plague.crowd-per-chunk", 24);
    int crowded = 0;
    for (World w : Bukkit.getWorlds()) {
      if (!settings.worldAllowed(w.getName())) continue;
      Map<String, Integer> counts = new HashMap<>();
      for (Animals a : w.getEntitiesByClass(Animals.class))
        counts.merge(
            a.getLocation().getBlockX() / 16 + ":" + a.getLocation().getBlockZ() / 16 + ":" + a.getType(),
            1,
            Integer::sum);
      for (int n : counts.values()) if (n > limit) crowded++;
    }
    return crowded;
  }

  private void omen(Type t, Severity s) {
    long omenTicks = Math.round(settings.d("omen-minutes", settings.d("omen-days", 1) * 20) * 1200);
    if (omenTicks < 20) {
      begin(t, s, 0);
      return;
    }
    phase = Phase.OMEN;
    type = t;
    severity = s;
    saved.elapsed = 0;
    saved.duration = omenTicks;
    history("omen " + t.key + " " + s.name());
    broadcast("calamity.omen", Map.of("name", name(t), "time", time(omenTicks)));
    dirty = true;
  }

  /** Starts the calamity; {@code override} is a duration in ticks, or 0 for the configured one. */
  private void begin(Type t, Severity s, long override) {
    phase = Phase.ACTIVE;
    saved.clockAt = nowMillis.getAsLong();
    saved.nextAutomaticAt = CalamityClock.deadline(nowMillis.getAsLong(), settings.d("cooldown-days", 14));
    type = t;
    severity = s;
    saved.elapsed = 0;
    saved.duration = override > 0 ? override : settings.durationTicks(t);
    saved.quietDays = 0;
    saved.killCredit = 0;
    waveClock = 0;
    history("start " + t.key + " " + s.name());
    broadcast(
        "calamity.started",
        Map.of("name", name(t), "severity", lang.text("calamity.severity-" + s.name().toLowerCase(Locale.ROOT)), "time", time(saved.duration)));
    dirty = true;
  }

  private void end() {
    end(0);
  }

  /** Carry offline time past the event's end into its cooldown, rather than restarting the pause. */
  private void end(long overdueTicks) {
    Type finished = type;
    phase = Phase.IDLE;
    saved.elapsed = 0;
    saved.duration = 0;
    saved.cooldown = Math.max(0, Math.round(settings.d("cooldown-days", 14) * CalamityClock.DAY_TICKS) - overdueTicks);
    saved.quietDays = 0;
    strength = 0;
    if (finished != null) {
      history("end " + finished.key);
      for (Type t : Type.values())
        saved.threat.put(
            t.key, t == finished ? 0.0 : saved.threat.getOrDefault(t.key, 0.0) * 0.7);
      // Dead livestock draw the undead.
      if (finished == Type.PLAGUE)
        saved.threat.put(Type.UNDEAD.key, CalamityRules.threat(saved.threat.getOrDefault(Type.UNDEAD.key, 0.0), 20, 0));
      broadcast("calamity.ended", Map.of("name", name(finished)));
    }
    removeInvasionMobs();
    type = null;
    hideBar();
    flush();
  }

  private void history(String line) {
    saved.history.add(Instant.now().toString().substring(0, 16).replace('T', ' ') + " " + line);
    while (saved.history.size() > HISTORY) saved.history.remove(0);
  }

  // ---------------------------------------------------------------- announcements and bar

  String name(Type t) {
    return lang.text("calamity.type-" + t.key);
  }

  private String name(Disease d) {
    return lang.text("calamity.disease-" + d.key);
  }

  /** Remaining real days, hours and minutes. */
  private long remainingCooldownTicks() {
    return Math.max(saved.cooldown, Math.max(0, saved.nextAutomaticAt - nowMillis.getAsLong()) / 50);
  }

  String time(long ticks) {
    long days = ticks / CalamityClock.DAY_TICKS;
    long hours = (ticks % CalamityClock.DAY_TICKS) / 72000;
    long minutes = (ticks % 72000) / 1200;
    var parts = new ArrayList<String>();
    if (days > 0) parts.add(lang.text("calamity.time-day").replace("<n>", Long.toString(days)));
    if (hours > 0) parts.add(lang.text("calamity.time-hour").replace("<n>", Long.toString(hours)));
    if (minutes > 0) parts.add(lang.text("calamity.time-minute").replace("<n>", Long.toString(minutes)));
    return parts.isEmpty() ? lang.text("calamity.time-less") : String.join(" ", parts);
  }

  private void broadcast(String key, Map<String, String> vars) {
    for (Player p : eligible()) lang.send(p, key, vars);
  }

  private void updateBar(List<Player> players) {
    if (phase == Phase.IDLE || type == null) {
      hideBar();
      return;
    }
    long left = Math.max(0, saved.duration - saved.elapsed);
    boolean active = phase == Phase.ACTIVE;
    Component title =
        lang.render(
            active ? "calamity.bar-active" : "calamity.bar-omen",
            Map.of("name", name(type), "time", time(left)));
    float progress =
        (float)
            (active
                ? 1 - CalamityRules.progress(saved.elapsed, saved.duration)
                : CalamityRules.progress(saved.elapsed, saved.duration));
    var color = active ? type.color : BossBar.Color.WHITE;
    if (bar == null) bar = BossBar.bossBar(title, progress, color, BossBar.Overlay.PROGRESS);
    else {
      bar.name(title);
      bar.progress(progress);
      bar.color(color);
    }
    var present = new HashSet<UUID>();
    for (Player p : players) {
      present.add(p.getUniqueId());
      if (barShown.add(p.getUniqueId())) p.showBossBar(bar);
    }
    for (var it = barShown.iterator(); it.hasNext(); ) {
      UUID id = it.next();
      if (present.contains(id)) continue;
      var p = Bukkit.getPlayer(id);
      if (p != null) p.hideBossBar(bar);
      it.remove();
    }
  }

  private void hideBar() {
    if (bar == null) return;
    for (UUID id : barShown) {
      var p = Bukkit.getPlayer(id);
      if (p != null) p.hideBossBar(bar);
    }
    barShown.clear();
    bar = null;
  }

  // ---------------------------------------------------------------- penalties

  private boolean here(Location at) {
    return on() && settings.worldAllowed(at.getWorld().getName());
  }

  /** Effective penalty (a fraction) of one kind for the running calamity of {@code t}; 0 otherwise. */
  private double penalty(Type t, String key, Location at) {
    if (phase != Phase.ACTIVE || type != t || !here(at)) return 0;
    double base = settings.base(t, key);
    if (base <= 0) return 0;
    double shield = shielded(t, at) ? settings.d("protection.share", 0.5) : 0;
    return CalamityRules.penalty(base, strength, shield, settings.d("cap-percent", 75));
  }

  /** Whether something that fights this calamity stands near: a lit campfire, a composter, hay. */
  private boolean shielded(Type t, Location at) {
    long key =
        at.getWorld().getUID().hashCode() * 31L * 31L
            + (((long) (at.getBlockX() >> 3)) * 73856093L
                ^ ((long) (at.getBlockY() >> 3)) * 19349663L
                ^ ((long) (at.getBlockZ() >> 3)) * 83492791L)
            + t.ordinal();
    Boolean cached = protection.getIfPresent(key);
    if (cached != null) return cached;
    boolean found = scan(t, at, settings.i("protection.radius", 6));
    protection.put(key, found);
    return found;
  }

  private boolean scan(Type t, Location at, int r) {
    World w = at.getWorld();
    int cx = at.getBlockX(), cy = at.getBlockY(), cz = at.getBlockZ();
    for (int x = cx - r; x <= cx + r; x++)
      for (int z = cz - r; z <= cz + r; z++) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
        for (int y = cy - 3; y <= cy + 3; y++) {
          Block b = w.getBlockAt(x, y, z);
          Material m = b.getType();
          switch (t) {
            case BUGS -> {
              if ((m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE)
                  && b.getBlockData() instanceof Lightable l
                  && l.isLit()) return true;
            }
            case INFERTILE -> {
              if (m == Material.COMPOSTER
                  && b.getBlockData() instanceof Levelled l
                  && l.getLevel() >= 4) return true;
            }
            case PLAGUE -> {
              if (m == Material.HAY_BLOCK) return true;
            }
            default -> {
              return false;
            }
          }
        }
      }
    return false;
  }

  private boolean crop(Material m) {
    for (var set : settings.crops.values()) if (set.contains(m)) return true;
    return false;
  }

  private static boolean mature(BlockState state) {
    return !(state.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge();
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void grow(BlockGrowEvent event) {
    if (phase != Phase.ACTIVE || (type != Type.BUGS && type != Type.INFERTILE)) return;
    Block block = event.getBlock();
    if (!settings.crops.get(type).contains(block.getType())) return;
    if (CalamityRules.roll(penalty(type, "growth", block.getLocation()), random))
      event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void harvest(BlockDropItemEvent event) {
    if (!on()) return;
    BlockState state = event.getBlockState();
    Material material = state.getType();
    if (!crop(material) || !mature(state)) return;
    harvested.merge(material, 1, Integer::sum);
    harvestTotal++;
    if (phase != Phase.ACTIVE || (type != Type.BUGS && type != Type.INFERTILE)) return;
    if (!settings.crops.get(type).contains(material)) return;
    Player player = event.getPlayer();
    double fraction =
        penalty(type, "crops", state.getLocation())
            * CalamityRules.newcomerFactor(
                player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200L,
                settings.i("newcomer-minutes", 300));
    if (!(fraction > 0)) return;
    int before = 0, after = 0;
    Item first = null;
    for (Iterator<Item> it = event.getItems().iterator(); it.hasNext(); ) {
      Item drop = it.next();
      ItemStack stack = drop.getItemStack();
      before += stack.getAmount();
      int left = CalamityRules.harvest(stack.getAmount(), fraction, 0, random);
      if (first == null) first = drop;
      if (left <= 0) {
        it.remove();
        continue;
      }
      after += left;
      stack.setAmount(left);
      drop.setItemStack(stack);
    }
    int floor = Math.min(settings.i("harvest-minimum", 1), before);
    if (after < floor && first != null) {
      // The farm must always be able to replant: put the minimum back.
      ItemStack stack = first.getItemStack();
      boolean listed = event.getItems().contains(first);
      stack.setAmount((listed ? stack.getAmount() : 0) + floor - after);
      first.setItemStack(stack);
      if (!listed) event.getItems().add(first);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void fertilize(BlockFertilizeEvent event) {
    if (on()) fertilizedToday++;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void breed(EntityBreedEvent event) {
    if (!on()) return;
    bornToday++;
    if (phase != Phase.ACTIVE || (type != Type.INFERTILE && type != Type.PLAGUE)) return;
    if (CalamityRules.roll(penalty(type, "breeding", event.getEntity().getLocation()), random)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void spawn(CreatureSpawnEvent event) {
    if (phase != Phase.ACTIVE || type != Type.PLAGUE) return;
    if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
        || !(event.getEntity() instanceof Animals)) return;
    if (CalamityRules.roll(penalty(type, "natural-spawn", event.getLocation()), random))
      event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void drops(EntityDeathEvent event) {
    LivingEntity dead = event.getEntity();
    if (dead.getPersistentDataContainer().has(invasionKey, PersistentDataType.BYTE)) {
      invasionMobs.remove(dead.getUniqueId());
      invasionKill(dead.getKiller());
      return;
    }
    if (phase != Phase.ACTIVE || (type != Type.INFERTILE && type != Type.PLAGUE)) return;
    if (!(dead instanceof Animals)) return;
    double fraction = penalty(type, "meat", dead.getLocation());
    if (!(fraction > 0)) return;
    for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
      ItemStack stack = it.next();
      if (!settings.meat.contains(stack.getType())) continue;
      int left = CalamityRules.harvest(stack.getAmount(), fraction, 0, random);
      if (left <= 0) it.remove();
      else stack.setAmount(left);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void died(PlayerDeathEvent event) {
    if (!on()) return;
    Player p = event.getEntity();
    if (!(p.getLastDamageCause() instanceof EntityDamageByEntityEvent hit)
        || !(hit.getDamager() instanceof Monster)) return;
    long time = p.getWorld().getTime();
    if (time >= 12542 && time < 23000) nightDeaths++;
  }

  // ---------------------------------------------------------------- undead invasion

  private void wave(List<Player> players) {
    if (++waveClock < settings.i("invasion.wave-interval-seconds", 150)) return;
    waveClock = 0;
    invasionMobs.removeIf(id -> Bukkit.getEntity(id) == null);
    for (Player p : players) {
      if (settings.b("invasion.night-only", true)) {
        long time = p.getWorld().getTime();
        if (time < 12542 || time >= 23000) continue;
      }
      spawnAround(p);
    }
  }

  private void spawnAround(Player p) {
    if (settings.invaders.isEmpty()) return;
    int cap = Math.max(1, (int) Math.round(settings.i("invasion.per-player-cap", 6) * Math.min(1.0, strength)));
    int want = Math.max(1, (int) Math.round(settings.i("invasion.wave-size", 2) * strength));
    int near = 0;
    for (Entity e : p.getNearbyEntities(48, 24, 48))
      if (e.getPersistentDataContainer().has(invasionKey, PersistentDataType.BYTE)) near++;
    int global = settings.i("invasion.global-cap", 60);
    for (int i = 0; i < want && near < cap && invasionMobs.size() < global; i++) {
      Location at = spot(p);
      if (at == null) continue;
      EntityType kind = settings.invaders.get(random.nextInt(settings.invaders.size()));
      Entity mob = p.getWorld().spawnEntity(at, kind, CreatureSpawnEvent.SpawnReason.CUSTOM);
      mob.setPersistent(false);
      mob.getPersistentDataContainer().set(invasionKey, PersistentDataType.BYTE, (byte) 1);
      if (mob instanceof Mob m) m.setTarget(p);
      invasionMobs.add(mob.getUniqueId());
      near++;
    }
  }

  private Location spot(Player p) {
    World w = p.getWorld();
    double min = settings.d("invasion.min-distance", 20), max = settings.d("invasion.max-distance", 30);
    for (int attempt = 0; attempt < 8; attempt++) {
      double angle = random.nextDouble() * Math.PI * 2;
      double distance = min + random.nextDouble() * (max - min);
      int x = (int) Math.floor(p.getLocation().getX() + Math.cos(angle) * distance);
      int z = (int) Math.floor(p.getLocation().getZ() + Math.sin(angle) * distance);
      if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
      int y = w.getHighestBlockYAt(x, z);
      Block ground = w.getBlockAt(x, y, z);
      if (!ground.getType().isSolid()
          || !w.getBlockAt(x, y + 1, z).isPassable()
          || !w.getBlockAt(x, y + 2, z).isPassable()) continue;
      Location at = new Location(w, x + 0.5, y + 1, z + 0.5);
      if (!settings.b("invasion.spawn-in-claims", false) && love.claimed(at)) continue;
      return at;
    }
    return null;
  }

  private void invasionKill(Player killer) {
    if (killer == null || phase != Phase.ACTIVE || type != Type.UNDEAD) return;
    long now = nowMillis.getAsLong();
    long[] window = credit.computeIfAbsent(killer.getUniqueId(), id -> new long[] {now, 0});
    if (now - window[0] > 3_600_000L) {
      window[0] = now;
      window[1] = 0;
    }
    if (window[1] >= settings.i("invasion.credit-per-player-hour", 60)) return;
    window[1]++;
    love.stat(killer, "calamity_kills", 1);
    if (++saved.killCredit < settings.i("invasion.kills-per-step", 100)) return;
    saved.killCredit = 0;
    long cut = Math.round(saved.duration * settings.d("invasion.step-percent", 10) / 100.0);
    saved.duration = Math.max(saved.elapsed + 20, saved.duration - cut);
    broadcast("calamity.shortened", Map.of());
    dirty = true;
  }

  private void removeInvasionMobs() {
    for (UUID id : invasionMobs) {
      Entity e = Bukkit.getEntity(id);
      if (e != null) e.remove();
    }
    invasionMobs.clear();
  }

  // ---------------------------------------------------------------- diseases

  private void loadHealth(Player p) {
    UUID id = p.getUniqueId();
    store
        .profile(id, HEALTH_KEY)
        .whenComplete(
            (value, error) ->
                main.execute(
                    () -> {
                      if (closed || Bukkit.getPlayer(id) == null || health.containsKey(id)) return;
                      Health h = null;
                      if (error == null && value.isPresent())
                        try {
                          h = GSON.fromJson(value.get(), Health.class);
                        } catch (RuntimeException ignored) {
                          // An unreadable record starts fresh.
                        }
                      if (h == null) h = new Health();
                      health.put(id, h);
                      var online = Bukkit.getPlayer(id);
                      // Nobody starts with scurvy: the clock of the last fresh meal begins now.
                      if (online != null && h.freshAt == 0) {
                        h.freshAt = playtime(online);
                        saveHealth(id, h);
                      }
                    }));
  }

  private void saveHealth(UUID id, Health h) {
    store
        .profile(id, HEALTH_KEY, GSON.toJson(h))
        .exceptionally(
            error -> {
              plugin.getLogger().warning("calamities: health not saved for " + id + ": " + error);
              return null;
            });
  }

  private static long playtime(Player p) {
    return p.getStatistic(Statistic.PLAY_ONE_MINUTE);
  }

  private boolean newcomer(Player p) {
    return playtime(p) / 1200L < settings.i("newcomer-minutes", 300);
  }

  /** Whether {@code p} suffers from {@code d} right now, whichever way the disease spreads. */
  public boolean sick(Player p, Disease d) {
    if (!on() || !settings.worldAllowed(p.getWorld().getName()) || newcomer(p)) return false;
    Mode mode = settings.modes.get(d);
    if (mode == Mode.SERVER) return saved.serverWide.contains(d.key);
    if (mode == Mode.WORLD)
      return phase == Phase.ACTIVE && type != null && settings.carriers.get(d).contains(type);
    Health h = health.get(p.getUniqueId());
    return h != null && (d == Disease.HUNGER ? h.hunger : h.scurvy) > 0;
  }

  private void infect(Player p, Disease d) {
    Health h = health.get(p.getUniqueId());
    if (h == null || newcomer(p) || settings.modes.get(d) != Mode.INDIVIDUAL) return;
    long now = playtime(p);
    if (d == Disease.HUNGER) {
      if (h.hunger > 0 || now < h.hungerImmune) return;
      h.hunger = Math.max(1, now);
    } else {
      if (h.scurvy > 0 || now < h.scurvyImmune) return;
      h.scurvy = Math.max(1, now);
    }
    h.cure = 0;
    saveHealth(p.getUniqueId(), h);
    lang.send(
        p,
        "calamity.infected",
        Map.of("name", name(d), "hint", lang.text("calamity.hint-" + d.key)));
  }

  private void cure(Player p, Disease d) {
    Health h = health.get(p.getUniqueId());
    if (h == null) return;
    long until = playtime(p) + Math.round(settings.d("diseases." + d.key + ".immune-days", 2) * CalamityRules.DAY_TICKS);
    if (d == Disease.HUNGER) {
      if (h.hunger == 0) return;
      h.hunger = 0;
      h.hungerImmune = until;
    } else {
      if (h.scurvy == 0) return;
      h.scurvy = 0;
      h.scurvyImmune = until;
    }
    h.cure = 0;
    saveHealth(p.getUniqueId(), h);
    lang.send(p, "calamity.cured", Map.of("name", name(d)));
  }

  /** Once a game day: carriers pass the disease on, a diet without fresh food brings scurvy. */
  private void infections(Player p) {
    Health h = health.get(p.getUniqueId());
    if (h == null) return;
    for (Disease d : Disease.values()) {
      if (phase == Phase.ACTIVE
          && type != null
          && settings.carriers.get(d).contains(type)
          && CalamityRules.roll(settings.d("diseases." + d.key + ".infect-chance-per-day", 10) / 100.0, random))
        infect(p, d);
    }
    long days = Math.round(settings.d("diseases.scurvy.no-fresh-days", 5) * CalamityRules.DAY_TICKS);
    if (playtime(p) - h.freshAt >= days) infect(p, Disease.SCURVY);
  }

  private void weakness(List<Player> players) {
    long after = Math.round(settings.d("diseases.scurvy.weakness-after-days", 3) * CalamityRules.DAY_TICKS);
    for (Player p : players) {
      Health h = health.get(p.getUniqueId());
      if (h == null || h.scurvy == 0 || !sick(p, Disease.SCURVY)) continue;
      if (playtime(p) - h.scurvy >= after)
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 140, 0, true, false, false));
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void exhaustion(EntityExhaustionEvent event) {
    if (event.getEntity() instanceof Player p && sick(p, Disease.HUNGER))
      event.setExhaustion(
          event.getExhaustion() * (float) settings.d("diseases.hunger.exhaustion-multiplier", 1.5));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void regain(EntityRegainHealthEvent event) {
    var reason = event.getRegainReason();
    if ((reason == EntityRegainHealthEvent.RegainReason.SATIATED
            || reason == EntityRegainHealthEvent.RegainReason.REGEN)
        && event.getEntity() instanceof Player p
        && sick(p, Disease.SCURVY)) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void ate(PlayerItemConsumeEvent event) {
    if (!on()) return;
    Player p = event.getPlayer();
    Health h = health.get(p.getUniqueId());
    if (h == null) return;
    Material m = event.getItem().getType();
    if (settings.freshFoods.contains(m)) {
      h.freshAt = playtime(p);
      if (h.scurvy > 0 && ++h.cure >= settings.i("diseases.scurvy.cure-count", 4)) cure(p, Disease.SCURVY);
      else saveHealth(p.getUniqueId(), h);
    }
    var meals = eaten.computeIfAbsent(p.getUniqueId(), id -> new ArrayDeque<>());
    meals.addLast(m);
    while (meals.size() > 12) meals.removeFirst();
    int same = 0;
    for (var it = meals.descendingIterator(); it.hasNext() && it.next() == m; ) same++;
    if (same >= settings.i("diseases.hunger.monotony-foods", 5) && random.nextBoolean())
      infect(p, Disease.HUNGER);
    int varied = settings.i("diseases.hunger.cure-different-foods", 3);
    if (h.hunger > 0 && meals.size() >= varied) {
      var recent = new ArrayList<>(meals).subList(meals.size() - varied, meals.size());
      if (new HashSet<>(recent).size() == varied) cure(p, Disease.HUNGER);
    }
    if (sick(p, Disease.HUNGER) && settings.doubleFoods.contains(m)) {
      int bonus = settings.i("diseases.hunger.bonus-food", 3);
      Bukkit.getScheduler()
          .runTask(plugin, () -> p.setFoodLevel(Math.min(20, p.getFoodLevel() + bonus)));
    }
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    if (!closed) loadHealth(event.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    health.remove(id);
    eaten.remove(id);
    credit.remove(id);
    barShown.remove(id);
  }

  // ---------------------------------------------------------------- commands

  private boolean admin(org.bukkit.command.CommandSender p) {
    return p.hasPermission("loveessential.calamities.admin");
  }

  private void requireAdmin(org.bukkit.command.CommandSender p) {
    if (!admin(p)) throw new IllegalArgumentException("errors.permission");
  }

  public void command(org.bukkit.command.CommandSender p, String[] args) {
    if (!plugin.module("calamities")) throw new IllegalArgumentException("errors.module-disabled");
    String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "info" -> info(p);
      case "start" -> {
        requireAdmin(p);
        startCommand(p, args);
      }
      case "stop" -> {
        requireAdmin(p);
        if (phase == Phase.IDLE) {
          lang.send(p, "calamity.admin-nothing");
          return;
        }
        end();
        lang.send(p, "calamity.admin-stopped");
      }
      case "threat" -> {
        requireAdmin(p);
        Type t = args.length > 1 ? Type.of(args[1]) : null;
        double value = number(args, 2, -1);
        if (t == null || value < 0 || value > 100) throw new IllegalArgumentException("calamity.usage");
        saved.threat.put(t.key, value);
        dirty = true;
        lang.send(p, "calamity.admin-threat", Map.of("name", name(t), "value", String.format(Locale.ROOT, "%.0f", value)));
      }
      case "disease" -> {
        requireAdmin(p);
        diseaseCommand(p, args);
      }
      case "simulate" -> {
        requireAdmin(p);
        Type t = args.length > 1 ? Type.of(args[1]) : null;
        if (t == null) throw new IllegalArgumentException("calamity.usage");
        Severity s = args.length > 2 ? severityOf(args[2]) : Severity.NORMAL;
        simulate(p, t, s);
      }
      case "log" -> {
        requireAdmin(p);
        if (saved.history.isEmpty()) lang.send(p, "calamity.admin-log-empty");
        for (String line : saved.history) lang.send(p, "calamity.admin-log-line", Map.of("line", line));
      }
      default -> throw new IllegalArgumentException("calamity.usage");
    }
  }

  private static double number(String[] args, int index, double fallback) {
    if (args.length <= index) return fallback;
    try {
      double v = Double.parseDouble(args[index].replace(',', '.'));
      return Double.isFinite(v) ? v : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private Severity severityOf(String word) {
    for (Severity s : Severity.values()) {
      String id = s.name().toLowerCase(Locale.ROOT);
      if (id.equalsIgnoreCase(word) || lang.text("calamity.severity-" + id).equalsIgnoreCase(word))
        return s;
    }
    throw new IllegalArgumentException("calamity.usage");
  }

  private void startCommand(org.bukkit.command.CommandSender p, String[] args) {
    Type t = args.length > 1 ? Type.of(args[1]) : null;
    if (t == null) throw new IllegalArgumentException("calamity.usage");
    if (phase != Phase.IDLE) {
      lang.send(p, "calamity.admin-busy");
      return;
    }
    Severity s = args.length > 2 ? severityOf(args[2]) : Severity.NORMAL;
    double minutes = number(args, 3, 0);
    if (args.length > 3 && minutes < 1) throw new IllegalArgumentException("calamity.usage");
    begin(t, s, minutes > 0 ? Math.round(minutes * 1200) : 0);
    lang.send(
        p,
        "calamity.admin-started",
        Map.of(
            "name", name(t),
            "severity", lang.text("calamity.severity-" + s.name().toLowerCase(Locale.ROOT)),
            "time", time(saved.duration)));
  }

  private void diseaseCommand(org.bukkit.command.CommandSender p, String[] args) {
    Disease d = args.length > 1 ? Disease.of(args[1]) : null;
    if (d == null || args.length < 3) throw new IllegalArgumentException("calamity.usage");
    String target = args[2];
    if (target.equalsIgnoreCase("on") || target.equalsIgnoreCase("off")) {
      boolean on = target.equalsIgnoreCase("on");
      if (on) saved.serverWide.add(d.key);
      else saved.serverWide.remove(d.key);
      dirty = true;
      lang.send(p, "calamity.admin-disease", Map.of("name", name(d), "state", target.toLowerCase(Locale.ROOT), "target", "*"));
      return;
    }
    Player who = Bukkit.getPlayerExact(target);
    if (who == null) throw new IllegalArgumentException("errors.input");
    boolean cure = args.length > 3 && args[3].equalsIgnoreCase("cure");
    if (cure) cure(who, d);
    else infect(who, d);
    lang.send(
        p,
        "calamity.admin-disease",
        Map.of("name", name(d), "state", cure ? "cure" : "infect", "target", who.getName()));
  }

  private void simulate(org.bukkit.command.CommandSender p, Type t, Severity s) {
    double cap = settings.d("cap-percent", 75);
    double power = CalamityRules.strength(0.5, s, 1);
    java.util.function.Function<String, String> pct =
        key ->
            String.format(
                Locale.ROOT, "%.0f", CalamityRules.penalty(settings.base(t, key), power, 0, cap) * 100);
    lang.send(
        p,
        "calamity.admin-simulate",
        Map.of(
            "name", name(t),
            "severity", lang.text("calamity.severity-" + s.name().toLowerCase(Locale.ROOT)),
            "growth", pct.apply("growth"),
            "crops", pct.apply("crops"),
            "breeding", pct.apply("breeding"),
            "meat", pct.apply("meat"),
            "spawn", pct.apply("natural-spawn")));
  }

  private void info(org.bukkit.command.CommandSender p) {
    switch (phase) {
      case ACTIVE ->
          lang.send(
              p,
              "calamity.status-active",
              Map.of(
                  "name", name(type),
                  "severity", lang.text("calamity.severity-" + severity.name().toLowerCase(Locale.ROOT)),
                  "time", time(Math.max(0, saved.duration - saved.elapsed)),
                  "strength", String.format(Locale.ROOT, "%.0f", strength * 100)));
      case OMEN ->
          lang.send(
              p,
              "calamity.status-omen",
              Map.of("name", name(type), "time", time(Math.max(0, saved.duration - saved.elapsed))));
      default ->
          lang.send(
              p,
              remainingCooldownTicks() > 0 ? "calamity.status-idle" : "calamity.status-idle-ready",
              Map.of("time", time(remainingCooldownTicks())));
    }
    var ill = new ArrayList<String>();
    if (p instanceof Player player) for (Disease d : Disease.values()) if (sick(player, d)) ill.add(name(d));
    if (!ill.isEmpty())
      lang.send(p, "calamity.status-sick", Map.of("diseases", String.join(", ", ill)));
    if (admin(p)) {
      var threats = new ArrayList<String>();
      for (Type t : Type.values())
        threats.add(name(t) + " " + String.format(Locale.ROOT, "%.0f", saved.threat.getOrDefault(t.key, 0.0)));
      lang.send(p, "calamity.status-threat", Map.of("threats", String.join(", ", threats)));
    }
  }

  public List<String> suggestions(Player p, String[] args) {
    if (!admin(p)) return args.length <= 1 ? List.of("info") : List.of();
    var types = Arrays.stream(Type.values()).map(t -> t.key).toList();
    if (args.length <= 1)
      return List.of("info", "start", "stop", "threat", "disease", "simulate", "log");
    String sub = args[0].toLowerCase(Locale.ROOT);
    if (args.length == 2) {
      return switch (sub) {
        case "start", "threat", "simulate" -> types;
        case "disease" -> Arrays.stream(Disease.values()).map(d -> d.key).toList();
        default -> List.of();
      };
    }
    if (args.length == 3) {
      return switch (sub) {
        case "start", "simulate" -> List.of("mild", "normal", "heavy");
        case "disease" -> {
          var out = new ArrayList<>(List.of("on", "off"));
          Bukkit.getOnlinePlayers().forEach(o -> out.add(o.getName()));
          yield out;
        }
        default -> List.of();
      };
    }
    return args.length == 4 && sub.equals("disease") ? List.of("cure") : List.of();
  }

  // ---------------------------------------------------------------- read access for tests and other modules

  public Phase phase() {
    return phase;
  }

  public Type current() {
    return type;
  }

  public double strength() {
    return strength;
  }

  /** Validates the {@code calamities} section; throws {@code errors.config} for unusable values. */
  public static void validate(org.bukkit.configuration.ConfigurationSection config) {
    CalamitySettings.validate(config);
  }
}
