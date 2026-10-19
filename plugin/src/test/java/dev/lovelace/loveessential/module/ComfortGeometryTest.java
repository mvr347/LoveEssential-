package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.api.HologramService.*;
import java.util.*;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

class ComfortGeometryTest {
  @Test
  void sitsOnActualSurfaceForBothSlabHalvesCarpetAndFullBlock() {
    for (double height : new double[] {1, .5, 1.0 / 16})
      assertEquals(
          64 + height,
          Comfort.surface(List.of(new BoundingBox(0, 0, 0, 1, height, 1)), 64, .5, .5),
          1e-8);
    assertEquals(65, Comfort.surface(List.of(new BoundingBox(0, .5, 0, 1, 1, 1)), 64, .5, .5));
  }

  @Test
  void stairLowerAndUpperTreadsUseDifferentSeatHeights() {
    var boxes = List.of(new BoundingBox(0, 0, 0, 1, .5, 1), new BoundingBox(0, .5, 0, 1, 1, .5));
    assertEquals(64.5, Comfort.surface(boxes, 64, .5, .75));
    assertEquals(65, Comfort.surface(boxes, 64, .5, .25));
  }

  @Test
  void noSurfaceCannotCreateUndergroundOrFloatingSeat() {
    assertThrows(IllegalArgumentException.class, () -> Comfort.surface(List.of(), 64, .5, .5));
  }

  @Test
  void definitionsRejectInvalidGeometryAndAmbiguousLineContent() {
    var pos = new Position(UUID.randomUUID(), 0, 64, 0, 0, 0);
    var pages = List.of(new Page(List.of(new Line("common.done", "")), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Hologram("valid", pos, pages, 0, true, Double.NaN, 48));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Hologram("valid", pos, pages, 0, true, .3, Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> new Line("key", "encoded"));
    assertThrows(IllegalArgumentException.class, () -> new Line("", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Page(Collections.nCopies(33, new Line("key", "")), null));
  }

  @Test
  void npcDraftHasIdWithoutAskingPlayer() {
    var draft =
        dev.lovelace.loveessential.wizard.NpcDraft.create(
            UUID.randomUUID(), new Position(UUID.randomUUID(), 0, 64, 0, 0, 0));
    assertTrue(draft.npc().id().startsWith("npc_"));
    assertFalse(draft.npc().nameKey().isBlank());
  }
}
