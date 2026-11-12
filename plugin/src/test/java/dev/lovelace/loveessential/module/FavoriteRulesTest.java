package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FavoriteRulesTest {
  @Test
  void parseDropsBlankDuplicateAndSurplusIds() {
    assertEquals(List.of("a", "b", "c"), FavoriteRules.parse(" a, b ,,a,c,d", 3));
    assertEquals(List.of(), FavoriteRules.parse(null, 3));
    assertEquals(List.of(), FavoriteRules.parse("", 3));
  }

  @Test
  void serializeRoundTrips() {
    var list = List.of("hunt_1", "secret_toast");
    assertEquals(list, FavoriteRules.parse(FavoriteRules.serialize(list), 3));
  }

  @Test
  void toggleAddsRemovesAndRefusesWhenFull() {
    var added = FavoriteRules.toggle(List.of("a"), "b", 3);
    assertEquals(FavoriteRules.Result.ADDED, added.result());
    assertEquals(List.of("a", "b"), added.favorites());

    var removed = FavoriteRules.toggle(added.favorites(), "a", 3);
    assertEquals(FavoriteRules.Result.REMOVED, removed.result());
    assertEquals(List.of("b"), removed.favorites());

    var full = FavoriteRules.toggle(List.of("a", "b", "c"), "d", 3);
    assertEquals(FavoriteRules.Result.FULL, full.result());
    assertEquals(List.of("a", "b", "c"), full.favorites());
  }

  @Test
  void aFullListStillLetsYouRemove() {
    var removed = FavoriteRules.toggle(List.of("a", "b", "c"), "b", 3);
    assertEquals(FavoriteRules.Result.REMOVED, removed.result());
    assertEquals(List.of("a", "c"), removed.favorites());
  }
}
