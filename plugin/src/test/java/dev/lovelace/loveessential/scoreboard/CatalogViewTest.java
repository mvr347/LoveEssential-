package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.scoreboard.CatalogView.Row;
import dev.lovelace.loveessential.scoreboard.CatalogView.Sort;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogViewTest {
  static final List<Row> ROWS =
      List.of(
          new Row("coords", "Координаты", "general", 0, -1, true),
          new Row("ping", "Пинг", "general", 0, 0, true),
          new Row("kills", "Убийства", "combat", 1, -1, true),
          new Row("clan", "Клан", "clans", 2, -1, false),
          new Row("arena", "Арена", "combat", 1, 1, true));

  @Test
  void theRingStepsForwardAndBackwardAndWraps() {
    assertEquals(1, CatalogView.step(0, 3, true));
    assertEquals(0, CatalogView.step(2, 3, true));
    assertEquals(2, CatalogView.step(0, 3, false));
    assertEquals(0, CatalogView.step(5, 0, true));
    assertEquals(Sort.AVAILABLE, CatalogView.step(Sort.NAME, Sort.values(), true));
    assertEquals(Sort.ENABLED, CatalogView.step(Sort.NAME, Sort.values(), false));
  }

  @Test
  void theFilterKeepsOneCategoryOrAllOfThem() {
    assertEquals(5, CatalogView.apply(ROWS, "", Sort.NAME).size());
    assertEquals(List.of("arena", "kills"), CatalogView.apply(ROWS, "combat", Sort.NAME));
    assertEquals(List.of(), CatalogView.apply(ROWS, "nothing", Sort.NAME));
  }

  @Test
  void sortByNameIsAlphabeticalTheRussianWay() {
    assertEquals(List.of("arena", "clan", "coords", "ping", "kills"), CatalogView.apply(ROWS, "", Sort.NAME));
  }

  @Test
  void availableFirstPutsLockedLinesLast() {
    assertEquals("clan", CatalogView.apply(ROWS, "", Sort.AVAILABLE).getLast());
  }

  @Test
  void categoryOrderFollowsTheConfiguration() {
    assertEquals(List.of("coords", "ping", "arena", "kills", "clan"), CatalogView.apply(ROWS, "", Sort.CATEGORY));
  }

  @Test
  void enabledFirstKeepsTheOrderOfTheBoard() {
    var sorted = CatalogView.apply(ROWS, "", Sort.ENABLED);
    assertEquals(List.of("ping", "arena"), sorted.subList(0, 2));
    assertEquals("clan", sorted.getLast());
  }
}
