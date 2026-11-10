package dev.lovelace.loveessential.module;

import dev.lovelace.lovecore.api.achievements.AchievementRarity;
import dev.lovelace.lovecore.api.achievements.AchievementUnlockedEvent;
import dev.lovelace.lovecore.api.achievements.Achievements;
import dev.lovelace.lovecore.api.stats.StatChangedEvent;
import dev.lovelace.lovecore.api.stats.StatSubject;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.revive.ReviveEvent;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.AchievementEngine.Progress;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The shared achievements: the LoveCore {@link Achievements} contract on top of {@link AchievementEngine}. The
 * catalogue is {@code achievements.catalog} of {@code modules/achievements/achievements.yml}; the progress of a player
 * is one document in the store. Facts that arrive before a player's document has been read are queued and applied
 * right after it loads, so a bounty paid in the first second after joining is not lost and nothing is written over
 * data that has not been read yet.
 *
 * <p>Sources: the stat bus ({@link StatChangedEvent}), the plugins that call the contract, and a few counters of
 * this plugin (play time, daily join streak, revives).</p>
 */
public final class AchievementService implements Achievements, Listener, AutoCloseable {
  static final String KIND = "achievements";
  /** The prefix the old book of secrets ids get as achievements. */
  public static final String SECRET_PREFIX = "secret_";

  private static final int QUEUE_LIMIT = 512;
  private static final long TICKS_PER_HOUR = 72_000L;

  /** Stored form of one player. */
  public record Stored(
      Map<String, Long> unlocked, Map<String, Double> counters, Map<String, List<String>> sets) {}

  private static final class State {
    final Progress progress = new Progress();
    long revision;
    CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Deque<Function<Progress, List<String>>>> queued = new ConcurrentHashMap<>();
  private final Map<UUID, CompletableFuture<?>> writes = new ConcurrentHashMap<>();
  private final Map<UUID, Object> sessions = new HashMap<>();
  private final Set<String> reportedUnknown = new HashSet<>();
  private volatile AchievementCatalog catalog = AchievementCatalog.empty();
  private volatile AchievementEngine engine = new AchievementEngine(catalog);
  private volatile boolean closed;
  private org.bukkit.scheduler.BukkitTask playTimeTask;

  public AchievementService(
      LoveEssentialPlugin plugin, MainThread main, SqliteStore store, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
  }

  public void start() {
    reload();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getOnlinePlayers().forEach(p -> load(p.getUniqueId()));
    playTimeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::trackPlayTime, 6000L, 6000L);
  }

  /** Re-reads the catalogue from the config; a typo in one entry skips that entry only. */
  public void reload() {
    var parsed =
        AchievementCatalog.parse(
            plugin.getConfig().getConfigurationSection("achievements.catalog"),
            problem -> plugin.getLogger().warning(problem));
    catalog = parsed;
    engine = new AchievementEngine(parsed);
  }

  @Override
  public void close() {
    closed = true;
    if (playTimeTask != null) playTimeTask.cancel();
    states.clear();
    loading.clear();
    queued.clear();
    sessions.clear();
    writes.clear();
    org.bukkit.event.HandlerList.unregisterAll(this);
  }

  public AchievementCatalog catalog() {
    return catalog;
  }

  // ------------------------------------------------------------------ contract

  @Override
  public boolean unlock(UUID player, String achievementId) {
    if (achievementId == null) return false;
    if (!catalog.contains(achievementId)) {
      reportUnknown(achievementId);
      return false;
    }
    return apply(player, progress -> engine.unlock(progress, achievementId, now())).contains(achievementId);
  }

  @Override
  public void progress(UUID player, String key, double delta) {
    if (key == null) return;
    apply(player, progress -> engine.add(progress, key, delta, now()));
  }

  @Override
  public void set(UUID player, String key, double value) {
    if (key == null) return;
    apply(player, progress -> engine.set(progress, key, value, now()));
  }

  @Override
  public void collect(UUID player, String setKey, String value) {
    if (setKey == null) return;
    apply(player, progress -> engine.collect(progress, setKey, value, now()));
  }

