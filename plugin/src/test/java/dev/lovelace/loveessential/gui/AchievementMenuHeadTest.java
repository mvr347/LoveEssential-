package dev.lovelace.loveessential.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AchievementMenuHeadTest {
  @Test
  void slotZeroTextureIsAValidMojangHash() {
    assertTrue(ControlMenus.ACH_MENU.matches("[0-9a-f]{50,64}"));
  }
}
