package dev.lovelace.loveessential.calamity;

import java.util.random.RandomGenerator;

/**
 * The numbers behind the calamities, kept free of Bukkit so they can be tested. Every penalty is a
 * probability (a 40% growth penalty cancels 40% of growth steps) instead of an exact timer, which is
 * cheap and behaves the same on any farm design.
 */
public final class CalamityRules {
  private CalamityRules() {}

  /** One game day in ticks. */
  public static final long DAY_TICKS = 24000;

  /** How hard a calamity hits; multiplies every penalty. */
  public enum Severity {
    MILD(0.6),
    NORMAL(1.0),
    HEAVY(1.3);

    public final double multiplier;

    Severity(double multiplier) {
      this.multiplier = multiplier;
    }
  }

  /**
   * Share of full strength at {@code progress} (0 at the start, 1 at the end): it builds up over the
   * first fifth, stays at the peak, and fades over the last fifth, so a calamity never switches on or
   * off in a single step.
   */
  public static double envelope(double progress) {
    if (!(progress > 0) || progress >= 1) return 0;
    if (progress < 0.2) return progress / 0.2;
    if (progress <= 0.8) return 1;
    return (1 - progress) / 0.2;
  }

  /** Fewer players online, weaker calamity: full strength from {@code fullAt} players, 60% alone. */
  public static double onlineScale(int online, int fullAt) {
    if (online <= 0) return 0;
    if (fullAt <= 1 || online >= fullAt) return 1;
    return 0.6 + 0.4 * (online - 1) / (double) (fullAt - 1);
  }

  /** Current strength of a calamity: envelope x severity x online scale. */
  public static double strength(double progress, Severity severity, double onlineScale) {
    return envelope(progress) * severity.multiplier * onlineScale;
  }

  /**
   * The effective penalty, as a fraction 0..cap, for a configured penalty of {@code basePercent} at
   * the given strength. {@code protection} (0..1) is what a campfire, a composter or a cure takes
   * off; {@code capPercent} is the ceiling no combination may pass.
   */
  public static double penalty(
      double basePercent, double strength, double protection, double capPercent) {
    if (!(basePercent > 0) || !(strength > 0)) return 0;
    double p = Math.clamp(protection, 0, 1);
    double fraction = basePercent / 100.0 * strength * (1 - p);
    return Math.clamp(fraction, 0, Math.clamp(capPercent, 0, 100) / 100.0);
  }

  /** True with probability {@code chance}. */
  public static boolean roll(double chance, RandomGenerator random) {
    if (!(chance > 0)) return false;
    if (chance >= 1) return true;
    return random.nextDouble() < chance;
  }

  /**
   * Drops left after a crop penalty: each item is lost with probability {@code fraction}, but a
   * harvest that gave at least {@code minimum} never goes below it, so a farm can always replant.
   */
  public static int harvest(int drops, double fraction, int minimum, RandomGenerator random) {
    if (drops <= 0) return 0;
    if (!(fraction > 0)) return drops;
    int kept = 0;
    for (int i = 0; i < drops; i++) if (random.nextDouble() >= fraction) kept++;
    return Math.max(kept, Math.min(minimum, drops));
  }

  /**
   * Extra hunger for a loss of {@code lost} food points under a hunger multiplier: 1.5 turns a loss
   * of 2 into 3, and a loss of 1 into 2 half of the time.
   */
  public static int extraFoodLoss(int lost, double multiplier, RandomGenerator random) {
    if (lost <= 0 || !(multiplier > 1)) return 0;
    double extra = lost * (multiplier - 1);
    int whole = (int) Math.floor(extra);
    return whole + (random.nextDouble() < extra - whole ? 1 : 0);
  }

  /** Threat after one step: the old value decays, then {@code gain} is added; always within 0..100. */
  public static double threat(double current, double gain, double decayPercent) {
    double decayed = current * (1 - Math.clamp(decayPercent, 0, 100) / 100.0);
    return Math.clamp(decayed + Math.max(0, gain), 0, 100);
  }

  /**
   * Chance, per game day, that a calamity of one kind starts: the base chance plus a share of the
   * threat. At 100 threat the calamity is certain.
   */
  public static double dailyStartChance(double threat, double baseChance, double threatWeight) {
    if (threat >= 100) return 1;
    return Math.clamp(baseChance + threat / 100.0 * threatWeight, 0, 1);
  }

  /**
   * Severity for a calamity that is about to start: little threat means mild; high threat (the
   * players brought it on themselves) or a long quiet spell means heavy.
   */
  public static Severity severity(double threat, double quietDays, double heavyAfterDays) {
    if (threat >= 75 || quietDays >= heavyAfterDays) return Severity.HEAVY;
    if (threat < 25) return Severity.MILD;
    return Severity.NORMAL;
  }

  /** Newcomers get half the penalty so a first day is not ruined. */
  public static double newcomerFactor(long playedMinutes, long thresholdMinutes) {
    return playedMinutes < thresholdMinutes ? 0.5 : 1;
  }

  /** Progress 0..1 of a calamity that has run {@code elapsed} of {@code duration} ticks. */
  public static double progress(long elapsed, long duration) {
    if (duration <= 0) return 1;
    return Math.clamp(elapsed / (double) duration, 0, 1);
  }
}
