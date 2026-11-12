package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalizationSectionsTest {
  @Test
  void sectionsSitOnFixedSlotsInTheOwnersOrder() {
    assertEquals(
        List.of(
            PersonalizationSections.Section.NICK,
            PersonalizationSections.Section.MARKS,
            PersonalizationSections.Section.DISPLAY,
            PersonalizationSections.Section.JOINQUIT),
        List.of(PersonalizationSections.Section.values()));
    assertEquals(
        List.of(2, 3, 4, 5),
        java.util.Arrays.stream(PersonalizationSections.Section.values())
            .map(PersonalizationSections.Section::slot)
            .toList());
  }

  @Test
  void filterAndSortFollowTheSectionsInsideTheHeaderZone() {
    var slots = new HashSet<Integer>();
    for (var section : PersonalizationSections.Section.values()) slots.add(section.slot());
    slots.add(PersonalizationSections.FILTER_SLOT);
    slots.add(PersonalizationSections.SORT_SLOT);
    assertEquals(6, slots.size());
    for (int slot : slots) assertTrue(slot >= 2 && slot <= 7, "outside 2-7: " + slot);
  }
}
