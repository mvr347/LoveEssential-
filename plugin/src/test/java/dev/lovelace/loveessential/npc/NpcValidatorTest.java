package dev.lovelace.loveessential.npc;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NpcValidatorTest {
  private final UUID world = UUID.randomUUID();
  private final NpcValidator validator =
      new NpcValidator(
          k -> k.startsWith("known."),
          t -> Set.of("all", "any", "not", "permission").contains(t),
          t -> t.equals("message"));

  NpcDefinition npc(Route route, Dialog dialog, Map<String, String> options) {
    return new NpcDefinition(
        "guard",
        0,
        UUID.randomUUID(),
        "known.guard",
        "MANNEQUIN",
        new Position(world, 0, 64, 0, 0, 0),
        options,
        Condition.always(),
        Condition.always(),
        route,
        dialog,
        List.of(),
        true);
  }

  @Test
  void validDecorativeNpcPasses() {
    assertTrue(validator.validate(npc(Route.empty(), Dialog.empty(), Map.of()), 128).isEmpty());
  }

  @Test
  void checksMissingDialogueTargetsAndOrphanNodes() {
    var answer = new Dialog.Answer("yes", "known.answer", "absent", Condition.always(), List.of());
    var d =
        new Dialog(
            "start",
            Map.of(
                "start",
                new Dialog.Node("known.text", List.of(answer)),
                "orphan",
                new Dialog.Node("known.text", List.of())));
    var errors = validator.validate(npc(Route.empty(), d, Map.of()), 128);
    assertTrue(errors.contains("validation.dialog"));
    assertTrue(errors.contains("validation.unreachable"));
  }

  @Test
  void refusesRoutesAcrossWorlds() {
    var point =
        new Route.Point(
            new Position(UUID.randomUUID(), 0, 64, 0, 0, 0),
            0,
            1,
            .5,
            Condition.always(),
            List.of());
    assertTrue(
        validator
            .validate(
                npc(new Route(Route.Mode.LOOP, List.of(point)), Dialog.empty(), Map.of()), 128)
            .contains("validation.world-transition"));
  }

  @Test
  void refusesUnknownSettingsAndUnsafeNumbers() {
    assertFalse(NpcValidator.validOption("speed", "NaN"));
    assertFalse(NpcValidator.validOption("scale", "999999"));
    assertFalse(NpcValidator.validOption("gravity", "yes"));
    assertFalse(NpcValidator.validOption("movement", "fly-through-walls"));
    assertTrue(
        validator
            .validate(npc(Route.empty(), Dialog.empty(), Map.of("invented", "1")), 128)
            .contains("validation.option"));
  }

  @Test
  void immutableDefinitionsDoNotExposeMutableEditorState() {
    var options = new HashMap<String, String>();
    options.put("look-at", "true");
    var n = npc(Route.empty(), Dialog.empty(), options);
    options.clear();
    assertEquals("true", n.settings().get("look-at"));
    assertThrows(UnsupportedOperationException.class, () -> n.settings().clear());
  }

  @Test
  void positionAndPointRejectNonFiniteValues() {
    assertThrows(
        IllegalArgumentException.class, () -> new Position(world, Double.NaN, 64, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Route.Point(
                new Position(world, 0, 64, 0, 0, 0),
                0,
                Double.NaN,
                .5,
                Condition.always(),
                List.of()));
  }
}
