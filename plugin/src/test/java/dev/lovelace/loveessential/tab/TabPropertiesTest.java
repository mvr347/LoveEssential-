package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TabPropertiesTest {
  @Test
  void higherLayersWinEachKeyIndependently() {
    var resolved =
        TabProperties.resolve(
            List.of(
                new TabProperties.Layer("user:Steve/world_nether", Map.of("tabprefix", "[N] ")),
                new TabProperties.Layer("user:Steve", Map.of("tabprefix", "[S] ", "tagprefix", "[S] ")),
                new TabProperties.Layer("group:admin/world_nether", Map.of("tagsuffix", "!")),
                new TabProperties.Layer("group:admin", Map.of("tabprefix", "[A] ", "tagsuffix", "?", "header", "H")),
                new TabProperties.Layer("group:_DEFAULT_", Map.of("tabprefix", "", "footer", "F"))));
    assertEquals("[N] ", resolved.value(TabProperties.TAB_PREFIX).orElseThrow());
    assertEquals("[S] ", resolved.value(TabProperties.TAG_PREFIX).orElseThrow());
    assertEquals("!", resolved.value(TabProperties.TAG_SUFFIX).orElseThrow());
    assertEquals("H", resolved.value(TabProperties.HEADER).orElseThrow());
    assertEquals("F", resolved.value(TabProperties.FOOTER).orElseThrow());
    assertEquals("user:Steve/world_nether", resolved.sources().get(TabProperties.TAB_PREFIX));
    assertTrue(resolved.value(TabProperties.CUSTOM_TAB_NAME).isEmpty());
  }

  @Test
  void unknownKeysAreIgnoredAndEmptyValuesStillCount() {
    var resolved =
        TabProperties.resolve(
            List.of(
                new TabProperties.Layer("group:x", Map.of("unknown", "v", "tabsuffix", "")),
                new TabProperties.Layer("group:_DEFAULT_", Map.of("tabsuffix", "<gray>d"))));
    assertEquals("", resolved.value(TabProperties.TAB_SUFFIX).orElseThrow());
    assertFalse(resolved.values().containsKey("unknown"));
  }

  @Test
  void validationRejectsKeysAndValuesThatCouldNotBeApplied() {
    assertDoesNotThrow(() -> TabProperties.validate("tabprefix", "<red>x"));
    assertDoesNotThrow(() -> TabProperties.validate("visibility", "never"));
    assertDoesNotThrow(() -> TabProperties.validate("collision", "false"));
    assertDoesNotThrow(() -> TabProperties.validate("tabprefix", null));
    assertThrows(IllegalArgumentException.class, () -> TabProperties.validate("nonsense", "x"));
    assertThrows(IllegalArgumentException.class, () -> TabProperties.validate("visibility", "hidden"));
    assertThrows(IllegalArgumentException.class, () -> TabProperties.validate("collision", "yes"));
  }
}
