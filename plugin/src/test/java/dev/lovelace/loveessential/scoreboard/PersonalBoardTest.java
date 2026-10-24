package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class PersonalBoardTest {
  static List<String> plain(List<Component> lines) {
    return lines.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c).replace(' ', '_')).toList();
  }

  static Component text(String value) {
    return Component.text(value);
  }

  @Test
  void rowsComeTwoPerPairWithASeparatorAfterEachAndABottom() {
    var lines =
        PersonalBoard.assemble(
            text("T"), text(""), text("-"), text(""), List.of(text("1"), text("2"), text("3")));
    assertEquals(List.of("-", "1", "2", "-", "3", "-", ""), plain(lines));
  }

  @Test
  void eightRowsFitTheFifteenLineLimit() {
    var rows = java.util.stream.IntStream.range(0, 8).mapToObj(i -> text("r" + i)).toList();
    var lines = PersonalBoard.assemble(text("T"), text("top"), text(""), text("bottom"), rows);
    assertTrue(lines.size() <= 15, "size " + lines.size());
    assertEquals(14, lines.size());
  }

  @Test
  void aNonBlankTopReplacesTheFirstSeparatorAndBothEndsAreCentered() {
    var wide = text("WWWWWWWWWWWWWWWWWWWW");
    var lines = PersonalBoard.assemble(text("T"), text("top"), text("-"), text("bottom"), List.of(wide));
    var plain = plain(lines);
    assertTrue(plain.getFirst().contains("top") && plain.getFirst().startsWith("_"), plain.getFirst());
    assertTrue(plain.getLast().contains("bottom") && plain.getLast().startsWith("_"), plain.getLast());
    assertEquals(plain.size(), 4);
  }

  @Test
  void aBlankBottomStaysBlank() {
    var lines = PersonalBoard.assemble(text("T"), text(""), text(""), text(""), List.of(text("x")));
    assertEquals("", plain(lines).getLast());
  }
}