  @Override
  public boolean unlocked(UUID player, String achievementId) {
    var state = states.get(player);
    return state != null && achievementId != null && state.progress.unlocked.containsKey(achievementId);
  }

  @Override
  public Set<String> unlockedIds(UUID player) {
    var state = states.get(player);
    if (state == null) return Set.of();
    synchronized (state) {
      return Set.copyOf(state.progress.unlocked.keySet());
    }
  }

  @Override
  public Set<String> catalogIds() {
    return Set.copyOf(catalog.ids());
  }

  // ---------------------------------------------------------------- read model

  /** Whether this player's progress has arrived from the database. */
  public boolean loaded(UUID player) {
    return states.containsKey(player);
  }

  /** When each achievement was unlocked; empty until the progress has loaded. */
  public Map<String, Long> unlockedAt(UUID player) {
    var state = states.get(player);
    if (state == null) return Map.of();
    synchronized (state) {
      return Map.copyOf(state.progress.unlocked);
    }
  }

  /** Current value of a counter for the menu's progress bars. */
  public double counter(UUID player, String key) {
    var state = states.get(player);
    if (state == null) return 0;
    synchronized (state) {
      return state.progress.counter(key);
    }
  }

  /** The values collected so far into a set. */
  public Set<String> collected(UUID player, String key) {
    var state = states.get(player);
    if (state == null) return Set.of();
    synchronized (state) {
      return Set.copyOf(state.progress.sets.getOrDefault(key, Set.of()));
    }
  }

  /** Placeholder values: {@code achievements} (unlocked, only catalogued ones) and {@code achievements_total}. */
  public Map<String, String> values(UUID player) {
    boolean on = plugin.module("achievements");
    int total = on ? catalog.all().size() : 0;
    int owned = on ? (int) unlockedIds(player).stream().filter(catalog::contains).count() : 0;
    return Map.of("achievements", Integer.toString(owned), "achievements_total", Integer.toString(total));
  }

  // -------------------------------------------------------------------- admin

  /** Takes one achievement back (administration); counters stay, so a counter achievement can come back by itself. */
  public boolean revoke(UUID player, String achievementId) {
    var state = states.get(player);
    if (state == null) return false;
    synchronized (state) {
      if (state.progress.unlocked.remove(achievementId) == null) return false;
    }
    persist(player, state);
    return true;
  }

  /** Forgets all progress and every achievement of a player (administration, tests). */
  public void reset(UUID player) {
    var state = states.get(player);
    if (state == null) return;
    synchronized (state) {
      state.progress.unlocked.clear();
      state.progress.counters.clear();
      state.progress.sets.clear();
    }
    persist(player, state);
  }

  // --------------------------------------------------------------- application

  private static long now() {
    return System.currentTimeMillis();
  }

  private void reportUnknown(String id) {
    if (reportedUnknown.add(id))
      plugin.getLogger().warning("achievements: unknown achievement id «" + id + "» ignored");
  }

  /** Runs a change now when the player's progress is loaded, queues it while it is loading, drops it otherwise. */
  private List<String> apply(UUID player, Function<Progress, List<String>> change) {
    MainThread.require();
    if (closed || !plugin.module("achievements")) return List.of();
    var state = states.get(player);
    if (state == null) {
      if (loading.contains(player)) {
        var queue = queued.computeIfAbsent(player, id -> new ArrayDeque<>());
        if (queue.size() < QUEUE_LIMIT) queue.add(change);
      }
      return List.of();
    }
    List<String> unlocked;
    synchronized (state) {
      unlocked = change.apply(state.progress);
    }
    if (!unlocked.isEmpty()) {
      persist(player, state);
      var online = Bukkit.getPlayer(player);
      if (online != null) unlocked.forEach(id -> announce(online, id));
    }
    return unlocked;
  }

