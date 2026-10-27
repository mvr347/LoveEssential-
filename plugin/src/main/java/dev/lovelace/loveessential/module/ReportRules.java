package dev.lovelace.loveessential.module;

import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;

/** Pure counting rules for player complaints; kept free of Bukkit so they can be unit-tested. */
final class ReportRules {
  enum State {
    OPEN,
    CONFIRMED,
    REJECTED
  }

  enum Level {
    QUIET,
    NOTIFY,
    DANGER
  }

  private ReportRules() {}

  /** Legacy records have only {@code resolved}; a handled legacy record counted for nothing. */
  static State state(String status, boolean resolved) {
    if (status != null)
      try {
        return State.valueOf(status);
      } catch (IllegalArgumentException unknown) {
        return State.REJECTED;
      }
    return resolved ? State.REJECTED : State.OPEN;
  }

  /** Different authors with an open complaint about {@code subject} inside the window. */
  static int distinctOpenAuthors(
      Collection<PlayerReports.Report> all, UUID subject, long now, long windowMillis) {
    var authors = new HashSet<UUID>();
    for (var r : all)
      if (subject.equals(r.subject())
          && r.state() == State.OPEN
          && now - r.created() <= windowMillis) authors.add(r.author());
    return authors.size();
  }

  static Level level(int authors, int notify, int danger) {
    if (authors >= danger) return Level.DANGER;
    return authors >= notify ? Level.NOTIFY : Level.QUIET;
  }

  /** True when going from {@code before} to {@code after} authors reached {@code threshold}. */
  static boolean crossed(int before, int after, int threshold) {
    return before < threshold && after >= threshold;
  }
}
