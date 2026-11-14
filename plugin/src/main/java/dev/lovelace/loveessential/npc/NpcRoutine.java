package dev.lovelace.loveessential.npc;

/** Minecraft day ticks, including shifts crossing midnight. No wall-clock timezone dependency. */
public final class NpcRoutine {
  public enum State { WORK, WALK, AWAY }
  private NpcRoutine() {}

  public static State state(long time, int from, int to, boolean walk) {
    int t = (int) Math.floorMod(time, 24000L);
    boolean working = from == to || (from < to ? t >= from && t < to : t >= from || t < to);
    return working ? State.WORK : walk ? State.WALK : State.AWAY;
  }
}
