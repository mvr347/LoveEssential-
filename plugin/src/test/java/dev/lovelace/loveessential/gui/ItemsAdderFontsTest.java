package dev.lovelace.loveessential.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ItemsAdderFontsTest {
  @Test
  void textWithoutAGlyphTokenIsLeftAlone() {
    assertEquals("<gold>Статистика", ItemsAdderFonts.resolve("<gold>Статистика"));
    assertEquals(null, ItemsAdderFonts.resolve(null));
  }

  @Test
  void withoutItemsAdderATokenStaysAsWritten() {
    MockBukkit.mock();
    try {
      String title = "<white>%img_offset_-8%%img_stats_menu%";
      assertEquals(title, ItemsAdderFonts.resolve(title));
    } finally {
      MockBukkit.unmock();
    }
  }
}
