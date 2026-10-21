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

  public static final class Snapshot {
    private final int x, y, z, width, height, depth;
    private final BitSet cells;

    public Snapshot(int x, int y, int z, int width, int height, int depth, BitSet cells) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.width = width;
      this.height = height;
      this.depth = depth;
      this.cells = (BitSet) cells.clone();
    }

    public Snapshot(Set<Cell> values) {
      x = values.stream().mapToInt(Cell::x).min().orElse(0);
      y = values.stream().mapToInt(Cell::y).min().orElse(0);
      z = values.stream().mapToInt(Cell::z).min().orElse(0);
      width = values.stream().mapToInt(Cell::x).max().orElse(x) - x + 1;
      height = values.stream().mapToInt(Cell::y).max().orElse(y) - y + 1;
      depth = values.stream().mapToInt(Cell::z).max().orElse(z) - z + 1;
      cells = new BitSet(Math.multiplyExact(Math.multiplyExact(width, height), depth));
      for (var c : values) cells.set(index(c));
    }

    private int index(Cell c) {
      return ((c.x() - x) * depth + c.z() - z) * height + c.y() - y;
    }

    public boolean contains(Cell c) {
      return c.x() >= x
          && c.x() < x + width
          && c.y() >= y
          && c.y() < y + height
          && c.z() >= z
          && c.z() < z + depth
          && cells.get(index(c));
    }

    public Set<Cell> walkable() {
      var values = new HashSet<Cell>();
      cells.stream()
          .forEach(
              i ->
                  values.add(
                      new Cell(
                          x + i / (depth * height), y + i % height, z + (i / height) % depth)));
      return Collections.unmodifiableSet(values);
    }
  }

  private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int[] HEIGHTS = {0, 1, -1};

  private record Node(Cell cell, double cost, double estimate) {}

  public static List<Cell> find(Snapshot snapshot, Cell start, Cell goal, int limit) {
    if (!snapshot.contains(start) || !snapshot.contains(goal)) return List.of();
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
      for (int[] dir : DIRECTIONS) {
        for (int dy : HEIGHTS) {
          var next =
              new Cell(
                  current.cell().x() + dir[0],
                  current.cell().y() + dy,
                  current.cell().z() + dir[1]);
          // Ascending needs space above the original feet, descending above the destination.
          if (!snapshot.contains(next) || visited.contains(next)) continue;
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
