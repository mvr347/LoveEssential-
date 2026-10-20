package dev.lovelace.loveessential.api;

import java.time.Duration;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit service for temporary Love event/quest/arena sidebars. All calls require the server
 * thread.
 */
public interface ScoreboardService {
  record Board(Component title, List<Component> lines) {
    public Board {
      Objects.requireNonNull(title);
      lines = List.copyOf(lines);
      if (lines.isEmpty() || lines.size() > 15) throw new IllegalArgumentException("errors.input");
    }
  }

  interface Lease extends AutoCloseable {
    /** Replaces this lease's content; does not extend its expiry or change its priority. */
    void update(Board board);

    /** Idempotent; immediately releases this request. Previous personal choices remain intact. */
    @Override
    void close();
  }

  /**
   * Highest priority wins; for equal priority the newest lease wins. TTL must be positive and
   * within the configured maximum. force requests can bypass a personal toggle only when allowed in
   * modules/scoreboard.yml; server world/authentication restrictions always apply. Leases expire
   * automatically on TTL, player disconnect or owner plugin disable.
   */
  Lease show(Plugin owner, UUID player, int priority, Duration ttl, boolean force, Board board);
}
