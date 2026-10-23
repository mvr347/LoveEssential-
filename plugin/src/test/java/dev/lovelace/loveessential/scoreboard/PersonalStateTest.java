package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.scoreboard.PersonalState.Entry;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalStateTest {
  @Test
  void linesKeepTheirOrderAndStayWithinTheLimit() {
    var state = new PersonalState();
    assertTrue(state.enable("a", 2));
    assertTrue(state.enable("b", 2));
    assertFalse(state.enable("c", 2), "the third line does not fit");
    assertFalse(state.enable("a", 2), "no duplicates");
    assertEquals(List.of("a", "b"), state.ids());
  }

  @Test
  void movingAndRemovingKeepsTheCardsGapFree() {
    var state = new PersonalState();
    for (String id : List.of("a", "b", "c")) state.enable(id, 8);
    assertFalse(state.moveUp("a"));
    assertTrue(state.moveUp("c"));
    assertEquals(List.of("a", "c", "b"), state.ids());
    assertFalse(state.moveDown("b"));
    assertTrue(state.moveDown("a"));
    assertEquals(List.of("c", "a", "b"), state.ids());
    assertTrue(state.disable("c"));
    assertEquals(List.of("a", "b"), state.ids());
    assertFalse(state.disable("zzz"));
  }

  @Test
  void variantsCycleWrapAndForgetWhenTheLineIsRemoved() {
    var state = new PersonalState();
    state.enable("t", 8);
    assertEquals(1, state.cycleVariant("t", 3));
    assertEquals(2, state.cycleVariant("t", 3));
    assertEquals(0, state.cycleVariant("t", 3));
    assertEquals(0, state.cycleVariant("t", 1), "one variant stays on it");
    state.cycleVariant("t", 3);
    state.disable("t");
    assertEquals(0, state.variant("t"));
  }

  @Test
  void normalizeFallsBackToTheFirstVariant() {
    assertEquals(0, PersonalState.normalize(5, 3));
    assertEquals(0, PersonalState.normalize(-1, 3));
    assertEquals(2, PersonalState.normalize(2, 3));
    assertEquals(0, PersonalState.normalize(1, 0));
  }

  @Test
  void theStoredFormRoundTripsAndDropsDuplicates() {
    var state = new PersonalState();
    state.enable("a", 8);
    state.enable("b", 8);
    state.cycleVariant("b", 2);
    state.viewAll(true);
    var copy = new PersonalState(state.stored());
    assertEquals(state.entries(), copy.entries());
    assertTrue(copy.viewAll());
    var messy = new PersonalState(new PersonalState.Stored(false, List.of(new Entry("a", 0), new Entry("a", 1), new Entry("b", 2))));
    assertEquals(List.of("a", "b"), messy.ids());
    assertEquals(0, messy.variant("a"));
  }

  @Test
  void clampingAndRetainingDropTheTail() {
    var state = new PersonalState();
    for (String id : List.of("a", "b", "c", "d")) state.enable(id, 8);
    state.clampTo(2);
    assertEquals(List.of("a", "b"), state.ids());
    state.retain(id -> id.equals("b"));
    assertEquals(List.of("b"), state.ids());
  }
}
