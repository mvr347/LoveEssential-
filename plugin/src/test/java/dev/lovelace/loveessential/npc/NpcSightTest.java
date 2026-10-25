package dev.lovelace.loveessential.npc;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class NpcSightTest {
  // A villager-sized body standing at 10, 70, 10.
  private static final BoundingBox BODY = new BoundingBox(9.7, 70, 9.7, 10.3, 71.95, 10.3);

  @Test
  void aRayThroughTheBodyHitsItAtADistance() {
    double d = NpcService.sightDistance(BODY, new Vector(10, 71.6, 14), new Vector(0, 0, -1), 10);
    assertEquals(3.4, d, 0.05); // 14 - (10.3 body edge + 0.3 margin)
  }

  @Test
  void aRayJustBesideTheBodyStillHitsThanksToTheMargin() {
    double d = NpcService.sightDistance(BODY, new Vector(10.5, 71, 14), new Vector(0, 0, -1), 10);
    assertTrue(Double.isFinite(d));
  }

  @Test
  void lookingAwayOrTooFarMisses() {
    assertTrue(Double.isInfinite(NpcService.sightDistance(BODY, new Vector(10, 71, 14), new Vector(1, 0, 0), 10)));
    assertTrue(Double.isInfinite(NpcService.sightDistance(BODY, new Vector(10, 71, 30), new Vector(0, 0, -1), 10)));
  }
}
