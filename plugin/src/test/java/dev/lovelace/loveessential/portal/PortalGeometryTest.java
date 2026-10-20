package dev.lovelace.loveessential.portal;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.api.Position;
import java.util.*;
import org.junit.jupiter.api.Test;

class PortalGeometryTest {
  PortalDefinition portal(boolean fill) {
    var world = UUID.randomUUID();
    return new PortalDefinition(
        "portal_test",
        "common.done",
        new Position(world, 4, 64, 8, 0, 0),
        new Position(world, 0, 69, 8, 0, 0),
        null,
        true,
        "",
        List.of(),
        3,
        0,
        true,
        false,
        "DUST",
        "B68CFF",
        fill,
        .25,
        List.of());
  }

  @Test
  void reversedCornersUseInclusiveBlocksAndExcludeAdjacentWorld() {
    var portal = portal(true);
    assertTrue(portal.contains(new Position(portal.first().world(), .1, 64, 8, 0, 0)));
    assertFalse(portal.contains(new Position(portal.first().world(), 5, 64, 8, 0, 0)));
    assertFalse(portal.contains(new Position(UUID.randomUUID(), 4, 64, 8, 0, 0)));
    assertEquals(30, portal.volume());
  }

  @Test
  void fillRespectsBudgetAndCreationProgressAndOutlineContainsOnlyEdges() {
    var portal = portal(true);
    for (int frame = 0; frame < 50; frame++) {
      var points = PortalGeometry.points(portal, 17, .5, frame);
      assertTrue(points.size() <= 17);
      assertTrue(points.stream().allMatch(p -> p.getY() <= 67 && p.getZ() == 8.5));
    }
    var outline = PortalGeometry.points(portal(false), 2000, 1, 0);
    assertFalse(outline.isEmpty());
    assertTrue(
        outline.stream()
            .allMatch(p -> p.getX() == 0 || p.getX() == 5 || p.getY() == 64 || p.getY() == 70));
    assertTrue(PortalGeometry.points(portal, 0, 1, 0).isEmpty());
  }
}
