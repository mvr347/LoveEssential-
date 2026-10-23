package dev.lovelace.loveessential.api;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;

/**
 * Temporary TAB changes for other plugins: name and nametag text, nametag visibility and a bossbar.
 * Every lease expires on its TTL, when the player leaves, or when the owner plugin is disabled.
 * All calls require the server thread.
 */
public interface TabService {
  /** Idempotent; immediately releases this request. */
  interface Lease extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Overrides some of {@code tabprefix}, {@code tabsuffix}, {@code customtabname}, {@code
   * tagprefix} and {@code tagsuffix} for {@code player}. Highest priority wins; for equal priority
   * the newest lease wins. Priority must be within -1000..1000. TTL must be positive and within
   * the configured maximum.
   */
  Lease properties(
      Plugin owner, UUID player, int priority, Duration ttl, Map<String, String> values);

  /** Hides the nametag of {@code player} from every viewer for the TTL. */
  Lease hideNametag(Plugin owner, UUID player, Duration ttl);

  /**
   * Shows a bossbar to {@code viewer} for the TTL. It replaces the configured bossbars while it
   * lasts; among several leases the highest priority is shown.
   */
  Lease bossBar(
      Plugin owner,
      UUID viewer,
      int priority,
      Duration ttl,
      Component name,
      float progress,
      BossBar.Color color,
      BossBar.Overlay overlay);
}
