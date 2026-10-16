package dev.lovelace.loveessential.api;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/** Immutable world reference; resolve only on the server thread. */
public record Position(UUID world, double x, double y, double z, float yaw, float pitch) {
  public Position {
    if (world == null
        || !Double.isFinite(x)
        || !Double.isFinite(y)
        || !Double.isFinite(z)
        || !Float.isFinite(yaw)
        || !Float.isFinite(pitch)
        || Math.abs(x) > 30_000_000
        || Math.abs(z) > 30_000_000) throw new IllegalArgumentException("invalid-position");
  }

  public static Position of(Location l) {
    return new Position(
        l.getWorld().getUID(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
  }

  public Location location() {
    var w = Bukkit.getWorld(world);
    if (w == null) throw new IllegalStateException("world-unavailable");
    return new Location(w, x, y, z, yaw, pitch);
  }
}
