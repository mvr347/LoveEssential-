package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.display.DisplayPrefs.Element;
import dev.lovelace.lovecore.api.display.DisplayPrefs.Scope;
import dev.lovelace.lovecore.api.display.DisplayPrefs.Surface;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** Pure parts of the personal tag/suffix choice: argument parsing and the bit layout. */
class DisplayPreferencesParseTest {

  @Test
  void parsesEnglishAndRussianWords() {
    var english = DisplayPreferences.parse(new String[] {"own", "clan", "chat", "off"});
    assertEquals(Scope.OWN, english.scope());
    assertEquals(Element.CLAN_TAG, english.element());
    assertEquals(Surface.CHAT, english.surface());
    assertEquals(Boolean.FALSE, english.value());
    var russian = DisplayPreferences.parse(new String[] {"чужие", "суффикс", "ник", "вкл"});
    assertEquals(Scope.OTHERS, russian.scope());
    assertEquals(Element.SUFFIX, russian.element());
    assertEquals(Surface.NAMETAG, russian.surface());
    assertEquals(Boolean.TRUE, russian.value());
  }

  @Test
  void withoutOnOffTheChoiceOnlyToggles() {
    assertNull(DisplayPreferences.parse(new String[] {"others", "suffix", "tab"}).value());
  }

  @Test
  void anythingElseIsAUsageError() {
    for (var args :
        new String[][] {
          {},
          {"own"},
          {"own", "clan"},
          {"mine", "clan", "chat", "off", "extra"},
          {"nobody", "clan", "chat"},
          {"own", "hat", "chat"},
          {"own", "clan", "radio"},
          {"own", "clan", "chat", "maybe"}
        }) {
      var error =
          assertThrows(IllegalArgumentException.class, () -> DisplayPreferences.parse(args));
      assertEquals("display.usage", error.getMessage());
    }
  }

  @Test
  void everyFlagHasItsOwnBitAndPlaceholderKey() {
    var bits = new HashSet<Integer>();
    var keys = new HashSet<String>();
    for (var s : Surface.values())
      for (var e : Element.values())
        for (var c : Scope.values()) {
          assertTrue(bits.add(DisplayPreferences.bit(s, e, c)), "bit collision");
          assertTrue(keys.add(DisplayPreferences.key(s, e, c)), "key collision");
        }
    assertEquals(12, bits.size());
    assertTrue(keys.contains("display_chat_clan_own"));
    assertTrue(keys.contains("display_nametag_suffix_others"));
  }
}
