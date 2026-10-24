package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExternalTabTest {
  @Test
  void autoStaysPassiveOnlyWhenAListedPluginIsEnabled() {
    assertFalse(ExternalTab.deferred(ExternalTab.Mode.AUTO, ExternalTab.DEFAULTS, n -> false));
    assertTrue(ExternalTab.deferred(ExternalTab.Mode.AUTO, ExternalTab.DEFAULTS, Set.of("CMI")::contains));
    assertTrue(ExternalTab.deferred(ExternalTab.Mode.AUTO, ExternalTab.DEFAULTS, Set.of("TAB")::contains));
    assertFalse(ExternalTab.deferred(ExternalTab.Mode.AUTO, List.of("TAB"), Set.of("Essentials")::contains));
  }

  @Test
  void onAlwaysTakesOverAndOffNeverDoes() {
    assertFalse(ExternalTab.deferred(ExternalTab.Mode.ON, ExternalTab.DEFAULTS, n -> true));
    assertTrue(ExternalTab.deferred(ExternalTab.Mode.OFF, ExternalTab.DEFAULTS, n -> false));
  }

  @Test
  void modesParseLeniently() {
    assertEquals(ExternalTab.Mode.AUTO, ExternalTab.Mode.parse(null));
    assertEquals(ExternalTab.Mode.AUTO, ExternalTab.Mode.parse("nonsense"));
    assertEquals(ExternalTab.Mode.ON, ExternalTab.Mode.parse(" ON "));
    assertEquals(ExternalTab.Mode.OFF, ExternalTab.Mode.parse("false"));
  }

  @Test
  void foundNamesTheFirstEnabledPlugin() {
    assertEquals("CMI", ExternalTab.found(List.of("TAB", "CMI"), Set.of("CMI")::contains));
    assertNull(ExternalTab.found(List.of("TAB"), n -> false));
  }
}
