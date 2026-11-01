package dev.lovelace.loveessential.calamity;

/** Real-time calendar, separate from the game-day counters used by player illnesses. */
final class CalamityClock {
  static final long DAY_TICKS = 86_400_000L / 50;
  private CalamityClock() {}

  static long elapsedTicks(long previousMillis, long nowMillis) {
    return nowMillis > previousMillis ? (nowMillis - previousMillis) / 50 : 0;
  }

  static long deadline(long nowMillis, double days) {
    return nowMillis + Math.round(Math.max(0, days) * 86_400_000L);
  }
}
