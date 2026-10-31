package dev.lovelace.loveessential.module;

/**
 * Circular navigation through the sections of one statistics button: after the last section comes
 * the first, before the first comes the last. No server types, so it is tested on its own.
 */
public final class StatCycle {
  private StatCycle() {}

  /** The section after {@code current}, wrapping to the first. */
  public static int next(int current, int size) {
    return size <= 0 ? 0 : Math.floorMod(clamp(current, size) + 1, size);
  }

  /** The section before {@code current}, wrapping to the last. */
  public static int previous(int current, int size) {
    return size <= 0 ? 0 : Math.floorMod(clamp(current, size) - 1, size);
  }

  /** A remembered position kept inside the list, which may have grown shorter since (a plugin went away). */
  public static int clamp(int current, int size) {
    return size <= 0 ? 0 : Math.max(0, Math.min(current, size - 1));
  }
}
