package dev.lovelace.loveessential.npc;

/** Missing reputation data is neutral, never a reason to punish a player. */
public enum NpcDisposition {
  FEARFUL, SKEPTICAL, FRIENDLY, NEUTRAL;

  public static NpcDisposition of(int politeness, int playstyle) {
    if (playstyle == 0) return FEARFUL;
    if (politeness >= 0 && politeness <= 1) return SKEPTICAL;
    if (politeness >= 5 || playstyle >= 5) return FRIENDLY;
    return NEUTRAL;
  }

  public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
}
