package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NightSkipTest {
  @Test
  void nothingHappensBelowTheRequiredShare() {
    assertEquals(0, Gameplay.nightExtra(0, 4, 50, 30));
    assertEquals(0, Gameplay.nightExtra(1, 4, 50, 30));
  }

  @Test
  void halfOfThePlayersAsleepStartsTheSkip() {
    assertEquals(116, Gameplay.nightExtra(2, 4, 50, 30));
    assertEquals(116, Gameplay.nightExtra(4, 4, 50, 30));
  }

  @Test
  void aLoneSleeperCountsWhenEveryoneElseIsAway() {
    assertEquals(116, Gameplay.nightExtra(1, 1, 50, 30));
  }

  @Test
  void aSpeedOfOneChangesNothing() {
    assertEquals(0, Gameplay.nightExtra(2, 2, 50, 1));
  }
}
