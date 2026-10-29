package dev.lovelace.loveessential.calamity;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.calamity.CalamityRules.Severity;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CalamityRulesTest {
  @Test
  void envelopeBuildsUpHoldsAndFades() {
    assertEquals(0, CalamityRules.envelope(0));
    assertEquals(0.5, CalamityRules.envelope(0.1), 1e-9);
    assertEquals(1, CalamityRules.envelope(0.2), 1e-9);
    assertEquals(1, CalamityRules.envelope(0.5), 1e-9);
    assertEquals(1, CalamityRules.envelope(0.8), 1e-9);
    assertEquals(0.5, CalamityRules.envelope(0.9), 1e-9);
    assertEquals(0, CalamityRules.envelope(1));
    assertEquals(0, CalamityRules.envelope(-3));
    assertEquals(0, CalamityRules.envelope(7));
  }

  @Test
  void onlineScaleIsSixtyPercentAloneAndFullAtThreshold() {
    assertEquals(0, CalamityRules.onlineScale(0, 8));
    assertEquals(0.6, CalamityRules.onlineScale(1, 8), 1e-9);
    assertEquals(1, CalamityRules.onlineScale(8, 8), 1e-9);
    assertEquals(1, CalamityRules.onlineScale(30, 8), 1e-9);
    double mid = CalamityRules.onlineScale(4, 8);
    assertTrue(mid > 0.6 && mid < 1);
    assertEquals(1, CalamityRules.onlineScale(1, 1), 1e-9);
  }

  @Test
  void penaltyScalesWithStrengthAndIsReducedByProtection() {
    assertEquals(0.35, CalamityRules.penalty(35, 1, 0, 75), 1e-9);
    assertEquals(0.175, CalamityRules.penalty(35, 0.5, 0, 75), 1e-9);
    assertEquals(0.175, CalamityRules.penalty(35, 1, 0.5, 75), 1e-9);
    assertEquals(0, CalamityRules.penalty(35, 0, 0, 75));
    assertEquals(0, CalamityRules.penalty(0, 1, 0, 75));
  }

  @Test
  void penaltyNeverPassesTheCap() {
    // 80% on a heavy calamity would be 104%: the cap keeps it at 75%.
    assertEquals(0.75, CalamityRules.penalty(80, Severity.HEAVY.multiplier, 0, 75), 1e-9);
    assertEquals(0.75, CalamityRules.penalty(500, 1, 0, 75), 1e-9);
    assertEquals(0, CalamityRules.penalty(50, 1, 0, 0));
  }

  @Test
  void strengthMultipliesEnvelopeSeverityAndOnlineScale() {
    assertEquals(1.3, CalamityRules.strength(0.5, Severity.HEAVY, 1), 1e-9);
    assertEquals(0.6 * 0.6, CalamityRules.strength(0.5, Severity.MILD, 0.6), 1e-9);
    assertEquals(0, CalamityRules.strength(0, Severity.HEAVY, 1));
  }

  @Test
  void harvestAlwaysKeepsTheMinimumSoAFarmCanReplant() {
    var random = new Random(7);
    for (int i = 0; i < 2000; i++) {
      int left = CalamityRules.harvest(3, 0.75, 1, random);
      assertTrue(left >= 1 && left <= 3, "left=" + left);
    }
    assertEquals(0, CalamityRules.harvest(0, 0.75, 1, random));
    assertEquals(4, CalamityRules.harvest(4, 0, 1, random));
  }

  @Test
  void harvestLosesRoughlyTheConfiguredShare() {
    var random = new Random(11);
    long kept = 0, total = 0;
    for (int i = 0; i < 20000; i++) {
      kept += CalamityRules.harvest(8, 0.25, 1, random);
      total += 8;
    }
    assertEquals(0.75, kept / (double) total, 0.02);
  }

  @Test
  void rollMatchesItsProbability() {
    var random = new Random(3);
    assertFalse(CalamityRules.roll(0, random));
    assertTrue(CalamityRules.roll(1, random));
    int hits = 0;
    for (int i = 0; i < 20000; i++) if (CalamityRules.roll(0.4, random)) hits++;
    assertEquals(0.4, hits / 20000.0, 0.02);
  }

  @Test
  void hungerMultiplierAddsHalfAPointOnAverage() {
    var random = new Random(5);
    assertEquals(0, CalamityRules.extraFoodLoss(0, 1.5, random));
    assertEquals(0, CalamityRules.extraFoodLoss(2, 1.0, random));
    for (int i = 0; i < 100; i++) assertEquals(1, CalamityRules.extraFoodLoss(2, 1.5, random));
    long sum = 0;
    for (int i = 0; i < 20000; i++) sum += CalamityRules.extraFoodLoss(1, 1.5, random);
    assertEquals(0.5, sum / 20000.0, 0.02);
  }

  @Test
  void threatDecaysThenGrowsWithinBounds() {
    assertEquals(45, CalamityRules.threat(50, 0, 10), 1e-9);
    assertEquals(55, CalamityRules.threat(50, 10, 10), 1e-9);
    assertEquals(100, CalamityRules.threat(95, 30, 0), 1e-9);
    assertEquals(0, CalamityRules.threat(0, -5, 10));
  }

  @Test
  void dailyChanceRisesWithThreatAndIsCertainAtTheTop() {
    assertEquals(0.08, CalamityRules.dailyStartChance(0, 0.08, 0.5), 1e-9);
    assertEquals(0.33, CalamityRules.dailyStartChance(50, 0.08, 0.5), 1e-9);
    assertEquals(1, CalamityRules.dailyStartChance(100, 0.08, 0.5));
    assertEquals(1, CalamityRules.dailyStartChance(40, 0.08, 5));
  }

  @Test
  void severityDependsOnThreatAndQuietSpell() {
    assertEquals(Severity.MILD, CalamityRules.severity(10, 1, 12));
    assertEquals(Severity.NORMAL, CalamityRules.severity(50, 1, 12));
    assertEquals(Severity.HEAVY, CalamityRules.severity(80, 1, 12));
    assertEquals(Severity.HEAVY, CalamityRules.severity(10, 12, 12));
  }

  @Test
  void newcomersGetHalfAndProgressIsClamped() {
    assertEquals(0.5, CalamityRules.newcomerFactor(60, 300));
    assertEquals(1, CalamityRules.newcomerFactor(300, 300));
    assertEquals(0.25, CalamityRules.progress(25, 100), 1e-9);
    assertEquals(1, CalamityRules.progress(200, 100));
    assertEquals(1, CalamityRules.progress(0, 0));
  }
}
