package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The achievements a player pins to their profile (up to {@link #MAX}); other players see them when they open that
 * profile. Kept in memory for online players and saved in the profile table, so a pin survives a restart. A pin
 * whose achievement is gone from the catalogue, or no longer unlocked, is simply not shown.
 */
public final class AchievementFavorites implements Listener, AutoCloseable {
  public static final int MAX = 3;
  static final String KEY = "loveessential.achievement.favorites";

  private final LoveEssentialPlugin plugin;
  private final SqliteStore store;
  private final AchievementService achievements;
  private final Map<UUID, List<String>> cache = new ConcurrentHashMap<>();

  public AchievementFavorites(
      LoveEssentialPlugin plugin, SqliteStore store, AchievementService achievements) {
    this.plugin = plugin;
    this.store = store;
    this.achievements = achievements;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    for (var p : Bukkit.getOnlinePlayers()) load(p.getUniqueId());
  }

  @Override
  public void close() {
    org.bukkit.event.HandlerList.unregisterAll(this);
    cache.clear();
  }

  private void load(UUID id) {
    store
        .profile(id, KEY)
        .whenComplete(
            (value, error) -> {
              // On a read failure the player simply has no pins; nothing here may block a join.
              if (error != null || value.isEmpty()) return;
              cache.putIfAbsent(id, FavoriteRules.parse(value.get(), MAX));
            });
  }

  /** Pinned achievements that still exist and are still unlocked, in the order the player chose. */
  public List<String> of(UUID id) {
    var saved = cache.getOrDefault(id, List.of());
    var unlocked = achievements.unlockedIds(id);
    return saved.stream()
        .filter(unlocked::contains)
        .filter(a -> achievements.catalog().find(a).isPresent())
        .toList();
  }

  /** Whether the player has any achievement at all: without one the profile shows no favourite slots. */
  public boolean hasAny(UUID id) {
    return !achievements.unlockedIds(id).isEmpty();
  }

  public java.util.Optional<AchievementCatalog.Def> def(String id) {
    return achievements.catalog().find(id);
  }

  public boolean isFavorite(UUID id, String achievement) {
    return of(id).contains(achievement);
  }

  /** Pins or unpins; the cache changes only after the write succeeded. Only an unlocked achievement can be pinned. */
  public CompletableFuture<FavoriteRules.Result> toggle(Player p, String achievement) {
    MainThread.require();
    UUID id = p.getUniqueId();
    if (!achievements.unlocked(id, achievement) || achievements.catalog().find(achievement).isEmpty())
      return CompletableFuture.failedFuture(new IllegalArgumentException("errors.permission"));
    var toggled = FavoriteRules.toggle(of(id), achievement, MAX);
    if (toggled.result() == FavoriteRules.Result.FULL)
      return CompletableFuture.completedFuture(FavoriteRules.Result.FULL);
    return store
        .profile(id, KEY, FavoriteRules.serialize(toggled.favorites()))
        .thenApply(
            done -> {
              cache.put(id, toggled.favorites());
              return toggled.result();
            });
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    load(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    if (!plugin.isEnabled()) return;
    Bukkit.getScheduler().runTask(plugin, () -> cache.remove(id));
  }
}
