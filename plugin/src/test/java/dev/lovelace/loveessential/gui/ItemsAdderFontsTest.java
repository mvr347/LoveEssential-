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

  @Test
  void withItemsAdderTheTokensBecomeGlyphsUnderAWhiteTag() {
    MockBukkit.mock();
    try {
      MockBukkit.createMockPlugin("ItemsAdder");
      assertEquals(
          "<white>[offset][menu]</white>",
          ItemsAdderFonts.resolve("%img_offset%%img_menu%"));
    } finally {
      MockBukkit.unmock();
    }
  }
}
