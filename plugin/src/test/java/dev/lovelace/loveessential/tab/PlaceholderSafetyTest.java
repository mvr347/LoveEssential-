package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class PlaceholderSafetyTest {
  private static boolean hasEvents(Component component) {
    return component.clickEvent() != null
        || component.hoverEvent() != null
        || component.insertion() != null
        || component.children().stream().anyMatch(PlaceholderSafetyTest::hasEvents);
  }

  @Test
  void placeholderValuesKeepStylingButCannotCarryClicksHoversOrInsertions() {
    var styled = Placeholders.styleOnly("<red>Hero</red> <gradient:#ff0000:#0000ff>Name</gradient>");
    assertEquals("Hero Name", PlainTextComponentSerializer.plainText().serialize(styled));
    assertEquals(NamedTextColor.RED, styled.children().getFirst().color());

    for (String hostile :
        new String[] {
          "<click:run_command:/op me>free ops</click>",
          "<hover:show_text:'x'>hover</hover>",
          "<insert:/op me>shift-click</insert>",
          "<font:uniform>font</font>",
          "<key:key.inventory>key</key>"
        }) {
      var parsed = Placeholders.styleOnly(hostile);
      assertFalse(hasEvents(parsed), hostile);
      assertNull(parsed.clickEvent(), hostile);
      assertFalse(
          parsed.children().stream().anyMatch(c -> c.clickEvent() instanceof ClickEvent), hostile);
    }
  }

  @Test
  void tagsOutsideTheStylingSetStayLiteralTextInsteadOfBeingInterpreted() {
    for (String literal :
        new String[] {"<font:uniform>font</font>", "<key:key.inventory>key</key>", "<lang:a.b>x"}) {
      var parsed = Placeholders.styleOnly(literal);
      assertEquals(literal, PlainTextComponentSerializer.plainText().serialize(parsed), literal);
      assertNull(parsed.font(), literal);
    }
  }
}
