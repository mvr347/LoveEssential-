package dev.lovelace.loveessential.module;

import dev.lovelace.lovecore.api.secrets.SecretBook;
import dev.lovelace.lovecore.api.secrets.SecretDiscoveredEvent;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * «Книга тайн»: which easter eggs each player has found, as the LoveCore {@link SecretBook}
 * contract. The catalogue (names, hints, descriptions) lives in {@code modules/secrets/secrets.yml};
 * the found list is one document per player. Until a player's list has arrived from the database,
 * {@link #discover} answers {@code false} and nothing is recorded, so a find never overwrites data
 * that has not been read yet.
 */
public final class Secrets implements SecretBook, Listener, AutoCloseable {
  public static final String ID = "loveessential";
  static final String KIND = "secrets";

  /** Stored form: secret id → when it was found (epoch milliseconds). */
  public record Stored(Map<String, Long> found) {}

  /** One catalogue entry as the book shows it. */
  public record Entry(String id, String name, String hint, String description, Material icon) {}

  private static final class State {
    volatile Map<String, Long> found;
    long revision;
    CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    State(long revision, Map<String, Long> found) {
      this.revision = revision;
      this.found = Map.copyOf(found);
    }
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
  private final Map<UUID, CompletableFuture<?>> writes = new ConcurrentHashMap<>();
  private final Map<UUID, Object> sessions = new HashMap<>();
  private final Set<String> reportedUnknown = new HashSet<>();
  private volatile boolean closed;

  public Secrets(
      LoveEssentialPlugin plugin, MainThread main, SqliteStore store, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getOnlinePlayers().forEach(p -> load(p.getUniqueId()));
  }

  @Override
  public void close() {
    closed = true;
    states.clear();
    loading.clear();
    sessions.clear();
    writes.clear();
    org.bukkit.event.HandlerList.unregisterAll(this);
  }

  @Override
  public String providerId() {
    return ID;
  }

  // ----------------------------------------------------------------- catalogue

  /** The catalogue in file order. */
  public List<Entry> catalog() {
    var section = plugin.getConfig().getConfigurationSection("secrets.catalog");
    var result = new ArrayList<Entry>();
    if (section == null) return result;
    for (String id : section.getKeys(false)) {
      var entry = section.getConfigurationSection(id);
      if (entry == null) continue;
      Material icon = Material.matchMaterial(entry.getString("icon", "BOOK"));
      result.add(
          new Entry(
              id,
              entry.getString("name", id),
              entry.getString("hint", ""),
              entry.getString("description", ""),
              icon == null ? Material.BOOK : icon));
    }
    return result;
  }

  private boolean known(String id) {
    var section = plugin.getConfig().getConfigurationSection("secrets.catalog");
    return section != null && section.isConfigurationSection(id);
  }

  // ------------------------------------------------------------------ contract

  /** Whether this player's list has arrived from the database. */
  public boolean loaded(UUID player) {
    return states.containsKey(player);
  }

  @Override
  public boolean discover(UUID player, String secretId) {
    MainThread.require();
    var online = Bukkit.getPlayer(player);
    var state = states.get(player);
    if (closed || online == null || state == null || secretId == null || !plugin.module("secrets"))
      return false;
    if (!known(secretId)) {
      if (reportedUnknown.add(secretId))
        plugin.getLogger().warning("secrets: unknown secret id «" + secretId + "» ignored");
      return false;
    }
    synchronized (state) {
      if (state.found.containsKey(secretId)) return false;
      var next = new LinkedHashMap<>(state.found);
      next.put(secretId, System.currentTimeMillis());
      state.found = Map.copyOf(next);
      persist(player, state);
    }
    var entry = catalog().stream().filter(e -> e.id().equals(secretId)).findFirst().orElseThrow();
    love.notify(online, lang.renderText(lang.text("secrets.found"), Map.of("name", entry.name())));
    online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    Bukkit.getPluginManager().callEvent(new SecretDiscoveredEvent(online, secretId));
    return true;
  }

  @Override
  public Set<String> found(UUID player) {
    var state = states.get(player);
    return state == null ? Set.of() : Set.copyOf(state.found.keySet());
  }

  /** When each found secret was found; empty until the list has loaded. */
  public Map<String, Long> foundAt(UUID player) {
    var state = states.get(player);
    return state == null ? Map.of() : state.found;
  }

  /** Placeholder values: {@code secrets} (found) and {@code secrets_total}. */
  public Map<String, String> values(UUID player) {
    int total = plugin.module("secrets") ? catalog().size() : 0;
    int found = (int) found(player).stream().filter(this::known).count();
    return Map.of("secrets", Integer.toString(found), "secrets_total", Integer.toString(total));
  }

  /** Forgets everything a player has found (administration, tests). */
  public void reset(UUID player) {
    var state = states.get(player);
    if (state == null) return;
    synchronized (state) {
      state.found = Map.of();
      persist(player, state);
    }
  }

  // ------------------------------------------------------------------- storage

  /** Writes the latest list; saves run one after another so revisions never clash. */
  private void persist(UUID player, State state) {
    var tail =
        state
            .tail
            .handle((value, error) -> null)
            .thenCompose(
                ignored -> {
                  long expected;
                  Map<String, Long> snapshot;
                  synchronized (state) {
                    expected = state.revision;
                    snapshot = state.found;
                  }
                  return store
                      .save(KIND, player.toString(), expected, new Stored(snapshot), player.toString())
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
                            "secrets: not saved for "
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
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      if (sessions.get(player) != session) return;
                      loading.remove(player);
                      if (closed || Bukkit.getPlayer(player) != online) return;
                      if (error != null) {
                        plugin
                            .getLogger()
                            .warning(
                                "secrets: not loaded for "
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
                      states.putIfAbsent(
                          player,
                          saved
                              .map(
                                  d ->
                                      new State(
                                          d.revision(),
                                          d.value() == null || d.value().found() == null
                                              ? Map.of()
                                              : d.value().found()))
                              .orElseGet(() -> new State(0, Map.of())));
                    }));
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
    sessions.remove(id);
  }
}
