package dev.lovelace.loveessential.scoreboard;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.ScoreboardService;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import org.bukkit.plugin.Plugin;

/** Ephemeral requests are never persisted with a player's chosen layout. */
final class SidebarOverrides implements ScoreboardService {
  private final List<Entry> entries = new ArrayList<>();
  private final LongSupplier clock;
  private final java.util.function.IntSupplier maxSeconds, perPlayer;
  private long sequence;
  private boolean closed;

  SidebarOverrides(
      LongSupplier clock,
      java.util.function.IntSupplier maxSeconds,
      java.util.function.IntSupplier perPlayer) {
    this.clock = clock;
    this.maxSeconds = maxSeconds;
    this.perPlayer = perPlayer;
  }

  private final class Entry implements Lease {
    final Plugin owner;
    final UUID player;
    final int priority;
    final long expires, sequence;
    final boolean force;
    Board board;

    Entry(Plugin owner, UUID player, int priority, long expires, boolean force, Board board) {
      this.owner = owner;
      this.player = player;
      this.priority = priority;
      this.expires = expires;
      this.force = force;
      this.board = board;
      this.sequence = ++SidebarOverrides.this.sequence;
    }

    public void update(Board board) {
      MainThread.require();
      prune();
      if (!entries.contains(this)) throw new IllegalStateException("expired-lease");
      this.board = Objects.requireNonNull(board);
    }

    public void close() {
      MainThread.require();
      entries.remove(this);
    }
  }

  public Lease show(
      Plugin owner, UUID player, int priority, Duration ttl, boolean force, Board board) {
    MainThread.require();
    Objects.requireNonNull(owner);
    Objects.requireNonNull(player);
    Objects.requireNonNull(ttl);
    Objects.requireNonNull(board);
    if (closed
        || !owner.isEnabled()
        || org.bukkit.Bukkit.getPluginManager().getPlugin(owner.getName()) != owner
        || org.bukkit.Bukkit.getPlayer(player) == null
        || priority < -1000
        || priority > 1000
        || ttl.isNegative()
        || ttl.isZero()
        || ttl.compareTo(Duration.ofSeconds(Math.max(1, maxSeconds.getAsInt()))) > 0)
      throw new IllegalArgumentException("errors.input");
    prune();
    if (entries.size() >= 4096
        || entries.stream().filter(e -> e.player.equals(player)).count()
            >= Math.max(1, perPlayer.getAsInt())) throw new IllegalStateException("override-limit");
    var entry = new Entry(owner, player, priority, clock.getAsLong() + ttl.toNanos(), force, board);
    entries.add(entry);
    return entry;
  }

  Optional<Board> current(UUID player, boolean personalEnabled, boolean allowForced) {
    MainThread.require();
    prune();
    return entries.stream()
        .filter(e -> e.player.equals(player) && (personalEnabled || allowForced && e.force))
        .max(Comparator.<Entry>comparingInt(e -> e.priority).thenComparingLong(e -> e.sequence))
        .map(e -> e.board);
  }

  private void prune() {
    long now = clock.getAsLong();
    entries.removeIf(e -> now - e.expires >= 0 || !e.owner.isEnabled());
  }

  void removePlayer(UUID player) {
    MainThread.require();
    entries.removeIf(e -> e.player.equals(player));
  }

  void removeOwner(Plugin owner) {
    MainThread.require();
    entries.removeIf(e -> e.owner == owner);
  }

  void close() {
    MainThread.require();
    closed = true;
    entries.clear();
  }
}
