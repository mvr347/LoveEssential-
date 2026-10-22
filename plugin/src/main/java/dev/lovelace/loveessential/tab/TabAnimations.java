package dev.lovelace.loveessential.tab;

/** Frame selection for {@code animations:} in tab.yml. Pure: the caller supplies the clock. */
public final class TabAnimations {
  /** Shortest allowed frame time; faster changes are not readable and only cost packets. */
  public static final long MIN_INTERVAL_MS = 50;

  /** Longest allowed frame time; a slower animation looks frozen. */
  public static final long MAX_INTERVAL_MS = 60_000;

  private TabAnimations() {}

  /** The frame visible at {@code nowMillis}; frames wrap around forever. */
  public static int frame(long nowMillis, long intervalMillis, int frames) {
    if (frames <= 1) return 0;
    long interval = Math.clamp(intervalMillis, MIN_INTERVAL_MS, MAX_INTERVAL_MS);
    return (int) ((Math.max(0, nowMillis) / interval) % frames);
  }
}
