package dev.lovelace.loveessential.module;

import java.util.Random;
import java.util.function.IntPredicate;

/**
 * Candidate geometry for {@code /rtp}: plain arithmetic with no Bukkit types, so it runs in unit
 * tests without a server. Block columns are returned as {@code {x, z}} in whole blocks.
 */
final class RandomTeleportGeometry {
  /** Square sampling draws this many times before it gives up on a ring it cannot hit. */
  static final int MAX_DRAWS = 10_000;

  private RandomTeleportGeometry() {}

  /**
   * A column between {@code minRadius} and {@code maxRadius} around {@code (cx, cz)}, or null
   * when a square ring could not be hit within {@link #MAX_DRAWS} draws.
   *
   * <p>Circle: the radius is drawn by area, {@code r² = u·(R² − rMin²) + rMin²}, so points do not
   * bunch up next to the centre. Square: the distance is Chebyshev (the larger of |dx| and |dz|), so
   * min-radius is the half-side of the inner square that is excluded.
   */
  static int[] sample(
      boolean circle, double cx, double cz, double minRadius, double maxRadius, Random random) {
    if (circle) {
      double inner = minRadius * minRadius;
      double r = Math.sqrt(random.nextDouble() * (maxRadius * maxRadius - inner) + inner);
      double angle = random.nextDouble() * Math.TAU;
      return block(cx + r * Math.cos(angle), cz + r * Math.sin(angle));
    }
    for (int i = 0; i < MAX_DRAWS; i++) {
      double dx = (2 * random.nextDouble() - 1) * maxRadius;
      double dz = (2 * random.nextDouble() - 1) * maxRadius;
      if (Math.max(Math.abs(dx), Math.abs(dz)) >= minRadius) return block(cx + dx, cz + dz);
    }
    return null;
  }

  /**
   * True when the block column lies inside the world border, keeping {@code margin} blocks away
   * from its edge. {@code size} is the border's full width, as Bukkit reports it.
   */
  static boolean insideBorder(
      int x, int z, double borderX, double borderZ, double size, double margin) {
    double half = size / 2 - margin;
    return Math.abs(x + .5 - borderX) <= half && Math.abs(z + .5 - borderZ) <= half;
  }

  static boolean inRange(int y, int minY, int maxY) {
    return y >= minY && y <= maxY;
  }

  /**
   * The first {@code y} from {@code top} down to {@code bottom} where {@code standable} holds, or
   * null. Used under a Nether ceiling, where the surface is not the highest block.
   */
  static Integer highestStandable(int top, int bottom, IntPredicate standable) {
    for (int y = top; y >= bottom; y--) if (standable.test(y)) return y;
    return null;
  }

  private static int[] block(double x, double z) {
    return new int[] {(int) Math.floor(x), (int) Math.floor(z)};
  }
}
