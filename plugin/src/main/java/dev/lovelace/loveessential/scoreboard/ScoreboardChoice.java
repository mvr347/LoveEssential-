package dev.lovelace.loveessential.scoreboard;

import java.util.List;
import java.util.function.Predicate;

/**
 * Which board a player sees. A runtime lease from {@code ScoreboardService} always wins. Without a
 * personal board the first named board in configuration order whose display condition holds is
 * shown. With one, named boards that carry a condition and hold (an event, a fight) go first, then
 * the player's own board, and only then the named boards without a condition. Pure.
 */
public final class ScoreboardChoice {
  private ScoreboardChoice() {}

  /** A named board with the name of its display condition; blank means always shown. */
  public record Named(String id, String displayCondition) {}

  public enum Kind {
    LEASE,
    NAMED,
    PERSONAL,
    NONE
  }

  public record Choice(Kind kind, String id) {
    static final Choice NONE_CHOICE = new Choice(Kind.NONE, "");
  }

  public static Choice choose(
      boolean leased, List<Named> boards, Predicate<String> conditionHolds) {
    return choose(leased, false, boards, conditionHolds);
  }

  public static Choice choose(
      boolean leased, boolean personal, List<Named> boards, Predicate<String> conditionHolds) {
    if (leased) return new Choice(Kind.LEASE, "");
    for (Named board : boards) {
      boolean conditional = !board.displayCondition().isBlank();
      if (personal && !conditional) continue;
      if (!conditional || conditionHolds.test(board.displayCondition()))
        return new Choice(Kind.NAMED, board.id());
    }
    if (personal) return new Choice(Kind.PERSONAL, "");
    return Choice.NONE_CHOICE;
  }
}
