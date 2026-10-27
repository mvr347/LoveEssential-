package dev.lovelace.loveessential.api.mechanics;

import java.util.UUID;

/** Resolve through Bukkit ServicesManager. Query on main; keep the returned immutable snapshot. */
public interface PlayerMechanicsService {
  PlayerMechanics snapshot(UUID player);
}
