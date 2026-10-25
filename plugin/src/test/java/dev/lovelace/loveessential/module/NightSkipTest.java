package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NightSkipTest {
  private static long step(long sleeping, long eligible, boolean started, long time) {
    return Gameplay.nightStep(sleeping, eligible, 50, 30, started, time);
  }

  @Test
  void nothingHappensByDayOrBelowTheRequiredShare() {
    assertEquals(0, step(4, 4, false, 6000));
    assertEquals(0, step(0, 4, false, 13000));
    assertEquals(0, step(1, 4, false, 13000));
  }

  @Test
  void halfOfThePlayersAsleepStartsTheSkip() {
    assertEquals(29, step(2, 4, false, 13000));
    assertEquals(29, step(1, 1, false, 13000));
  }

  @Test
  void aStartedSkipGoesOnWhileAnyoneSleeps() {
    assertEquals(29, step(1, 4, true, 13000));
    assertEquals(0, step(0, 4, true, 13000));
  }

  @Test
  void theLastStepEndsExactlyAtTheMorning() {
    assertEquals(10, step(2, 2, true, 23990));
    assertEquals(0, step(2, 2, true, 0));
  }

  @Test
  void aSpeedOfOneChangesNothing() {
    assertEquals(0, Gameplay.nightStep(2, 2, 50, 1, false, 13000));
  }
}
