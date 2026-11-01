package dev.lovelace.loveessential.condition;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerCommandTest {
  @Test
  void acceptsSlashAndPreservesArgumentsAndPlayerToken() {
    assertEquals("spawn", PlayerCommand.validated(" /spawn ", List.of("spawn")));
    assertEquals(
        "msg {player} hello  world",
        PlayerCommand.validated("/msg {player} hello  world", List.of("MSG")));
  }

  @Test
  void cannotBypassPermissionListWithNamespaceOrMultipleSlashes() {
    for (String value : List.of("/minecraft:op Builder", "op Builder", "//spawn"))
      assertThrows(
          IllegalArgumentException.class, () -> PlayerCommand.validated(value, List.of("spawn")));
  }

  @Test
  void rejectsEmptyAndMultipleCommandLines() {
    for (String value : List.of("", " / ", "spawn\nop Builder", "spawn\rop Builder", "spawn\top"))
      assertThrows(
          IllegalArgumentException.class, () -> PlayerCommand.validated(value, List.of("spawn")));
  }
}
