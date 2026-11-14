package dev.lovelace.loveessential.npc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.lovelace.loveessential.npc.NpcRoutine.State.*;

class NpcRoutineTest {
  @Test void dayShiftHasHalfOpenBoundariesAndOffDutyChoice() {
    assertEquals(WALK, NpcRoutine.state(999, 1000, 12000, true));
    assertEquals(WORK, NpcRoutine.state(1000, 1000, 12000, true));
    assertEquals(WORK, NpcRoutine.state(11999, 1000, 12000, true));
    assertEquals(WALK, NpcRoutine.state(12000, 1000, 12000, true));
    assertEquals(AWAY, NpcRoutine.state(12000, 1000, 12000, false));
  }
  @Test void nightShiftWrapsAtMidnightAndEqualTimesMeanAllDay() {
    assertEquals(WORK, NpcRoutine.state(23000, 18000, 6000, false));
    assertEquals(WORK, NpcRoutine.state(24000, 18000, 6000, false));
    assertEquals(AWAY, NpcRoutine.state(6000, 18000, 6000, false));
    assertEquals(WORK, NpcRoutine.state(17000, 0, 0, false));
  }
  @Test void missingBehaviorIsNeutralAndAggressionTakesPrecedence() {
    assertEquals(NpcDisposition.NEUTRAL, NpcDisposition.of(-1, -1));
    assertEquals(NpcDisposition.FEARFUL, NpcDisposition.of(6, 0));
    assertEquals(NpcDisposition.SKEPTICAL, NpcDisposition.of(1, 6));
    assertEquals(NpcDisposition.FRIENDLY, NpcDisposition.of(5, 3));
    assertEquals(NpcDisposition.NEUTRAL, NpcDisposition.of(3, 3));
  }
}
