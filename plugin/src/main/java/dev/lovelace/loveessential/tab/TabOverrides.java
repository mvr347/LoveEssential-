package dev.lovelace.loveessential.tab;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.TabService;
import java.time.Duration;
import java.util.*;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Ephemeral TAB requests from other plugins; they are never written into tab.yml. */
final class TabOverrides implements TabService {
  /** Only these keys can be overridden through the API; header and footer stay configured. */
  static final Set<String> API_KEYS =
      Set.of(
          TabProperties.TAB_PREFIX,
          TabProperties.TAB_SUFFIX,
          TabProperties.CUSTOM_TAB_NAME,
          TabProperties.TAG_PREFIX,
          TabProperties.TAG_SUFFIX);

  /** The highest-priority bossbar request for a viewer. */
  record Bar(Component name, float progress, BossBar.Color color, BossBar.Overlay overlay) {}

  private enum Kind {
    PROPERTIES,
    HIDE_NAMETAG,
    BOSSBAR
  }

  private final List<Entry> entries = new ArrayList<>();
  private final LongSupplier clock;
  private final IntSupplier maxSeconds, perPlayer;
  private long sequence;
  private boolean closed;

  TabOverrides(LongSupplier clock, IntSupplier maxSeconds, IntSupplier perPlayer) {
    this.clock = clock;
    this.maxSeconds = maxSeconds;
    this.perPlayer = perPlayer;
  }

  private final class Entry implements Lease {
    final Kind kind;
    final Plugin owner;
    final UUID player;
    final int priority;
    final long expires, sequence;
    final Map<String, String> values;
    final Bar bar;

    Entry(
        Kind kind,
        Plugin owner,
        UUID player,
        int priority,
        long expires,
        Map<String, String> values,
        Bar bar) {
      this.kind = kind;
      this.owner = owner;
      this.player = player;
      this.priority = priority;
      this.expires = expires;
      this.values = values;
      this.bar = bar;
      this.sequence = ++TabOverrides.this.sequence;
    }

    @Override
    public void close() {
      MainThread.require();
      entries.remove(this);
    }
  }

  private Entry add(
      Kind kind,
      Plugin owner,
      UUID player,
      int priority,
      Duration ttl,
      Map<String, String> values,
      Bar bar) {
    MainThread.require();
    Objects.requireNonNull(owner);
    Objects.requireNonNull(player);
    Objects.requireNonNull(ttl);
    if (closed
        || !owner.isEnabled()
        || Bukkit.getPluginManager().getPlugin(owner.getName()) != owner
        || Bukkit.getPlayer(player) == null
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
    var entry =
        new Entry(kind, owner, player, priority, clock.getAsLong() + ttl.toNanos(), values, bar);
    entries.add(entry);
    return entry;
  }

  @Override
  public Lease properties(
      Plugin owner, UUID player, int priority, Duration ttl, Map<String, String> values) {
    Objects.requireNonNull(values);
    if (values.isEmpty() || !API_KEYS.containsAll(values.keySet()))
      throw new IllegalArgumentException("errors.input");
    var copy = new HashMap<String, String>();
    for (var value : values.entrySet()) {
      if (value.getValue() == null || value.getValue().length() > 1024)
        throw new IllegalArgumentException("errors.input");
      try {
        MiniMessage.miniMessage().deserialize(value.getValue());
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("errors.input", e);
      }
      copy.put(value.getKey(), value.getValue());
    }
    return add(Kind.PROPERTIES, owner, player, priority, ttl, Map.copyOf(copy), null);
  }

  @Override
  public Lease hideNametag(Plugin owner, UUID player, Duration ttl) {
    return add(Kind.HIDE_NAMETAG, owner, player, 0, ttl, Map.of(), null);
  }

  @Override
  public Lease bossBar(
      Plugin owner,
      UUID viewer,
      int priority,
      Duration ttl,
      Component name,
      float progress,
      BossBar.Color color,
      BossBar.Overlay overlay) {
    Objects.requireNonNull(name);
    Objects.requireNonNull(color);
    Objects.requireNonNull(overlay);
    if (!(progress >= 0 && progress <= 1)) throw new IllegalArgumentException("errors.input");
    return add(
        Kind.BOSSBAR,
        owner,
        viewer,
        priority,
        ttl,
        Map.of(),
        new Bar(name, progress, color, overlay));
  }

  /** Merged property overrides for a player; a higher priority lease wins each key. */
  Map<String, String> properties(UUID player) {
    MainThread.require();
    prune();
    var merged = new HashMap<String, String>();
    entries.stream()
        .filter(e -> e.kind == Kind.PROPERTIES && e.player.equals(player))
        .sorted(Comparator.<Entry>comparingInt(e -> e.priority).reversed().thenComparing(e -> -e.sequence))
        .forEach(e -> e.values.forEach(merged::putIfAbsent));
    return merged;
  }

  boolean nametagHidden(UUID player) {
    MainThread.require();
    prune();
    return entries.stream().anyMatch(e -> e.kind == Kind.HIDE_NAMETAG && e.player.equals(player));
  }

  Optional<Bar> bossBar(UUID viewer) {
    MainThread.require();
    prune();
    return entries.stream()
        .filter(e -> e.kind == Kind.BOSSBAR && e.player.equals(viewer))
        .max(Comparator.<Entry>comparingInt(e -> e.priority).thenComparingLong(e -> e.sequence))
        .map(e -> e.bar);
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
