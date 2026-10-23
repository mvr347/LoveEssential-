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
}
