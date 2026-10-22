package dev.lovelace.loveessential.scoreboard;

import java.util.List;
import java.util.function.Predicate;

/**
 * Which board a player sees. A runtime lease from {@code ScoreboardService} always wins; otherwise
 * the first named board in configuration order whose display condition holds. Pure.
 */
public final class ScoreboardChoice {
  private ScoreboardChoice() {}

  /** A named board with the name of its display condition; blank means always shown. */
  public record Named(String id, String displayCondition) {}

  public enum Kind {
    LEASE,
    NAMED,
    NONE
  }

  public record Choice(Kind kind, String id) {
    static final Choice NONE_CHOICE = new Choice(Kind.NONE, "");
  }

  public static Choice choose(
      boolean leased, List<Named> boards, Predicate<String> conditionHolds) {
    if (leased) return new Choice(Kind.LEASE, "");
    for (Named board : boards)
      if (board.displayCondition().isBlank() || conditionHolds.test(board.displayCondition()))
        return new Choice(Kind.NAMED, board.id());
    return Choice.NONE_CHOICE;
  }
}
