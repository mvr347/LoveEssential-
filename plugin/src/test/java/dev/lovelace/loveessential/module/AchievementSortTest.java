package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AchievementSortTest {
  private static final List<String> ENTRIES = List.of("c", "a", "d", "b");
  private static final Set<String> OWNED = Set.of("a", "b");
  private static final Map<String, Integer> RARITY = Map.of("a", 0, "b", 3, "c", 1, "d", 3);

  private List<String> sorted(AchievementSort.Sort sort) {
    return AchievementSort.apply(sort, ENTRIES, OWNED::contains, RARITY::get, s -> s);
  }

  @Test
  void defaultKeepsTheCatalogueOrder() {
    assertEquals(ENTRIES, sorted(AchievementSort.Sort.DEFAULT));
  }

  @Test
  void rarityPutsTheRarestFirstAndKeepsTiesStable() {
    assertEquals(List.of("d", "b", "c", "a"), sorted(AchievementSort.Sort.RARITY));
  }

  @Test
  void nameSortsAlphabetically() {
    assertEquals(List.of("a", "b", "c", "d"), sorted(AchievementSort.Sort.NAME));
  }

  @Test
  void ownedComeFirstAndKeepTheirOrder() {
    assertEquals(List.of("a", "b", "c", "d"), sorted(AchievementSort.Sort.OWNED));
  }
}
