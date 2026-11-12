package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ClanFormatTest {
  private static final String FORMAT = "🏰 <gray>[</gray><aqua>%tag%</aqua><gray>]</gray> ";
  private final MiniMessage mm = MiniMessage.miniMessage();

  private String plain(String format, String tag) {
    return PlainTextComponentSerializer.plainText().serialize(ClanFormat.apply(mm, format, tag));
  }

  @Test
  void noClanGivesNothingNotABareEmoji() {
    assertEquals("", plain(FORMAT, ""));
    assertEquals("", plain(FORMAT, null));
    assertEquals("", plain(FORMAT, "  "));
  }

  @Test
  void clanGetsTheWholeDecoration() {
    assertEquals("🏰 [LOVE] ", plain(FORMAT, "LOVE"));
  }

  @Test
  void blankFormatIsJustTheTag() {
    assertEquals("LOVE", plain("", "LOVE"));
    assertEquals("LOVE", plain(null, "LOVE"));
  }

  @Test
  void tagIsNeverParsedAsMarkup() {
    assertEquals("[<red>x] ", plain("[%tag%] ", "<red>x"));
  }
}
