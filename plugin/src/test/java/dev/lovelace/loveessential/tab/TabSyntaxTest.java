package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class TabSyntaxTest {
  private static String plain(String miniMessage) {
    return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
  }

  @Test
  void legacyColorsBecomeMiniMessageAndRenderWithoutAmpersands() {
    String converted = TabSyntax.legacy("&c▐ Discord▐ &7текст &#ff8800hex");
    assertEquals("▐ Discord▐ текст hex", plain(converted));
    assertFalse(converted.contains("&"));
    assertTrue(converted.contains("<red>") && converted.contains("<gray>") && converted.contains("<#ff8800>"));
  }

  @Test
  void colorCodeResetsDecorationsLikeLegacyFormatting() {
    assertEquals("<bold>a<reset><red>b", TabSyntax.legacy("&la&cb"));
  }

  @Test
  void ampersandInsideTagsAndOrdinaryTextStaysAsWritten() {
    String url = "<click:open_url:https://a.b/?x=1&c=2>go</click>";
    assertEquals(url, TabSyntax.legacy(url));
    assertEquals("R&D and A&B", TabSyntax.legacy("R&D and A&B"));
    assertEquals("price & more &", TabSyntax.legacy("price & more &"));
  }

  @Test
  void placeholderTokensIncludingColonsAndSpacesAreRecognised() {
    assertEquals("<papi:'player_x'>", TabSyntax.tokens("%player_x%"));
    assertEquals("<papi:'server_time_hh:mm:ss a'>", TabSyntax.tokens("%server_time_hh:mm:ss a%"));
    assertEquals("<papi:'server_time_dd.MM.yyyy HH:mm'>", TabSyntax.tokens("%server_time_dd.MM.yyyy HH:mm%"));
    assertEquals("<papi:'ping'>", TabSyntax.tokens("%ping%"));
    assertEquals(
        "<papi:'a_b'> и <papi:'c_d'>", TabSyntax.tokens("%a_b% и %c_d%"));
  }

  @Test
  void percentSignsInProseAreNotTokens() {
    assertEquals("100% hp and 50%", TabSyntax.tokens("100% hp and 50%"));
    assertEquals("скидка 20% и 30%", TabSyntax.tokens("скидка 20% и 30%"));
    assertEquals("5%", TabSyntax.tokens("5%"));
  }

  @Test
  void builtinNamesAreTheTabOnes() {
    for (String name : new String[] {"player", "PING", "health", "world", "online", "group", "time", "date", "displayname"})
      assertTrue(TabSyntax.builtin(name), name);
    assertFalse(TabSyntax.builtin("player_name"));
    assertFalse(TabSyntax.builtin(null));
  }
}
