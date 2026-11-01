package dev.lovelace.loveessential.calamity;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class CalamityClockTest {
  @Test void lagAndOfflineTimeCountEvenWithoutServerTicks() {
    assertEquals(1200, CalamityClock.elapsedTicks(1000, 61000));
    assertEquals(14L * CalamityClock.DAY_TICKS, CalamityClock.elapsedTicks(1000, 1000 + 14L * 86400000));
  }
  @Test void clockRollbackDoesNotAdvanceOrRepeatTime() {
    assertEquals(0, CalamityClock.elapsedTicks(61000, 1000));
    assertEquals(1000 + 14L * 86400000, CalamityClock.deadline(1000,14));
  }
}
