package dev.lovelace.loveessential.wizard;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NpcRebaseTest {
  private final UUID world = UUID.randomUUID();

  NpcDefinition npc(long revision, Map<String, String> settings) {
    return new NpcDefinition(
        "guard", revision, UUID.randomUUID(), "known.guard", "MANNEQUIN",
        new Position(world, 0, 64, 0, 0, 0), settings, Condition.always(), Condition.always(),
        Route.empty(), Dialog.empty(), List.of(), true);
  }

  @Test
  void aDraftBehindOnlyBecauseOfOtherPluginsTagsTakesTheirTagsAndTheStoredRevision() {
    var draft = npc(3, Map.of("skin", "Notch", "tag.shop", "old"));
    var live = npc(5, Map.of("skin", "Steve", "tag.shop", "new", "tag.clan", "x"));
    var rebased = NpcWizard.rebase(draft, live);
    assertEquals(5, rebased.revision());
    assertEquals("Notch", rebased.settings().get("skin"), "the editor's own edit stays");
    assertEquals("new", rebased.settings().get("tag.shop"));
    assertEquals("x", rebased.settings().get("tag.clan"));
  }

  @Test
  void anUpToDateDraftOrAMissingStoredNpcIsLeftAlone() {
    var draft = npc(3, Map.of("tag.shop", "mine"));
    assertSame(draft, NpcWizard.rebase(draft, null));
    assertSame(draft, NpcWizard.rebase(draft, npc(3, Map.of("tag.shop", "other"))));
  }
}
