package dev.lovelace.loveessential.storage;

import com.google.gson.Gson;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded virtual-thread I/O; one writer, WAL readers, revisions checked inside the transaction.
 */
public final class SqliteStore implements AutoCloseable {
  public record Document<T>(String id, long revision, T value) {}

  public static final class Conflict extends RuntimeException {
    public Conflict() {
      super("revision-conflict");
    }
  }

  private final HikariDataSource pool;
  private final Gson gson = new Gson();
  private final ThreadPoolExecutor writer;
  private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore readLimit = new Semaphore(64);
  private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();

  public SqliteStore(Path file, int queueCapacity) throws Exception {
    Files.createDirectories(file.toAbsolutePath().getParent());
    var config = new HikariConfig();
    config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
    config.setDriverClassName("org.sqlite.JDBC");
    config.setMaximumPoolSize(3);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(5000);
    config.setPoolName("LoveEssential-SQLite");
    config.addDataSourceProperty("busy_timeout", "5000");
    config.addDataSourceProperty("foreign_keys", "true");
    config.addDataSourceProperty("journal_mode", "WAL");
    pool = new HikariDataSource(config);
    writer =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofVirtual().name("love-sql-write-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    try (var c = pool.getConnection();
        var s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY)");
      try (var rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_version")) {
        if (rs.next() && rs.getInt(1) > 3) throw new SQLException("newer-schema");
      }
      c.setAutoCommit(false);
      try {
        s.execute(
            "CREATE TABLE IF NOT EXISTS documents(kind TEXT NOT NULL,id TEXT NOT NULL,revision"
                + " INTEGER NOT NULL,payload TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY"
                + " KEY(kind,id))");
        s.execute(
            "CREATE TABLE IF NOT EXISTS audit(sequence INTEGER PRIMARY KEY AUTOINCREMENT,kind"
                + " TEXT,id TEXT,revision INTEGER,actor TEXT,operation TEXT,created INTEGER NOT"
                + " NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS profile(player TEXT NOT NULL,key TEXT NOT NULL,value TEXT"
                + " NOT NULL,PRIMARY KEY(player,key))");
        s.execute(
            "CREATE TABLE IF NOT EXISTS publications(id TEXT PRIMARY KEY,payload TEXT NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS document_versions(kind TEXT NOT NULL,id TEXT NOT"
                + " NULL,revision INTEGER NOT NULL,PRIMARY KEY(kind,id))");
        s.execute("INSERT OR IGNORE INTO document_versions SELECT kind,id,revision FROM documents");
        s.execute("CREATE INDEX IF NOT EXISTS audit_document ON audit(kind,id,sequence)");
        s.execute("CREATE INDEX IF NOT EXISTS profile_key ON profile(key)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS npc_numbers(id TEXT PRIMARY KEY,number INTEGER NOT NULL"
                + " UNIQUE CHECK(number BETWEEN 1 AND 999))");
        s.execute("INSERT OR IGNORE INTO schema_version VALUES(3)");
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    } catch (Exception e) {
      pool.close();
      writer.shutdownNow();
      readers.shutdownNow();
      throw e;
    }
  }

  public <T> CompletableFuture<Optional<Document<T>>> get(String kind, String id, Class<T> type) {
    return read(
        c -> {
          try (var s =
              c.prepareStatement("SELECT revision,payload FROM documents WHERE kind=? AND id=?")) {
            s.setString(1, kind);
            s.setString(2, id);
            try (var r = s.executeQuery()) {
              return r.next()
                  ? Optional.of(
                      new Document<>(id, r.getLong(1), gson.fromJson(r.getString(2), type)))
                  : Optional.empty();
            }
          }
        });
  }

  public <T> CompletableFuture<List<Document<T>>> list(String kind, Class<T> type) {
    return read(
        c -> {
          var out = new ArrayList<Document<T>>();
          try (var s =
              c.prepareStatement(
                  "SELECT id,revision,payload FROM documents WHERE kind=? ORDER BY id")) {
            s.setString(1, kind);
            try (var r = s.executeQuery()) {
              while (r.next())
                out.add(
                    new Document<>(
                        r.getString(1), r.getLong(2), gson.fromJson(r.getString(3), type)));
            }
          }
          return List.copyOf(out);
        });
  }

