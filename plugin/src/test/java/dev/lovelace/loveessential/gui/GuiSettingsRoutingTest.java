package dev.lovelace.loveessential.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GuiSettingsRoutingTest {
  @Test
  void theJoinQuitMenuReadsItsOwnModuleSettings() {
    assertEquals("joinquit", GuiSettings.module("joinquit.gui-title"));
    assertEquals("nickname", GuiSettings.module("nick.title"));
  }
}
