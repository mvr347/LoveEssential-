package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StatCycleTest {
  @Test
  void forwardWrapsFromTheLastSectionToTheFirst() {
    assertEquals(1, StatCycle.next(0, 4));
    assertEquals(3, StatCycle.next(2, 4));
    assertEquals(0, StatCycle.next(3, 4));
  }

  @Test
  void backwardWrapsFromTheFirstSectionToTheLast() {
    assertEquals(3, StatCycle.previous(0, 4));
    assertEquals(0, StatCycle.previous(1, 4));
  }

  @Test
  void aSingleSectionStaysPut() {
    assertEquals(0, StatCycle.next(0, 1));
    assertEquals(0, StatCycle.previous(0, 1));
  }

  @Test
  void aRememberedPositionPastTheEndIsPulledBack() {
    // A plugin went away and its sections with it: the list is now shorter than the saved position.
    assertEquals(1, StatCycle.clamp(5, 2));
    assertEquals(0, StatCycle.clamp(-3, 2));
    assertEquals(0, StatCycle.next(9, 3), "from the clamped last section the next one is the first");
  }

  @Test
  void noSectionsIsPositionZero() {
    assertEquals(0, StatCycle.next(0, 0));
    assertEquals(0, StatCycle.previous(0, 0));
    assertEquals(0, StatCycle.clamp(4, 0));
  }
}