  public <T> CompletableFuture<List<Document<T>>> listPrefix(
      String kind, String prefix, Class<T> type) {
    return read(
        c -> {
          var out = new ArrayList<Document<T>>();
          try (var s =
              c.prepareStatement(
                  "SELECT id,revision,payload FROM documents WHERE kind=? AND substr(id,1,?)=?"
                      + " ORDER BY id")) {
            s.setString(1, kind);
            s.setInt(2, prefix.length());
            s.setString(3, prefix);
            try (var r = s.executeQuery()) {
              while (r.next())
                out.add(
                    new Document<>(
                        r.getString(1), r.getLong(2), gson.fromJson(r.getString(3), type)));
            }
          }
          return List.copyOf(out);
        });
  }

  public <T> CompletableFuture<Long> save(
      String kind, String id, long expected, T value, String actor) {
    String json = gson.toJson(value);
    return write(
        c -> {
          c.setAutoCommit(false);
          try {
            long next = expected + 1;
            if (expected < 0) throw new Conflict();
            if (expected == 0)
              try (var seq =
                  c.prepareStatement(
                      "SELECT revision FROM document_versions WHERE kind=? AND id=?")) {
                seq.setString(1, kind);
                seq.setString(2, id);
                try (var r = seq.executeQuery()) {
                  if (r.next()) next = r.getLong(1) + 1;
                }
              }
            if (expected == 0) {
              try (var s =
                  c.prepareStatement("INSERT OR IGNORE INTO documents VALUES(?,?,?,?,?)")) {
                s.setString(1, kind);
                s.setString(2, id);
                s.setLong(3, next);
                s.setString(4, json);
                s.setLong(5, System.currentTimeMillis());
                if (s.executeUpdate() != 1) throw new Conflict();
              }
            } else {
              try (var s =
                  c.prepareStatement(
                      "UPDATE documents SET revision=?,payload=?,updated=? WHERE kind=? AND id=?"
                          + " AND revision=?")) {
                s.setLong(1, next);
                s.setString(2, json);
                s.setLong(3, System.currentTimeMillis());
                s.setString(4, kind);
                s.setString(5, id);
                s.setLong(6, expected);
                if (s.executeUpdate() != 1) throw new Conflict();
              }
            }
            try (var seq =
                c.prepareStatement(
                    "INSERT INTO document_versions VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET"
                        + " revision=excluded.revision")) {
              seq.setString(1, kind);
              seq.setString(2, id);
              seq.setLong(3, next);
              seq.executeUpdate();
            }
            if (kind.equals("npc")) allocateNumber(c, id);
            audit(c, kind, id, next, actor, "save");
            c.commit();
            return next;
          } catch (Exception e) {
            c.rollback();
            throw e;
          } finally {
            c.setAutoCommit(true);
          }
        });
  }

  public CompletableFuture<Void> delete(String kind, String id, long expected, String actor) {
    return write(
        c -> {
          c.setAutoCommit(false);
          try {
            try (var s =
                c.prepareStatement("DELETE FROM documents WHERE kind=? AND id=? AND revision=?")) {
              s.setString(1, kind);
              s.setString(2, id);
              s.setLong(3, expected);
              if (s.executeUpdate() != 1) throw new Conflict();
            }
            if (kind.equals("npc"))
              try (var statement = c.prepareStatement("DELETE FROM npc_numbers WHERE id=?")) {
                statement.setString(1, id);
                statement.executeUpdate();
              }
            audit(c, kind, id, expected, actor, "delete");
            c.commit();
            return null;
          } catch (Exception e) {
            c.rollback();
            throw e;
          } finally {
            c.setAutoCommit(true);
          }
        });
  }

