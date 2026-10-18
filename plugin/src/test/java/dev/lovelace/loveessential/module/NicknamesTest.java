package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class NicknamesTest {
  @Test
  void gradientInterpolatesAllStopsAndKeepsUnicodeCodepoints() {
    Component gradient = Nicknames.render("AБ界", List.of("#ff0000", "#00ff00", "#0000ff"));
    assertEquals("AБ界", PlainTextComponentSerializer.plainText().serialize(gradient));
    var children = gradient.children();
    assertEquals(3, children.size());
    assertEquals(TextColor.color(255, 0, 0), children.get(0).color());
    assertEquals(TextColor.color(0, 255, 0), children.get(1).color());
    assertEquals(TextColor.color(0, 0, 255), children.get(2).color());
    assertEquals(2, Nicknames.render("𐐀Z", List.of("red", "blue")).children().size());
  }

  @Test
  void colorsAreStrictAndNicknamesCannotBecomeMarkup() {
    assertEquals(TextColor.color(0xff6699), Nicknames.color("#FF6699"));
    assertEquals(TextColor.color(0xffaa00), Nicknames.color("gold"));
    for (String bad :
        List.of("#fff", "#12345678", "rainbow", "<red>", "red><click:run_command:/op>"))
      assertThrows(IllegalArgumentException.class, () -> Nicknames.color(bad));
    assertEquals(
        "<click:run_command:/op>",
        PlainTextComponentSerializer.plainText()
            .serialize(Nicknames.render("<click:run_command:/op>", List.of("red"))));
  }

  @Test
  void legacySerializationPreservesExactGradientColors() {
    Component gradient = Nicknames.render("Test", List.of("#123456", "#abcdef"));
    String encoded = Nicknames.LEGACY.serialize(gradient);
    assertTrue(encoded.contains("§x§1§2§3§4§5§6"));
    assertTrue(encoded.contains("§x§a§b§c§d§e§f"));
    assertEquals(
        "Test",
        PlainTextComponentSerializer.plainText().serialize(Nicknames.LEGACY.deserialize(encoded)));
  }
}
