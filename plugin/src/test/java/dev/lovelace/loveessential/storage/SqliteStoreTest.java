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
  void refusesUnknownFutureSchema() throws Exception {
    Path file = dir.resolve("future.db");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        var s = c.createStatement()) {
      s.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY)");
      s.execute("INSERT INTO schema_version VALUES(99)");
    }
    assertThrows(SQLException.class, () -> new SqliteStore(file, 32));
  }
}
