package dev.lovelace.loveessential.npc;

import java.util.*;

/** Bounded A* over an immutable snapshot. No Bukkit references may cross this boundary. */
public final class PathSearch {
  public record Cell(int x, int y, int z) {
    public double distance(Cell b) {
      double dx = x - b.x, dy = y - b.y, dz = z - b.z;
      return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
  }

  public record Snapshot(Set<Cell> walkable) {
    public Snapshot {
      walkable = Set.copyOf(walkable);
    }
  }

  private record Node(Cell cell, double cost, double estimate) {}

  public static List<Cell> find(Snapshot snapshot, Cell start, Cell goal, int limit) {
    if (!snapshot.walkable().contains(start) || !snapshot.walkable().contains(goal))
      return List.of();
    var open = new PriorityQueue<Node>(Comparator.comparingDouble(Node::estimate));
    var cost = new HashMap<Cell, Double>();
    var previous = new HashMap<Cell, Cell>();
    var visited = new HashSet<Cell>();
    cost.put(start, 0d);
    open.add(new Node(start, 0, start.distance(goal)));
    int count = 0;
    while (!open.isEmpty() && count++ < limit && !Thread.currentThread().isInterrupted()) {
      var current = open.remove();
      if (!visited.add(current.cell())) continue;
      if (current.cell().equals(goal)) {
        var result = new ArrayList<Cell>();
        Cell c = goal;
        result.add(c);
        while (!c.equals(start)) {
          c = previous.get(c);
          result.add(c);
        }
        Collections.reverse(result);
        return List.copyOf(result);
      }
      for (int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        for (int dy : new int[] {0, 1, -1}) {
          var next =
              new Cell(
                  current.cell().x() + dir[0],
                  current.cell().y() + dy,
                  current.cell().z() + dir[1]);
          // Ascending needs space above the original feet, descending above the destination.
          if (!snapshot.walkable().contains(next) || visited.contains(next)) continue;
          double g = current.cost() + 1 + Math.abs(dy) * .4;
          if (g < cost.getOrDefault(next, Double.POSITIVE_INFINITY)) {
            cost.put(next, g);
            previous.put(next, current.cell());
            open.add(new Node(next, g, g + next.distance(goal)));
          }
          break;
        }
      }
    }
    return List.of();
  }
}
