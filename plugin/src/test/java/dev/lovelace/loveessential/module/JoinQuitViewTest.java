package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JoinQuitViewTest {
  private static final List<String> CATALOGUE = List.of("Зверь", "Алхимик", "Бард", "Воин");
  private static final Set<String> OPEN = Set.of("Зверь", "Бард");

  private List<String> view(JoinQuitView.Mode mode) {
    return JoinQuitView.apply(mode, CATALOGUE, OPEN::contains, s -> s);
  }

  @Test
  void allKeepsTheCatalogueOrder() {
    assertEquals(CATALOGUE, view(JoinQuitView.Mode.ALL));
  }

  @Test
  void availableAndLockedSplitTheCatalogue() {
    assertEquals(List.of("Зверь", "Бард"), view(JoinQuitView.Mode.AVAILABLE));
    assertEquals(List.of("Алхимик", "Воин"), view(JoinQuitView.Mode.LOCKED));
  }

  @Test
  void nameSortsEverything() {
    assertEquals(List.of("Алхимик", "Бард", "Воин", "Зверь"), view(JoinQuitView.Mode.NAME));
  }
}