  private void announce(Player player, String id) {
    var def = catalog.find(id).orElse(null);
    if (def == null) return;
    var vars =
        Map.of(
            "name", def.name(),
            "description", def.description(),
            "player", player.getName(),
            "reward", def.rewardText());
    // The rarity carries its own colour, so it goes in as a component, not as text.
    var parts =
        Map.of(
            "rarity",
            lang.render("achievements.rarity-" + def.rarity().name().toLowerCase(Locale.ROOT)));
    var title = lang.renderText(lang.text("achievements.unlocked"), vars, parts);
    love.service(dev.lovelace.lovecore.api.notify.LoveNotify.class)
        .ifPresentOrElse(n -> n.notify(player, title), () -> player.sendMessage(title));
    love.notify(player, lang.renderText(lang.text("achievements.unlocked-detail"), vars));
    if (!def.rewardText().isBlank())
      love.notify(player, lang.renderText(lang.text("achievements.unlocked-reward"), vars));
    player.playSound(
        player.getLocation(),
        Sound.UI_TOAST_CHALLENGE_COMPLETE,
        0.8f,
        def.rarity().atLeast(AchievementRarity.EPIC) ? 1.4f : 1.1f);
    if (broadcastFrom().map(def.rarity()::atLeast).orElse(false))
      Bukkit.broadcast(lang.renderText(lang.text("achievements.broadcast"), vars));
    Bukkit.getPluginManager().callEvent(new AchievementUnlockedEvent(player, id, def.rarity()));
  }

