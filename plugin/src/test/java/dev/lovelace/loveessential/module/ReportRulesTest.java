package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ReportRulesTest {
  static final long DAY = 86_400_000L;
  static final long NOW = 100 * DAY;
  static final UUID SUBJECT = UUID.randomUUID();

  static PlayerReports.Report open(UUID author, UUID subject, long created) {
    return new PlayerReports.Report(
        author, "a", subject, "s", "long enough reason", created, false, "OPEN", null, 0);
  }

  @Test
  void countsDistinctAuthorsNotReports() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    var all = List.of(open(a, SUBJECT, NOW - DAY), open(a, SUBJECT, NOW - 2 * DAY), open(b, SUBJECT, NOW));
    assertEquals(2, ReportRules.distinctOpenAuthors(all, SUBJECT, NOW, 7 * DAY));
  }

  @Test
  void ignoresOldHandledAndOtherSubjects() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    var handled = open(b, SUBJECT, NOW).handled(ReportRules.State.REJECTED, "staff", NOW);
    var all =
        List.of(
            open(a, SUBJECT, NOW - 8 * DAY),
            handled,
            open(c, UUID.randomUUID(), NOW),
            open(UUID.randomUUID(), SUBJECT, NOW));
    assertEquals(1, ReportRules.distinctOpenAuthors(all, SUBJECT, NOW, 7 * DAY));
  }

  @Test
  void legacyRecordsKeepTheirMeaning() {
    var unresolved =
        new PlayerReports.Report(UUID.randomUUID(), "a", SUBJECT, "s", "legacy text here", NOW, false);
    var resolved =
        new PlayerReports.Report(UUID.randomUUID(), "a", SUBJECT, "s", "legacy text here", NOW, true);
    assertEquals(ReportRules.State.OPEN, unresolved.state());
    assertEquals(ReportRules.State.REJECTED, resolved.state());
    assertEquals(1, ReportRules.distinctOpenAuthors(List.of(unresolved), SUBJECT, NOW, 7 * DAY));
    assertEquals(0, ReportRules.distinctOpenAuthors(List.of(resolved), SUBJECT, NOW, 7 * DAY));
  }

  @Test
  void unknownStatusNeverCountsAsOpen() {
    assertEquals(ReportRules.State.REJECTED, ReportRules.state("SOMETHING_NEW", false));
  }

  @Test
  void levels() {
    assertEquals(ReportRules.Level.QUIET, ReportRules.level(2, 3, 5));
    assertEquals(ReportRules.Level.NOTIFY, ReportRules.level(3, 3, 5));
    assertEquals(ReportRules.Level.NOTIFY, ReportRules.level(4, 3, 5));
    assertEquals(ReportRules.Level.DANGER, ReportRules.level(5, 3, 5));
  }

  @Test
  void alertsOnlyWhenThresholdIsCrossed() {
    assertFalse(ReportRules.crossed(1, 2, 3));
    assertTrue(ReportRules.crossed(2, 3, 3));
    assertFalse(ReportRules.crossed(3, 4, 3));
    assertTrue(ReportRules.crossed(4, 5, 5));
  }
}
