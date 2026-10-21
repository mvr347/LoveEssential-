package dev.lovelace.loveessential.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SqliteStoreTest {
  @TempDir Path dir;

  record Value(String text, List<Integer> list) {}

  @Test
  void documentsSurviveRestartAndPreserveUnicode() throws Exception {
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      assertEquals(
          1L, db.save("npc", "guard", 0, new Value("Стражник", List.of(1, 2)), "admin").get());
    }
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      var saved = db.get("npc", "guard", Value.class).get().orElseThrow();
      assertEquals(1, saved.revision());
      assertEquals("Стражник", saved.value().text());
      assertEquals(List.of(1, 2), saved.value().list());
    }
  }

  @Test
  void competingWritersCannotOverwriteARevision() throws Exception {
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      db.save("npc", "guard", 0, "original", "a").get();
      var a = db.save("npc", "guard", 1, "first", "a");
      var b = db.save("npc", "guard", 1, "second", "b");
      assertEquals(2L, a.get());
      assertInstanceOf(
          SqliteStore.Conflict.class, assertThrows(ExecutionException.class, b::get).getCause());
      assertEquals("first", db.get("npc", "guard", String.class).get().orElseThrow().value());
    }
  }

  @Test
  void duplicateCreateAndStaleDeleteAreRejected() throws Exception {
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      db.save("npc", "guard", 0, "original", "a").get();
      assertThrows(
          ExecutionException.class, () -> db.save("npc", "guard", 0, "duplicate", "a").get());
      assertThrows(ExecutionException.class, () -> db.delete("npc", "guard", 2, "a").get());
      assertTrue(db.get("npc", "guard", String.class).get().isPresent());
      db.delete("npc", "guard", 1, "a").get();
      assertTrue(db.get("npc", "guard", String.class).get().isEmpty());
    }
  }

  @Test
  void profileKeysAreNamespacedAndSqlValuesAreBound() throws Exception {
    var player = UUID.randomUUID();
    String value = "' DROP TABLE profile; -- Привет";
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      db.profile(player, "LoveQuests.flag", value).get();
      db.profile(player, "LoveClans.flag", "other").get();
      assertEquals(value, db.profile(player, "LoveQuests.flag").get().orElseThrow());
      assertEquals("other", db.profile(player, "LoveClans.flag").get().orElseThrow());
    }
  }

  @Test
  void preparationJournalIsRecoverableAfterRestart() throws Exception {
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      db.prepare("operation", Map.of("dialogs.guard.hello", "Привет")).get();
    }
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      assertEquals("Привет", db.publications().get().get("operation").get("dialogs.guard.hello"));
      db.complete("operation").get();
      assertTrue(db.publications().get().isEmpty());
    }
  }

  @Test
  void backupIncludesWalWritesAndDoesNotReplaceAnExistingFile() throws Exception {
    Path backup = dir.resolve("backup.db");
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      db.save("npc", "guard", 0, "latest", "a").get();
      db.backup(backup).get();
      assertThrows(ExecutionException.class, () -> db.backup(backup).get());
    }
    try (var db = new SqliteStore(backup, 32)) {
      assertEquals("latest", db.get("npc", "guard", String.class).get().orElseThrow().value());
    }
  }

  @Test
  void closingFlushesAcceptedWritesAndRejectsNewWork() throws Exception {
    var db = new SqliteStore(dir.resolve("data.db"), 32);
    var saves = new ArrayList<CompletableFuture<Long>>();
    for (int i = 0; i < 20; i++) saves.add(db.save("draft", Integer.toString(i), 0, "saved", "a"));
    db.close();
    for (var save : saves) assertTrue(save.isDone() && !save.isCompletedExceptionally());
    assertThrows(ExecutionException.class, () -> db.list("draft", String.class).get());
    try (var reopened = new SqliteStore(dir.resolve("data.db"), 32)) {
      assertEquals(20, reopened.list("draft", String.class).get().size());
    }
  }

  @Test
  void staleRevisionCannotDeleteARecreatedObject() throws Exception {
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      long first = db.save("npc", "guard", 0, "old", "a").get();
      db.delete("npc", "guard", first, "a").get();
      long second = db.save("npc", "guard", 0, "new", "a").get();
      assertTrue(second > first);
      assertThrows(ExecutionException.class, () -> db.delete("npc", "guard", first, "a").get());
      assertEquals("new", db.get("npc", "guard", String.class).get().orElseThrow().value());
    }
  }

  @Test
  void forcedShutdownSettlesDiscardedQueuedWrites() throws Exception {
    var db = new SqliteStore(dir.resolve("blocked.db"), 2);
    var field = SqliteStore.class.getDeclaredField("writer");
    field.setAccessible(true);
    var writer = (ThreadPoolExecutor) field.get(db);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    writer.execute(
        () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException ignored) {
          }
        });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    var queued = db.profile(UUID.randomUUID(), "test.pending", "value");
    var second = db.profile(UUID.randomUUID(), "test.pending", "second");
    var overflow = db.profile(UUID.randomUUID(), "test.pending", "overflow");
    assertTrue(overflow.isCompletedExceptionally());
    db.close(0, TimeUnit.MILLISECONDS);
    release.countDown();
    assertTrue(second.isCompletedExceptionally());
    assertTrue(queued.isDone());
    assertTrue(queued.isCompletedExceptionally());
  }

  @Test
  void npcNumbersAreStableUniqueAndAllocatedWithTheDocument() throws Exception {
    Path file = dir.resolve("numbers.db");
    try (var db = new SqliteStore(file, 16)) {
      db.save("npc", "long-technical-id", 0, "first", "a").get();
      db.save("npc", "other-id", 0, "second", "a").get();
      assertEquals(1, db.npcNumbers(List.of("long-technical-id")).get().get("long-technical-id"));
      assertEquals(2, db.npcNumbers(List.of("other-id")).get().get("other-id"));
      assertThrows(
          ExecutionException.class, () -> db.save("npc", "other-id", 0, "conflict", "a").get());
    }
    try (var db = new SqliteStore(file, 16)) {
      assertEquals(1, db.npcNumbers(List.of("long-technical-id")).get().get("long-technical-id"));
      assertEquals(2, db.npcNumbers(List.of("other-id")).get().get("other-id"));
    }
  }

  @Test
  void refusesUnknownFutureSchema() throws Exception {
    Path file = dir.resolve("future.db");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        var s = c.createStatement()) {
      s.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY)");
      s.execute("INSERT INTO schema_version VALUES(99)");
    }
    assertThrows(SQLException.class, () -> new SqliteStore(file, 32));
  }

  @Test
  void pruneBackupsKeepsOnlyTheNewestAndIgnoresOtherFiles() throws Exception {
    for (long stamp : new long[] {100, 300, 200, 400})
      Files.writeString(dir.resolve("backup-" + stamp + ".db"), "x");
    Files.writeString(dir.resolve("notes.txt"), "keep me");
    assertEquals(2, SqliteStore.pruneBackups(dir, 2));
    assertFalse(Files.exists(dir.resolve("backup-100.db")));
    assertFalse(Files.exists(dir.resolve("backup-200.db")));
    assertTrue(Files.exists(dir.resolve("backup-300.db")));
    assertTrue(Files.exists(dir.resolve("backup-400.db")));
    assertTrue(Files.exists(dir.resolve("notes.txt")));
    assertEquals(0, SqliteStore.pruneBackups(dir.resolve("missing"), 2));
    // A zero or negative limit must never wipe every backup.
    assertEquals(1, SqliteStore.pruneBackups(dir, 0));
    assertTrue(Files.exists(dir.resolve("backup-400.db")));
  }

  @Test
  void profileWritesNotifyListenersSoCachesDropStaleValues() throws Exception {
    try (var db = new SqliteStore(dir.resolve("data.db"), 32)) {
      var seen = new CopyOnWriteArrayList<String>();
      db.onProfileWritten((player, key) -> seen.add(player + "/" + key));
      var id = UUID.randomUUID();
      db.profile(id, "loveessential.vanish", "true").get();
      assertEquals(List.of(id + "/loveessential.vanish"), seen);
      assertEquals("true", db.profile(id, "loveessential.vanish").get().orElseThrow());
    }
  }
}
