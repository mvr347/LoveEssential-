package dev.lovelace.loveessential.module;

/** The decisions of the downed state that need no server: what to show and when time stands still. */
final class ReviveRules {
  private ReviveRules() {}

  /** What the downed player reads in the action bar, most important first. */
  enum Bar {
    /** Holding sneak: progress of giving up. */
    SUICIDE,
    /** Somebody is reviving: progress, with a note that giving up is still possible. */
    REVIVING,
    /** Being carried. */
    CARRIED,
    /** Giving up is possible: hold sneak. */
    GIVE_UP,
    /** The first seconds after the fall, when giving up is not allowed yet. */
    WAIT
  }

  static Bar bar(boolean reviving, boolean carried, double suicide, boolean canGiveUp) {
    if (suicide > 0) return Bar.SUICIDE;
    if (reviving) return Bar.REVIVING;
    if (carried) return Bar.CARRIED;
    return canGiveUp ? Bar.GIVE_UP : Bar.WAIT;
  }

  /** The death timer stands while the player is carried or revived, each by its own switch. */
  static boolean paused(boolean carried, boolean reviving, boolean carryPauses, boolean revivePauses) {
    return carried && carryPauses || reviving && revivePauses;
  }

  /** One configured sound: {@code name [volume [pitch]]}, for example {@code block.note_block.chime 1.0 1.2}. */
  record Cue(String name, float volume, float pitch) {}

  /** Parses a sound line; null for a blank line (no sound), an exception for an unreadable number. */
  static Cue cue(String line) {
    if (line == null || line.isBlank()) return null;
    String[] part = line.trim().split("\\s+");
    if (part.length > 3) throw new IllegalArgumentException(line);
    float volume = part.length > 1 ? Float.parseFloat(part[1]) : 1f;
    float pitch = part.length > 2 ? Float.parseFloat(part[2]) : 1f;
    if (!(volume >= 0 && volume <= 10 && pitch >= 0.5f && pitch <= 2f)) throw new IllegalArgumentException(line);
    return new Cue(part[0], volume, pitch);
  }
}
