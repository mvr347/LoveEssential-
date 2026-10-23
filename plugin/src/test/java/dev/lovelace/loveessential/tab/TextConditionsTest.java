package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextConditionsTest {
  Map<String, String> values = Map.of("%world%", "world_nether", "%ping%", "120", "%name%", "Steve");

  boolean run(TextConditions.Type type, String... checks) {
    var parsed = new java.util.ArrayList<TextConditions.Check>();
    for (String check : checks) parsed.add(TextConditions.parse(check));
    var definition = new TextConditions.Definition(type, parsed, "yes", "no");
    return TextConditions.evaluate(
        definition,
        text -> values.getOrDefault(text, text),
        node -> node.equals("loveessential.vip"),
        id -> id.equals("vip-flag"));
  }

  @Test
  void comparisonsCoverEveryOperator() {
    assertTrue(run(TextConditions.Type.AND, "%world% == world_nether"));
    assertTrue(run(TextConditions.Type.AND, "%world% != world"));
    assertTrue(run(TextConditions.Type.AND, "%ping% >= 100"));
    assertTrue(run(TextConditions.Type.AND, "%ping% <= 120"));
    assertTrue(run(TextConditions.Type.AND, "%ping% > 119"));
    assertTrue(run(TextConditions.Type.AND, "%ping% < 121"));
    assertTrue(run(TextConditions.Type.AND, "%world% contains nether"));
    assertTrue(run(TextConditions.Type.AND, "%world% starts-with world"));
    assertTrue(run(TextConditions.Type.AND, "%world% ends-with _nether"));
    assertFalse(run(TextConditions.Type.AND, "%ping% > 200"));
  }

  @Test
  void numbersCompareAsNumbersAndTextAsTextIgnoringCase() {
    assertTrue(run(TextConditions.Type.AND, "%name% == steve"));
    assertTrue(run(TextConditions.Type.AND, "%ping% == 120.0"));
    // Ordering needs numbers on both sides; a text operand never orders.
    assertFalse(run(TextConditions.Type.AND, "%name% > 5"));
  }

  @Test
  void andRequiresEveryCheckAndOrRequiresOne() {
    assertFalse(run(TextConditions.Type.AND, "%ping% > 1", "%ping% > 1000"));
    assertTrue(run(TextConditions.Type.OR, "%ping% > 1", "%ping% > 1000"));
    assertFalse(run(TextConditions.Type.OR, "%ping% > 1000", "%name% == Alex"));
  }

  @Test
  void permissionAndEngineChecksUseTheirOwnLookups() {
    assertTrue(run(TextConditions.Type.AND, "permission:loveessential.vip"));
    assertFalse(run(TextConditions.Type.AND, "permission:loveessential.admin"));
    assertTrue(run(TextConditions.Type.AND, "engine:vip-flag"));
    assertFalse(run(TextConditions.Type.AND, "engine:other"));
  }

  @Test
  void emptyDefinitionsHaveFixedMeanings() {
    assertTrue(TextConditions.evaluate(
        new TextConditions.Definition(TextConditions.Type.AND, List.of(), "", ""),
        s -> s, n -> false, i -> false));
    assertFalse(TextConditions.evaluate(
        new TextConditions.Definition(TextConditions.Type.OR, List.of(), "", ""),
        s -> s, n -> false, i -> false));
  }

  @Test
  void brokenChecksAreRejectedAtLoadNotSilentlyFalse() {
    for (String bad : Set.of("", "%world%", "permission:", "permission:a b", "engine:", "%a% ~ b", " == x")) {
      assertThrows(IllegalArgumentException.class, () -> TextConditions.parse(bad), bad);
    }
    assertEquals(TextConditions.Type.OR, TextConditions.Type.parse("OR"));
    assertThrows(IllegalArgumentException.class, () -> TextConditions.Type.parse("xor"));
  }
}