  private int allocateNumber(Connection connection, String id) throws SQLException {
    try (var query = connection.prepareStatement("SELECT number FROM npc_numbers WHERE id=?")) {
      query.setString(1, id);
      try (var result = query.executeQuery()) {
        if (result.next()) return result.getInt(1);
      }
    }
    var occupied = new BitSet(1000);
    try (var query = connection.createStatement();
        var result = query.executeQuery("SELECT number FROM npc_numbers")) {
      while (result.next()) occupied.set(result.getInt(1));
    }
    int number = occupied.nextClearBit(1);
    if (number > 999) throw new SQLException("validation.limit");
    try (var insert = connection.prepareStatement("INSERT INTO npc_numbers VALUES(?,?)")) {
      insert.setString(1, id);
      insert.setInt(2, number);
      insert.executeUpdate();
    }
    return number;
  }

  public CompletableFuture<Map<String, Integer>> npcNumbers(Collection<String> ids) {
    var ordered = ids.stream().sorted().toList();
    return write(
        connection -> {
          connection.setAutoCommit(false);
          try {
            var result = new LinkedHashMap<String, Integer>();
            for (String id : ordered) result.put(id, allocateNumber(connection, id));
            connection.commit();
            return Map.copyOf(result);
          } catch (Exception error) {
            connection.rollback();
            throw error;
          } finally {
            connection.setAutoCommit(true);
          }
        });
  }

  private void audit(
      Connection c, String kind, String id, long revision, String actor, String operation)
      throws SQLException {
    try (var s =
        c.prepareStatement(
            "INSERT INTO audit(kind,id,revision,actor,operation,created) VALUES(?,?,?,?,?,?)")) {
      s.setString(1, kind);
      s.setString(2, id);
      s.setLong(3, revision);
      s.setString(4, actor);
      s.setString(5, operation);
      s.setLong(6, System.currentTimeMillis());
      s.executeUpdate();
    }
  }

  public record SavedPlayer(UUID id, String json) {}

  public CompletableFuture<Optional<SavedPlayer>> findPlayerProfile(String name) {
    return read(
        c -> {
          try (var s =
              c.prepareStatement(
                  "SELECT player,value FROM profile WHERE key='loveessential.player-info' AND"
                      + " (json_extract(value,'$.player')=? COLLATE NOCASE OR player=?) LIMIT 1")) {
            s.setString(1, name);
            s.setString(2, name);
            try (var r = s.executeQuery()) {
              return r.next()
                  ? Optional.of(new SavedPlayer(UUID.fromString(r.getString(1)), r.getString(2)))
                  : Optional.empty();
            }
          }
        });
  }

