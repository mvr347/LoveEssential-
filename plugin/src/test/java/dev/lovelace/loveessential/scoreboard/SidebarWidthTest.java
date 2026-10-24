package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

class SidebarWidthTest {
  @Test
  void centersWithoutLosingTextOrBoldFormatting() {
    var text = Component.text("Мир").decorate(TextDecoration.BOLD);
    var centered = SidebarWidth.center(text, 120);
    assertTrue(SidebarWidth.width(centered) >= 120);
    assertEquals(text, centered.children().getFirst());
    assertEquals(0, SidebarWidth.width(Component.empty()));
    assertTrue(SidebarWidth.width(text) > SidebarWidth.width(Component.text("Мир")));
  }
}
