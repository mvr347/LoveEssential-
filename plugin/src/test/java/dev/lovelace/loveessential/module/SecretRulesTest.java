package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SecretRulesTest {
  @Test
  void barIsEmptyOnlyWhenNothingIsFound() {
    assertEquals(0, SecretRules.filled(0, 8));
    assertEquals(2, SecretRules.filled(1, 8));
    assertEquals(8, SecretRules.filled(4, 8));
    assertEquals(SecretRules.BAR, SecretRules.filled(8, 8));
    assertEquals(1, SecretRules.filled(1, 1000), "one find still shows a cell");
  }

  @Test
  void barCopesWithAnEmptyCatalogueAndTooManyFinds() {
    assertEquals(0, SecretRules.filled(3, 0));
    assertEquals(SecretRules.BAR, SecretRules.filled(20, 8));
  }

  @Test
  void textWrapsAtSpacesAndKeepsLongWordsWhole() {
    assertEquals(List.of("один два", "три четыре"), SecretRules.wrap("один два три четыре", 10));
    assertEquals(List.of("словослишкомдлинное", "и"), SecretRules.wrap("словослишкомдлинное и", 5));
    assertEquals(List.of(), SecretRules.wrap("   ", 10));
    assertEquals(List.of(), SecretRules.wrap(null, 10));
  }
}
