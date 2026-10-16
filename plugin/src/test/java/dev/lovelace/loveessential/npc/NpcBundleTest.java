package dev.lovelace.loveessential.npc;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NpcBundleTest {
  @Test
  void clonedNpcOwnsItsTextKeysAndStartsANewRevision() {
    var world = UUID.randomUUID();
    var position = new Position(world, 1, 64, 1, 0, 0);
    var message = new Action("message", Map.of("key", "source.message"));
    var route =
        new Route(
            Route.Mode.LOOP,
            List.of(new Route.Point(position, 20, 1, .5, Condition.always(), List.of(message))));
    var dialog =
        new Dialog(
            "hello",
            Map.of(
                "hello",
                new Dialog.Node(
                    "source.dialog",
                    List.of(
                        new Dialog.Answer(
                            "bye", "source.answer", "", Condition.always(), List.of(message))))));
    var source =
        new NpcDefinition(
            "source",
            9,
            UUID.randomUUID(),
            "source.name",
            "MANNEQUIN",
            position,
            Map.of(),
            Condition.always(),
            Condition.always(),
            route,
            dialog,
            List.of(message),
            true);
    var bundle =
        new NpcBundle(
            1,
            source,
            Map.of(
                "source.name",
                "Страж",
                "source.dialog",
                "Привет",
                "source.answer",
                "Пока",
                "source.message",
                "Готово"));
    var owner = UUID.randomUUID();
    var clone = bundle.draft("copy", owner);
    assertEquals(0, clone.npc().revision());
    assertEquals(owner, clone.npc().owner());
    assertEquals("copy", clone.npc().id());
    assertNotEquals(source.nameKey(), clone.npc().nameKey());
    assertEquals("Страж", clone.texts().get(clone.npc().nameKey()));
    String actionKey =
        clone.npc().route().points().getFirst().actions().getFirst().args().get("key");
    assertNotEquals("source.message", actionKey);
    assertEquals("Готово", clone.texts().get(actionKey));
    assertEquals("source.message", source.actions().getFirst().args().get("key"));
  }

  @Test
  void rejectsUnsupportedExportFormat() {
    var bundle = new NpcBundle(99, null, Map.of());
    assertThrows(IllegalArgumentException.class, () -> bundle.draft("new_npc", UUID.randomUUID()));
  }
}
