package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Candidate columns for /rtp: rings, border margin, Nether ceiling scan. No server needed. */
class RandomTeleportGeometryTest {

  /** Distance from the centre to the centre of the block, the way a player would measure it. */
  static double circleDistance(int[] column, double cx, double cz) {
    return Math.hypot(column[0] + .5 - cx, column[1] + .5 - cz);
  }

  static double squareDistance(int[] column, double cx, double cz) {
    return Math.max(Math.abs(column[0] + .5 - cx), Math.abs(column[1] + .5 - cz));
  }

  @Test
  void circleCandidatesStayBetweenTheRadiiAndSpreadAcrossThem() {
    var random = new Random(7);
    int inner = 0, outer = 0;
    for (int i = 0; i < 4000; i++) {
      int[] column =
          RandomTeleportGeometry.sample(true, 100, -50, 200, 2000, random);
      assertNotNull(column);
      // One block of rounding is allowed on each side: the column is an integer position.
      double distance = circleDistance(column, 100, -50);
      assertTrue(distance >= 200 - 1.5 && distance <= 2000 + 1.5, "distance " + distance);
      if (distance < 1100) inner++;
      else outer++;
    }
    // Uniform by area: the outer half of the ring holds about three quarters of the points.
    assertTrue(outer > inner * 2, "points bunch up near the centre: " + inner + " vs " + outer);
  }

  @Test
  void circleWithZeroMinimumReachesTheCentre() {
    var random = new Random(3);
    int nearCentre = 0;
    for (int i = 0; i < 2000; i++) {
      int[] column = RandomTeleportGeometry.sample(true, 0, 0, 0, 10, random);
      if (circleDistance(column, 0, 0) < 3) nearCentre++;
    }
    assertTrue(nearCentre > 0, "a zero minimum must allow spots next to the centre");
  }

  @Test
  void squareCandidatesUseTheLargerCoordinateAsDistanceAndSkipTheInnerSquare() {
    var random = new Random(11);
    for (int i = 0; i < 4000; i++) {
      int[] column = RandomTeleportGeometry.sample(false, 0, 0, 300, 1000, random);
      assertNotNull(column);
      double distance = squareDistance(column, 0, 0);
      assertTrue(distance >= 300 - 1 && distance <= 1000 + 1, "square distance " + distance);
    }
  }

  @Test
  void aSquareRingThatCannotBeHitGivesUpInsteadOfLooping() {
    // Minimum equal to maximum leaves no area; sampling must end with null, not spin forever.
    assertNull(RandomTeleportGeometry.sample(false, 0, 0, 500, 500, new Random(1)));
  }

  @Test
  void theBorderKeepsTheConfiguredMarginFromItsEdge() {
    // Border 200 wide centred on 0: edges at -100 and +100; margin 8 leaves -92..92.
    assertTrue(RandomTeleportGeometry.insideBorder(91, 0, 0, 0, 200, 8));
    assertFalse(RandomTeleportGeometry.insideBorder(92, 0, 0, 0, 200, 8));
    assertTrue(RandomTeleportGeometry.insideBorder(-92, -92, 0, 0, 200, 8));
    assertFalse(RandomTeleportGeometry.insideBorder(0, -93, 0, 0, 200, 8));
  }

  @Test
  void theBorderCentreMovesTheAllowedArea() {
    assertTrue(RandomTeleportGeometry.insideBorder(1000, 1000, 1000, 1000, 100, 0));
    assertFalse(RandomTeleportGeometry.insideBorder(0, 0, 1000, 1000, 100, 0));
  }

  @Test
  void aBorderSmallerThanTheMarginAllowsNothing() {
    assertFalse(RandomTeleportGeometry.insideBorder(0, 0, 0, 0, 10, 16));
  }

  @Test
  void heightRangeIsInclusiveAtBothEnds() {
    assertTrue(RandomTeleportGeometry.inRange(60, 60, 220));
    assertTrue(RandomTeleportGeometry.inRange(220, 60, 220));
    assertFalse(RandomTeleportGeometry.inRange(59, 60, 220));
    assertFalse(RandomTeleportGeometry.inRange(221, 60, 220));
  }

  @Test
  void theNetherScanStartsAtTheCeilingAndTakesTheFirstFloor() {
    // Floors at 40 and 70; the scan from 120 must take 70, the one just below the ceiling.
    Integer y =
        RandomTeleportGeometry.highestStandable(120, 32, floor -> floor == 70 || floor == 40);
    assertEquals(70, y);
  }

  @Test
  void theNetherScanReturnsNullWhenNoFloorIsInRange() {
    assertNull(RandomTeleportGeometry.highestStandable(120, 32, y -> y == 31));
  }
}