  /** The lowest rarity announced to the whole server; empty when the broadcast is switched off. */
  private Optional<AchievementRarity> broadcastFrom() {
    String configured = plugin.getConfig().getString("achievements.broadcast-rarity", "LEGENDARY");
    try {
      return Optional.of(AchievementRarity.valueOf(configured.toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException off) {
      return Optional.empty();
    }
  }

  // ------------------------------------------------------------------- sources

  @EventHandler
  public void stat(StatChangedEvent event) {
    if (event.subject().kind() != StatSubject.Kind.PLAYER) return;
    var player = event.subject().id();
    if (event.mode() == StatChangedEvent.Mode.ADD) progress(player, event.metric(), event.value());
    else set(player, event.metric(), event.value());
  }

  @EventHandler
  public void revive(ReviveEvent event) {
    if (event.getReviver() != null) progress(event.getReviver().getUniqueId(), "revive.saved", 1);
  }

  private void trackPlayTime() {
    for (var p : Bukkit.getOnlinePlayers()) playTime(p);
  }

  private void playTime(Player p) {
    if (!states.containsKey(p.getUniqueId())) return;
    set(p.getUniqueId(), "play.hours", p.getStatistic(Statistic.PLAY_ONE_MINUTE) / (double) TICKS_PER_HOUR);
  }

  /** Daily join counters: {@code join.days} (distinct days) and {@code join.streak} (days in a row). */
  private void joinDay(UUID player) {
    long today = LocalDate.now().toEpochDay();
    apply(
        player,
        progress -> {
          double last = progress.counters.getOrDefault("join.last", -1.0);
          if (last == today) return List.of();
          double streak = last == today - 1 ? progress.counter("join.streak") + 1 : 1;
          progress.counters.put("join.last", (double) today);
          var result = new ArrayList<>(engine.set(progress, "join.streak", streak, now()));
          result.addAll(engine.add(progress, "join.days", 1, now()));
          return result;
        });
  }

  // ------------------------------------------------------------------- storage

  private Stored snapshot(State state) {
    synchronized (state) {
      var sets = new LinkedHashMap<String, List<String>>();
      state.progress.sets.forEach((key, values) -> sets.put(key, List.copyOf(values)));
      return new Stored(
          new LinkedHashMap<>(state.progress.unlocked), new LinkedHashMap<>(state.progress.counters), sets);
    }
  }

  /** Writes the latest state; saves run one after another so revisions never clash. */
  private void persist(UUID player, State state) {
    var tail =
        state
            .tail
            .handle((value, error) -> null)
            .thenCompose(
                ignored -> {
                  long expected;
                  Stored snapshot;
                  synchronized (state) {
                    expected = state.revision;
                  }
                  snapshot = snapshot(state);
                  return store
                      .save(KIND, player.toString(), expected, snapshot, player.toString())
                      .thenAccept(
                          revision -> {
                            synchronized (state) {
                              state.revision = revision;
                            }
                          });
                })
            .whenComplete(
                (value, error) -> {
                  if (error != null && !closed)
                    plugin
                        .getLogger()
                        .warning(
                            "achievements: not saved for "
                                + player
                                + " ("
                                + error.getClass().getSimpleName()
                                + ")");
                });
    state.tail = tail;
    writes.put(player, tail);
    tail.whenComplete((value, error) -> writes.remove(player, tail));
  }

  private void load(UUID player) {
    MainThread.require();
    var online = Bukkit.getPlayer(player);
    if (closed || online == null || states.containsKey(player) || !loading.add(player)) return;
    Object session = new Object();
    sessions.put(player, session);
    var previousWrite = writes.get(player);
    (previousWrite == null
            ? CompletableFuture.completedFuture(null)
            : previousWrite.handle((value, error) -> null))
        .thenCompose(ignored -> store.get(KIND, player.toString(), Stored.class))
        .thenCompose(
            saved ->
                saved.isPresent()
                    ? CompletableFuture.completedFuture(
                        new Loaded(saved, Optional.<SqliteStore.Document<Secrets.Stored>>empty()))
                    // First time here: the old book of secrets is read once and turned into achievements.
                    : store
                        .get("secrets", player.toString(), Secrets.Stored.class)
                        .thenApply(old -> new Loaded(saved, old)))
        .whenComplete(
            (loaded, error) ->
                main.execute(
                    () -> {
                      if (sessions.get(player) != session) return;
                      loading.remove(player);
                      if (closed || Bukkit.getPlayer(player) != online) return;
                      if (error != null) {
                        plugin
                            .getLogger()
                            .warning(
                                "achievements: not loaded for "
                                    + player
                                    + " ("
                                    + error.getClass().getSimpleName()
                                    + ")");
                        Bukkit.getScheduler()
                            .runTaskLater(
                                plugin,
                                () -> {
                                  if (!closed
                                      && sessions.get(player) == session
                                      && Bukkit.getPlayer(player) == online) load(player);
                                },
                                200L);
                        return;
                      }
                      install(player, online, loaded);
                    }));
  }

  private record Loaded(
      Optional<SqliteStore.Document<Stored>> saved, Optional<SqliteStore.Document<Secrets.Stored>> old) {}

  private void install(UUID player, Player online, Loaded loaded) {
    var state = new State();
    loaded
        .saved()
        .ifPresent(
            doc -> {
              state.revision = doc.revision();
              var value = doc.value();
              if (value == null) return;
              if (value.unlocked() != null) state.progress.unlocked.putAll(value.unlocked());
              if (value.counters() != null) state.progress.counters.putAll(value.counters());
              if (value.sets() != null)
                value.sets().forEach((key, values) -> state.progress.sets.put(key, new LinkedHashSet<>(values)));
            });
    boolean migrated = false;
    var oldSecrets = loaded.old();
    if (oldSecrets.isPresent() && oldSecrets.get().value() != null && oldSecrets.get().value().found() != null) {
      for (var found : oldSecrets.get().value().found().entrySet()) {
        // A secret that has left the catalogue is dropped: it could never be shown or counted.
        if (!catalog.contains(SECRET_PREFIX + found.getKey())) continue;
        migrated |= state.progress.unlocked.putIfAbsent(SECRET_PREFIX + found.getKey(), found.getValue()) == null;
      }
    }
    states.put(player, state);
    // Facts that arrived while the progress was loading, then whatever the catalogue now says is already true.
    var waiting = queued.remove(player);
    if (waiting != null) waiting.forEach(change -> apply(player, change));
    apply(player, progress -> engine.settle(progress, now()));
    if (migrated) persist(player, state);
    joinDay(player);
    playTime(online);
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    load(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    var id = event.getPlayer().getUniqueId();
    states.remove(id);
    loading.remove(id);
    queued.remove(id);
    sessions.remove(id);
  }
}
