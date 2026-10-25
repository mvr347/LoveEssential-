package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.lovecore.api.display.DisplayPrefs;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Per-player choice of which clan tags and suffixes are shown, as the LoveCore {@link DisplayPrefs}
 * contract. Six owner choices (chat, TAB, nametag × clan, suffix) control visibility for everyone.
 * The twelve-bit storage/API layout is retained for compatibility; legacy viewer choices no longer
 * affect visibility. Reads are safe from any thread (chat
 * renders asynchronously); until a player's flags have loaded everything is shown.
 */
public final class DisplayPreferences implements DisplayPrefs, Listener, AutoCloseable {
  static final String KIND = "display-prefs";

  /** Stored form: a set bit means "hidden", so an unset player is all zeros and shows everything. */
  public record Stored(int hidden) {}

  private static final class State {
    volatile int hidden;
    long revision;
    CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    State(long revision, int hidden) {
      this.revision = revision;
      this.hidden = hidden;
    }
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  public DisplayPreferences(LoveEssentialPlugin plugin, MainThread main, SqliteStore store) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getOnlinePlayers().forEach(p -> load(p.getUniqueId()));
  }

  /** Bit of one flag, for code that compares the stored mask. */
  public static int bitOf(Surface surface, Element element, Scope scope) {
    return bit(surface, element, scope);
  }

  /** Bit of one flag: surface, element and scope each pick a position, twelve in all. */
  static int bit(Surface surface, Element element, Scope scope) {
    return 1 << (surface.ordinal() * 4 + element.ordinal() * 2 + scope.ordinal());
  }

  /** Whether the flags of this player have arrived from the database. */
  public boolean loaded(UUID player) {
    return states.containsKey(player);
  }

  @Override
  public boolean shown(UUID player, Surface surface, Element element, Scope scope) {
    var state = states.get(player);
    return state == null
        || !plugin.module("display")
        || (state.hidden & bit(surface, element, scope)) == 0;
  }

  @Override
  public boolean set(UUID player, Surface surface, Element element, Scope scope, boolean shown) {
    var state = states.get(player);
    if (state == null) return true;
    int bit = bit(surface, element, scope);
    boolean previous;
    synchronized (state) {
      previous = (state.hidden & bit) == 0;
      if (previous == shown) return previous;
      state.hidden = shown ? state.hidden & ~bit : state.hidden | bit;
      persist(player, state);
    }
    return previous;
  }

  /** Shows (or hides) all twelve flags at once; one write. */
  public void setAll(UUID player, boolean shown) {
    var state = states.get(player);
    if (state == null) return;
    synchronized (state) {
      int next = shown ? 0 : mask();
      if (state.hidden == next) return;
      state.hidden = next;
      persist(player, state);
    }
  }

  /** Every bit that belongs to a real flag; the stored mask never carries stray bits. */
  private static int mask() {
    int mask = 0;
    for (var s : Surface.values())
      for (var e : Element.values())
        for (var c : Scope.values()) mask |= bit(s, e, c);
    return mask;
  }

  /** Writes the latest mask; saves run one after another so revisions never clash. */
  private void persist(UUID player, State state) {
    state.tail =
        state
            .tail
            .handle((value, error) -> null)
            .thenCompose(
                ignored -> {
                  long expected;
                  int snapshot;
                  synchronized (state) {
                    expected = state.revision;
                    snapshot = state.hidden;
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
                            "display-prefs: not saved for "
                                + player
                                + " ("
                                + error.getClass().getSimpleName()
                                + ")");
                });
  }

  private void load(UUID player) {
    if (states.containsKey(player) || !loading.add(player)) return;
    store
        .get(KIND, player.toString(), Stored.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      loading.remove(player);
                      if (closed || Bukkit.getPlayer(player) == null) return;
                      if (error != null) {
                        plugin
                            .getLogger()
                            .warning(
                                "display-prefs: not loaded for "
                                    + player
                                    + " ("
                                    + error.getClass().getSimpleName()
                                    + ")");
                        Bukkit.getScheduler().runTaskLater(plugin, () -> load(player), 200L);
                        return;
                      }
                      states.putIfAbsent(
                          player,
                          saved
                              .map(d -> new State(d.revision(), d.value().hidden() & mask()))
                              .orElseGet(() -> new State(0, 0)));
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
  }

  /** Flags as placeholder values: {@code display_<surface>_<element>_<scope>} = true when shown. */
  public Map<String, String> values(UUID player) {
    var values = new LinkedHashMap<String, String>();
    for (var s : Surface.values())
      for (var e : Element.values())
        for (var c : Scope.values())
          values.put(key(s, e, c), Boolean.toString(shown(player, s, e, c)));
    return values;
  }

  /** One command choice; {@code value} is null when the command only toggles. */
  public record Choice(Scope scope, Element element, Surface surface, Boolean value) {}

  /**
   * Parses {@code [own] <clan|suffix> <chat|tab|nametag> [on|off]}; Russian words work too.
   * The choice always concerns the player's own signature, including when seen by its owner.
   */
  public static Choice parse(String[] args) {
    Scope scope = args.length == 0 ? null : scopeOf(args[0]);
    int first = scope == null ? 0 : 1;
    if (args.length - first < 2 || args.length - first > 3)
      throw new IllegalArgumentException("display.usage");
    Element element =
        switch (args[first].toLowerCase(Locale.ROOT)) {
          case "clan", "tag", "клан", "тег" -> Element.CLAN_TAG;
          case "suffix", "суффикс", "пометки", "пометка" -> Element.SUFFIX;
          default -> throw new IllegalArgumentException("display.usage");
        };
    Surface surface =
        switch (args[first + 1].toLowerCase(Locale.ROOT)) {
          case "chat", "чат" -> Surface.CHAT;
          case "tab", "таб" -> Surface.TAB;
          case "nametag", "name", "ник" -> Surface.NAMETAG;
          default -> throw new IllegalArgumentException("display.usage");
        };
    Boolean value = null;
    if (args.length - first == 3)
      value =
          switch (args[first + 2].toLowerCase(Locale.ROOT)) {
            case "on", "show", "вкл", "да" -> Boolean.TRUE;
            case "off", "hide", "выкл", "нет" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("display.usage");
          };
    return new Choice(scope == null ? Scope.OWN : scope, element, surface, value);
  }

  private static Scope scopeOf(String word) {
    return switch (word.toLowerCase(Locale.ROOT)) {
      case "own", "my", "mine", "мои", "мой", "моё" -> Scope.OWN;
      default -> null;
    };
  }

  public static String key(Surface surface, Element element, Scope scope) {
    return "display_"
        + surface.name().toLowerCase(Locale.ROOT)
        + "_"
        + (element == Element.CLAN_TAG ? "clan" : "suffix")
        + "_"
        + scope.name().toLowerCase(Locale.ROOT);
  }

  /** Legacy permission name retained for integrations; owner choices no longer require it. */
  public static final String OWN_PERMISSION = "loveessential.display.own";

  @Override
  public boolean visible(UUID owner, UUID viewer, Surface surface, Element element) {
    return shown(owner, surface, element, Scope.OWN);
  }

  /** The viewer's setting for one player on one surface: which elements to leave out. */
  public Set<Element> hiddenFor(UUID owner, UUID viewer, Surface surface) {
    var hidden = EnumSet.noneOf(Element.class);
    for (var element : Element.values()) if (!visible(owner, viewer, surface, element)) hidden.add(element);
    return hidden;
  }

  @Override
  public void close() {
    closed = true;
    states.clear();
    loading.clear();
  }
}