  public CompletableFuture<Optional<String>> profile(UUID player, String key) {
    return read(
        c -> {
          try (var s = c.prepareStatement("SELECT value FROM profile WHERE player=? AND key=?")) {
            s.setString(1, player.toString());
            s.setString(2, key);
            try (var r = s.executeQuery()) {
              return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
          }
        });
  }

  private volatile java.util.function.BiConsumer<UUID, String> profileWritten = (p, k) -> {};

  /** Lets caches in front of the store drop an entry whenever any code path rewrites it. */
  public void onProfileWritten(java.util.function.BiConsumer<UUID, String> listener) {
    profileWritten = listener;
  }

  public CompletableFuture<Void> profile(UUID player, String key, String value) {
    return profileWrite(player, key, value).thenRun(() -> profileWritten.accept(player, key));
  }

  private CompletableFuture<Void> profileWrite(UUID player, String key, String value) {
    return write(
        c -> {
          try (var s =
              c.prepareStatement(
                  "INSERT INTO profile VALUES(?,?,?) ON CONFLICT(player,key) DO UPDATE SET"
                      + " value=excluded.value")) {
            s.setString(1, player.toString());
            s.setString(2, key);
            s.setString(3, value);
            s.executeUpdate();
          }
          return null;
        });
  }

  public CompletableFuture<Void> prepare(String id, Map<String, String> texts) {
    String json = gson.toJson(texts);
    return write(
        c -> {
          try (var s =
              c.prepareStatement(
                  "INSERT INTO publications VALUES(?,?) ON CONFLICT(id) DO UPDATE SET"
                      + " payload=excluded.payload")) {
            s.setString(1, id);
            s.setString(2, json);
            s.executeUpdate();
          }
          return null;
        });
  }

  public CompletableFuture<Map<String, Map<String, String>>> publications() {
    return read(
        c -> {
          Map<String, Map<String, String>> out = new LinkedHashMap<>();
          try (var s = c.createStatement();
              var r = s.executeQuery("SELECT id,payload FROM publications")) {
            while (r.next()) {
              var type = new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType();
              out.put(r.getString(1), gson.fromJson(r.getString(2), type));
            }
          }
          return out;
        });
  }

  public CompletableFuture<Void> complete(String id) {
    return write(
        c -> {
          try (var s = c.prepareStatement("DELETE FROM publications WHERE id=?")) {
            s.setString(1, id);
            s.executeUpdate();
          }
          return null;
        });
  }

  public CompletableFuture<Path> backup(Path destination) {
    return write(
        c -> {
          if (Files.exists(destination)) throw new IllegalArgumentException("backup-exists");
          Files.createDirectories(destination.toAbsolutePath().getParent());
          try (var s = c.createStatement()) {
            s.execute(
                "VACUUM INTO '" + destination.toAbsolutePath().toString().replace("'", "''") + "'");
          }
          return destination;
        });
  }

  /** Deletes the oldest {@code backup-*.db} files so that at most {@code keep} remain. */
  public static int pruneBackups(Path directory, int keep) throws java.io.IOException {
    if (!Files.isDirectory(directory)) return 0;
    List<Path> files;
    try (var stream = Files.list(directory)) {
      files =
          stream
              .filter(f -> f.getFileName().toString().matches("backup-\\d+\\.db"))
              .sorted(
                  Comparator.comparingLong(
                      (Path f) ->
                          Long.parseLong(f.getFileName().toString().replaceAll("[^0-9]", ""))))
              .toList();
    }
    int removed = 0;
    for (var file : files.subList(0, Math.max(0, files.size() - Math.max(1, keep)))) {
      Files.deleteIfExists(file);
      removed++;
    }
    return removed;
  }

  @FunctionalInterface
  private interface Work<T> {
    T run(Connection c) throws Exception;
  }

  private <T> CompletableFuture<T> write(Work<T> work) {
    return submit(writer, work, false);
  }

  private <T> CompletableFuture<T> read(Work<T> work) {
    return submit(readers, work, true);
  }

  private <T> CompletableFuture<T> submit(Executor executor, Work<T> work, boolean limited) {
    var future = new CompletableFuture<T>();
    if (closed.get() || (limited && !readLimit.tryAcquire())) {
      future.completeExceptionally(new RejectedExecutionException("storage-busy"));
      return future;
    }
    pending.add(future);
    future.whenComplete((value, error) -> pending.remove(future));
    try {
      executor.execute(
          () -> {
            try (var c = pool.getConnection()) {
              future.complete(work.run(c));
            } catch (Throwable e) {
              future.completeExceptionally(e);
            } finally {
              if (limited) readLimit.release();
            }
          });
    } catch (RejectedExecutionException e) {
      if (limited) readLimit.release();
      future.completeExceptionally(e);
    }
    return future;
  }

  public int queuedWrites() {
    return writer.getQueue().size();
  }

  @Override
  public void close() {
    close(20, TimeUnit.SECONDS);
  }

  void close(long timeout, TimeUnit unit) {
    if (!closed.compareAndSet(false, true)) return;
    writer.shutdown();
    readers.shutdown();
    try {
      if (!writer.awaitTermination(timeout, unit)) writer.shutdownNow();
      if (!readers.awaitTermination(timeout, unit)) readers.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      writer.shutdownNow();
      readers.shutdownNow();
      for (var future : pending)
        future.completeExceptionally(new CancellationException("storage-closed"));
      pending.clear();
      pool.close();
    }
  }
}
