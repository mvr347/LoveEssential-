package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class PersonalLayoutTest {
  @Test
  void controlSlotsAreCenteredInHeaderSlotsTwoToSeven() {
    assertArrayEquals(new int[] {4}, PersonalLayout.controlSlots(1));
    assertArrayEquals(new int[] {2, 4, 6}, PersonalLayout.controlSlots(3));
    assertArrayEquals(new int[] {2, 3, 5, 6}, PersonalLayout.controlSlots(4));
    assertArrayEquals(new int[] {2, 3, 4, 5, 6, 7}, PersonalLayout.controlSlots(6));
    assertThrows(IllegalArgumentException.class, () -> PersonalLayout.controlSlots(7));
  }

  @Test
  void cardsAndCategoriesLiveInTheWorkZoneWithoutTheSideWalls() {
    for (int slot : PersonalLayout.CARD_SLOTS) {
      assertTrue(slot >= 18 && slot < 45, "slot " + slot);
      assertTrue(slot % 9 != 0 && slot % 9 != 8, "side wall " + slot);
    }
    for (int slot : PersonalLayout.FLAT_SLOTS) assertTrue(slot % 9 != 0 && slot % 9 != 8);
    assertEquals(21, PersonalLayout.PAGE_SIZE);
    assertEquals(36, PersonalLayout.PAGE_PREV);
    assertEquals(44, PersonalLayout.PAGE_NEXT);
  }

  @Test
  void cardCountIsClamped() {
    assertEquals(1, PersonalLayout.cardCount(0));
    assertEquals(8, PersonalLayout.cardCount(99));
    assertEquals(5, PersonalLayout.cardCount(5));
  }

  @Test
  void paginationClampsAndCoversEveryItemOnce() {
    assertEquals(1, PersonalLayout.pageCount(0));
    assertEquals(2, PersonalLayout.pageCount(22));
    assertEquals(1, PersonalLayout.clampPage(9, 22));
    assertEquals(0, PersonalLayout.clampPage(-3, 22));
    assertArrayEquals(new int[] {21, 22}, PersonalLayout.pageRange(22, 1));
  }

  @Test
  void submenuSizesFollowTheStandardSizesAndSkip45() {
    assertEquals(27, PersonalLayout.submenuSize(5));
    assertEquals(27, PersonalLayout.submenuSize(7));
    assertEquals(36, PersonalLayout.submenuSize(8));
    assertEquals(36, PersonalLayout.submenuSize(14));
    assertEquals(54, PersonalLayout.submenuSize(15));
    assertEquals(54, PersonalLayout.submenuSize(40));
  }

  @Test
  void submenuSlotsAreDistinctCenteredAndInsideTheWorkZone() {
    for (int count = 1; count <= 21; count++) {
      int size = PersonalLayout.submenuSize(count);
      int[] slots = PersonalLayout.submenuSlots(size, count);
      assertEquals(count, slots.length);
      assertEquals(count, new HashSet<>(Arrays.stream(slots).boxed().toList()).size());
      for (int slot : slots) {
        assertTrue(slot >= PersonalLayout.workStart(size) && slot < size - 9, "count " + count);
        assertTrue(slot % 9 != 0 && slot % 9 != 8, "side wall at count " + count);
      }
    }
    // Nine items are spread 5 + 4, not 7 + 2.
    int[] nine = PersonalLayout.submenuSlots(36, 9);
    assertEquals(5, Arrays.stream(nine).filter(slot -> slot < 18).count());
    assertEquals(4, Arrays.stream(nine).filter(slot -> slot >= 18).count());
  }
}
