package dev.lovelace.loveessential.module;

import dev.lovelace.lovecore.api.secrets.SecretBook;
import dev.lovelace.lovecore.api.secrets.SecretDiscoveredEvent;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;

/**
 * The LoveCore {@link SecretBook} contract on top of the achievements: every secret is an achievement of the
 * category «secret» with the id {@code secret_<id>} (hidden until found), so the plugins that call
 * {@link #discover} keep working unchanged and the secrets show up in {@code /achievements} next to everything else.
 * What the old book stored (one document per player) is turned into achievements once, by
 * {@link AchievementService}, the first time a player's progress is loaded.
 */
public final class Secrets implements SecretBook {
  public static final String ID = "loveessential";

  /** Stored form of the old book: secret id to when it was found (epoch milliseconds); read only for the migration. */
  public record Stored(Map<String, Long> found) {}

  private final LoveEssentialPlugin plugin;
  private final AchievementService achievements;

  public Secrets(LoveEssentialPlugin plugin, AchievementService achievements) {
    this.plugin = plugin;
    this.achievements = achievements;
  }

  @Override
  public String providerId() {
    return ID;
  }

  /** Whether this player's progress has arrived from the database. */
  public boolean loaded(UUID player) {
    return achievements.loaded(player);
  }

  @Override
  public boolean discover(UUID player, String secretId) {
    MainThread.require();
    var online = Bukkit.getPlayer(player);
    // Nothing is recorded before the progress has been read, as the contract always promised.
    if (online == null || secretId == null || !plugin.module("secrets") || !achievements.loaded(player))
      return false;
    if (!achievements.unlock(player, AchievementService.SECRET_PREFIX + secretId)) return false;
    Bukkit.getPluginManager().callEvent(new SecretDiscoveredEvent(online, secretId));
    return true;
  }

  @Override
  public Set<String> found(UUID player) {
    return achievements.unlockedIds(player).stream()
        .filter(id -> id.startsWith(AchievementService.SECRET_PREFIX))
        .map(id -> id.substring(AchievementService.SECRET_PREFIX.length()))
        .collect(Collectors.toUnmodifiableSet());
  }

  /** Placeholder values: {@code secrets} (found) and {@code secrets_total}. */
  public Map<String, String> values(UUID player) {
    int total = plugin.module("secrets") ? (int) secretIds().count() : 0;
    int found =
        (int)
            found(player).stream()
                .filter(id -> achievements.catalog().contains(AchievementService.SECRET_PREFIX + id))
                .count();
    return Map.of("secrets", Integer.toString(found), "secrets_total", Integer.toString(total));
  }

  private java.util.stream.Stream<String> secretIds() {
    return achievements.catalog().ids().stream().filter(id -> id.startsWith(AchievementService.SECRET_PREFIX));
  }
}
