package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JoinQuitViewTest {
  private static final List<String> CATALOGUE = List.of("Зверь", "Алхимик", "Бард", "Воин");
  private static final Set<String> OPEN = Set.of("Зверь", "Бард");

  private List<String> view(JoinQuitView.Filter filter, JoinQuitView.Sort sort) {
    return JoinQuitView.apply(filter, sort, CATALOGUE, OPEN::contains, s -> s);
  }

  @Test
  void allKeepsTheCatalogueOrder() {
    assertEquals(CATALOGUE, view(JoinQuitView.Filter.ALL, JoinQuitView.Sort.CATALOG));
  }

  @Test
  void availableAndLockedSplitTheCatalogue() {
    assertEquals(List.of("Зверь", "Бард"), view(JoinQuitView.Filter.AVAILABLE, JoinQuitView.Sort.CATALOG));
    assertEquals(List.of("Алхимик", "Воин"), view(JoinQuitView.Filter.LOCKED, JoinQuitView.Sort.CATALOG));
  }

  @Test
  void nameSortsWithinTheFilter() {
    assertEquals(
        List.of("Алхимик", "Бард", "Воин", "Зверь"), view(JoinQuitView.Filter.ALL, JoinQuitView.Sort.NAME));
    assertEquals(List.of("Бард", "Зверь"), view(JoinQuitView.Filter.AVAILABLE, JoinQuitView.Sort.NAME));
  }
}
