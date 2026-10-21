import dev.lovelace.loveessential.npc.*;
import java.lang.management.ManagementFactory;
import java.util.*;

public class SnapshotBenchmark {
  static List<Object> retained = new ArrayList<>();

  static Object snapshot(boolean old) {
    if (old) {
      var cells = new HashSet<OldPathSearch.Cell>();
      for (int x = 0; x < 49; x++)
        for (int y = 0; y < 9; y++)
          for (int z = 0; z < 49; z++) cells.add(new OldPathSearch.Cell(x, y, z));
      return new OldPathSearch.Snapshot(cells);
    }
    var bits = new BitSet(49 * 49 * 9);
    bits.set(0, 49 * 49 * 9);
    return new PathSearch.Snapshot(0, 0, 0, 49, 9, 49, bits);
  }

  public static void main(String[] args) throws Exception {
    boolean old = args[0].equals("old");
    for (int i = 0; i < 16; i++) snapshot(old);
    System.gc();
    var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    long before = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    for (int i = 0; i < 64; i++) retained.add(snapshot(old));
    long allocation = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
    System.gc();
    long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() - before;
    System.out.println(
        "{\"mode\":\""
            + args[0]
            + "\",\"snapshots\":64,\"cells_per_snapshot\":21609,\"allocated_bytes\":"
            + allocation
            + ",\"retained_heap_delta_bytes\":"
            + used
            + "}");
  }
}
