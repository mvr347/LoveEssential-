package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScoreboardChoiceTest {
  final List<ScoreboardChoice.Named> boards =
      List.of(
          new ScoreboardChoice.Named("pvp", "in-combat"),
          new ScoreboardChoice.Named("kingdom", ""),
          new ScoreboardChoice.Named("late", "always"));

  @Test
  void aRuntimeLeaseBeatsEveryNamedBoard() {
    var choice = ScoreboardChoice.choose(true, boards, name -> true);
    assertEquals(ScoreboardChoice.Kind.LEASE, choice.kind());
  }

  @Test
  void theFirstBoardWhoseConditionHoldsWinsInConfigurationOrder() {
    assertEquals("pvp", ScoreboardChoice.choose(false, boards, Set.of("in-combat")::contains).id());
    // Not in combat: pvp is skipped and the unconditional board is the first that holds.
    assertEquals("kingdom", ScoreboardChoice.choose(false, boards, name -> false).id());
  }

  @Test
  void noBoardsMeansNoSidebar() {
    var choice = ScoreboardChoice.choose(false, List.of(new ScoreboardChoice.Named("x", "never")), name -> false);
    assertEquals(ScoreboardChoice.Kind.NONE, choice.kind());
  }

  @Test
  void aPersonalBoardComesAfterConditionalNamedBoardsAndBeforeUnconditionalOnes() {
    // Not in combat: pvp is skipped, kingdom (no condition) must not hide the personal board.
    var calm = ScoreboardChoice.choose(false, true, boards, name -> false);
    assertEquals(ScoreboardChoice.Kind.PERSONAL, calm.kind());
    // In combat the conditional board wins over the personal one.
    var fight = ScoreboardChoice.choose(false, true, boards, Set.of("in-combat")::contains);
    assertEquals(ScoreboardChoice.Kind.NAMED, fight.kind());
    assertEquals("pvp", fight.id());
    // The conditional board "late" also wins once its condition holds.
    assertEquals("late", ScoreboardChoice.choose(false, true, boards, Set.of("always")::contains).id());
  }

  @Test
  void aLeaseBeatsThePersonalBoardAndNoPersonalBoardKeepsTheOldOrder() {
    assertEquals(ScoreboardChoice.Kind.LEASE, ScoreboardChoice.choose(true, true, boards, n -> true).kind());
    assertEquals("kingdom", ScoreboardChoice.choose(false, false, boards, n -> false).id());
  }
}
