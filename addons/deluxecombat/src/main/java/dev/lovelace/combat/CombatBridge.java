package dev.lovelace.combat;

import dev.lovelace.lovecore.api.combat.CombatState;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable published snapshots: async Love consumers never call a Bukkit/DeluxeCombat API. */
final class CombatBridge implements CombatState {
  record Entry(Instant end, String source) {}
  private final CombatState delegate;
  private final Clock clock;
  private final java.util.function.Consumer<UUID> untag;
  private final Map<UUID, Entry> explicit = new ConcurrentHashMap<>();
  private volatile Map<UUID, Entry> observed = Map.of();
  private boolean closed;
  private final Set<UUID> clearing = new HashSet<>();

  CombatBridge(CombatState delegate, Clock clock, java.util.function.Consumer<UUID> untag) {
    this.delegate = delegate;
    this.clock = clock;
    this.untag = untag;
  }

  synchronized void publish(Map<UUID, Entry> snapshot) {
    if (closed) return;
    // A clear racing with a poll cannot resurrect the old DC snapshot.
    var copy = new HashMap<>(snapshot);
    clearing.forEach(copy::remove);
    clearing.removeIf(id -> !snapshot.containsKey(id));
    observed = Map.copyOf(copy);
    explicit.entrySet().removeIf(e -> !e.getValue().end().isAfter(clock.instant()));
  }

  private Optional<Entry> current(UUID id) {
    if (id == null) return Optional.empty();
    Entry a = observed.get(id), b = explicit.get(id);
    Entry result = a == null || b != null && b.end().isAfter(a.end()) ? b : a;
    return result != null && result.end().isAfter(clock.instant()) ? Optional.of(result) : Optional.empty();
  }

  @Override public boolean inCombat(UUID id) { return current(id).isPresent(); }
  @Override public Optional<Instant> combatEndsAt(UUID id) { return current(id).map(Entry::end); }
  @Override public Optional<String> combatSource(UUID id) { return current(id).map(Entry::source); }
  @Override public synchronized void mark(UUID id, Duration duration, String source) {
    if (closed || id == null || duration == null || duration.isNegative() || duration.isZero()) return;
    Entry incoming = new Entry(clock.instant().plus(duration), source == null ? "unknown" : source);
    explicit.merge(id, incoming, (a, b) -> a.end().isAfter(b.end()) ? a : b);
    delegate.mark(id, duration, source);
  }
  @Override public synchronized void clear(UUID id) {
    if (closed || id == null) return;
    explicit.remove(id);
    synchronized (this) {
      clearing.add(id);
      var copy = new HashMap<>(observed);
      copy.remove(id);
      observed = Map.copyOf(copy);
    }
    delegate.clear(id);
    untag.accept(id);
  }
  void forget(UUID id) {
    explicit.remove(id);
    synchronized (this) {
      var copy = new HashMap<>(observed);
      copy.remove(id);
      observed = Map.copyOf(copy);
    }
  }
  synchronized void close() { closed = true; observed = Map.of(); explicit.clear(); clearing.clear(); }

  static boolean inheritedSource(String source) {
    return source != null && !Set.of("pvp", "deluxecombat", "in_combat").contains(source.toLowerCase(Locale.ROOT));
  }
}
