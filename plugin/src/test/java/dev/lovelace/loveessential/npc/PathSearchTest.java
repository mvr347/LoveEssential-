package dev.lovelace.loveessential.npc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PathSearchTest {
  @Test
  void findsDetourWithoutCrossingBlockedCells() {
    var cells = new HashSet<PathSearch.Cell>();
    for (int x = 0; x <= 6; x++)
      for (int z = 0; z <= 4; z++) if (x != 3 || z == 4) cells.add(new PathSearch.Cell(x, 0, z));
    var path =
        PathSearch.find(
            new PathSearch.Snapshot(cells),
            new PathSearch.Cell(0, 0, 0),
            new PathSearch.Cell(6, 0, 0),
            200);
    assertFalse(path.isEmpty());
    assertTrue(path.contains(new PathSearch.Cell(3, 0, 4)));
    assertTrue(cells.containsAll(path));
  }

  @Test
  void refusesDisconnectedAndOutOfBudgetSearches() {
    var cells = Set.of(new PathSearch.Cell(0, 0, 0), new PathSearch.Cell(10, 0, 0));
    assertTrue(
        PathSearch.find(
                new PathSearch.Snapshot(cells),
                new PathSearch.Cell(0, 0, 0),
                new PathSearch.Cell(10, 0, 0),
                100)
            .isEmpty());
    var line = new HashSet<PathSearch.Cell>();
    for (int x = 0; x < 10; x++) line.add(new PathSearch.Cell(x, 0, 0));
    assertTrue(
        PathSearch.find(
                new PathSearch.Snapshot(line),
                new PathSearch.Cell(0, 0, 0),
                new PathSearch.Cell(9, 0, 0),
                2)
            .isEmpty());
  }

  @Test
  void supportsOneBlockStepsButNotTwoBlockCliffs() {
    var start = new PathSearch.Cell(0, 0, 0);
    var step = new PathSearch.Cell(1, 1, 0);
    var cliff = new PathSearch.Cell(2, 3, 0);
    var snapshot = new PathSearch.Snapshot(Set.of(start, step, cliff));
    assertEquals(List.of(start, step), PathSearch.find(snapshot, start, step, 30));
    assertTrue(PathSearch.find(snapshot, start, cliff, 30).isEmpty());
  }

  @Test
  void snapshotCannotBeModifiedByTheProducer() {
    var cells = new HashSet<PathSearch.Cell>();
    var start = new PathSearch.Cell(0, 0, 0);
    cells.add(start);
    var snapshot = new PathSearch.Snapshot(cells);
    cells.clear();
    assertEquals(List.of(start), PathSearch.find(snapshot, start, start, 1));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.walkable().clear());
  }
}
