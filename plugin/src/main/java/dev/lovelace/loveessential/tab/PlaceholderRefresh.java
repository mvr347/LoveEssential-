package dev.lovelace.loveessential.tab;

import java.util.Map;

/**
 * How long a resolved PlaceholderAPI value may be reused. Expensive placeholders can be refreshed
 * less often than the default; cheap ones can be refreshed more often. Pure.
 */
public final class PlaceholderRefresh {
  /** Lower bound: below this a single tick of 50 ms already cannot be told apart from no cache. */
  public static final long MIN_MILLIS = 50;

  /** Upper bound: five minutes is the longest value that still reads as live on a TAB list. */
  public static final long MAX_MILLIS = 300_000;

  private final long defaultMillis;
  private final Map<String, Long> perPlaceholder;

  public PlaceholderRefresh(long defaultMillis, Map<String, Long> perPlaceholder) {
    this.defaultMillis = Math.clamp(defaultMillis, MIN_MILLIS, MAX_MILLIS);
    this.perPlaceholder = Map.copyOf(perPlaceholder);
  }

  /** Time to live of one placeholder token such as {@code %player_ping%}. */
  public long ttl(String placeholder) {
    return perPlaceholder.getOrDefault(placeholder, defaultMillis);
  }

  public long defaultMillis() {
    return defaultMillis;
  }

  public static boolean valid(long millis) {
    return millis >= MIN_MILLIS && millis <= MAX_MILLIS;
  }
}
