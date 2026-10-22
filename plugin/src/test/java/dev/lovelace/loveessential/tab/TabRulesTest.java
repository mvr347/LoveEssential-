package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Animation frames, cache TTLs and output replacements: the pure rules behind the TAB text. */
class TabRulesTest {
  @Test
  void animationFramesChangeOnTheConfiguredInterval() {
    assertEquals(0, TabAnimations.frame(0, 500, 2));
    assertEquals(0, TabAnimations.frame(499, 500, 2));
    assertEquals(1, TabAnimations.frame(500, 500, 2));
    assertEquals(0, TabAnimations.frame(1000, 500, 2));
    // Frames wrap forever; a single frame never changes.
    assertEquals(2, TabAnimations.frame(1000 * 60 * 60 * 5 + 1000, 500, 3));
    assertEquals(0, TabAnimations.frame(12345, 500, 1));
    assertEquals(0, TabAnimations.frame(12345, 500, 0));
  }

  @Test
  void animationIntervalIsClampedSoItCannotFlickerOrFreeze() {
    // 1 ms would flicker, so the frame moves at the 50 ms floor.
    assertEquals(1, TabAnimations.frame(60, 1, 2));
    assertEquals(0, TabAnimations.frame(60, 10_000_000, 2));
    assertTrue(TabAnimations.MIN_INTERVAL_MS < TabAnimations.MAX_INTERVAL_MS);
  }

  @Test
  void placeholderTtlFallsBackToTheDefaultAndIsClamped() {
    var refresh = new PlaceholderRefresh(200, Map.of("%player_ping%", 1000L));
    assertEquals(1000, refresh.ttl("%player_ping%"));
    assertEquals(200, refresh.ttl("%player_health%"));
    assertEquals(PlaceholderRefresh.MIN_MILLIS, new PlaceholderRefresh(1, Map.of()).defaultMillis());
    assertEquals(PlaceholderRefresh.MAX_MILLIS, new PlaceholderRefresh(9_000_000, Map.of()).defaultMillis());
    assertTrue(PlaceholderRefresh.valid(200));
    assertFalse(PlaceholderRefresh.valid(10));
  }

  @Test
  void outputReplacementsPreferExactValuesThenRangesThenElse() {
    var table =
        PlaceholderOutput.parse(
            Map.of(
                "survival", "Выживание",
                "0-10", "мало",
                "11-20", "средне",
                "else", "много"));
    assertEquals("Выживание", PlaceholderOutput.apply(table, "survival"));
    assertEquals("мало", PlaceholderOutput.apply(table, "7"));
    assertEquals("мало", PlaceholderOutput.apply(table, "0"));
    assertEquals("много", PlaceholderOutput.apply(table, "10.5"));
    assertEquals("средне", PlaceholderOutput.apply(table, "11.5"));
    assertEquals("много", PlaceholderOutput.apply(table, "99"));
    // Text that is neither listed nor numeric falls through to else as well.
    assertEquals("много", PlaceholderOutput.apply(table, "creative"));
  }

  @Test
  void unmatchedValuesAreShownUnchangedWithoutElse() {
    var table = PlaceholderOutput.parse(Map.of("1-2", "low"));
    assertEquals("5", PlaceholderOutput.apply(table, "5"));
    assertEquals("", PlaceholderOutput.apply(table, null));
  }

  @Test
  void invertedRangesAreRejectedAtLoad() {
    assertThrows(IllegalArgumentException.class, () -> PlaceholderOutput.parse(Map.of("10-0", "x")));
  }
}
