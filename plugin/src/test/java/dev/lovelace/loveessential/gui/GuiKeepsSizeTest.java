package dev.lovelace.loveessential.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuiKeepsSizeTest {
  @Test
  void filteredMenusKeepTheirWindow() {
    assertTrue(GuiSettings.keepsSize("achievements.gui-title"));
    assertTrue(GuiSettings.keepsSize("joinquit.gui-title"));
  }

  @Test
  void otherMenusStillCompact() {
    assertFalse(GuiSettings.keepsSize("nick.title"));
    assertFalse(GuiSettings.keepsSize("profile-menu.settings"));
  }
}
