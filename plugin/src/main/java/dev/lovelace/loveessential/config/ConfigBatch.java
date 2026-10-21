package dev.lovelace.loveessential.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Stage every document before replacing any; roll back committed files if a later move fails. */
final class ConfigBatch {
  @FunctionalInterface
  interface Mover {
    void move(Path source, Path target) throws IOException;
  }

  private record Change(Path target, Path staged, byte[] original) {}

  static void write(Map<Path, String> documents) throws IOException {
    write(documents, ConfigBatch::move);
  }

  static void write(Map<Path, String> documents, Mover mover) throws IOException {
    var changes = new ArrayList<Change>();
    var committed = new ArrayList<Change>();
    try {
      for (var entry : documents.entrySet()) {
        Path target = entry.getKey();
        byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
        byte[] original = Files.exists(target) ? Files.readAllBytes(target) : null;
        if (Arrays.equals(content, original)) continue;
        Files.createDirectories(target.getParent());
        Path staged = Files.createTempFile(target.getParent(), ".config-", ".tmp");
        changes.add(new Change(target, staged, original));
        Files.write(staged, content);
      }
      for (var change : changes) {
        committed.add(change);
        mover.move(change.staged(), change.target());
      }
    } catch (IOException | RuntimeException error) {
      Collections.reverse(committed);
      for (var change : committed) {
        try {
          if (change.original() == null) Files.deleteIfExists(change.target());
          else {
            // The temporary backup stays on the same filesystem for atomic restoration.
            Files.write(change.staged(), change.original());
            move(change.staged(), change.target());
          }
        } catch (IOException rollback) {
          error.addSuppressed(rollback);
        }
      }
      throw error;
    } finally {
      for (var change : changes)
        try {
          Files.deleteIfExists(change.staged());
        } catch (IOException ignored) {
          // A stale temporary file must not hide the original commit/rollback failure.
        }
    }
  }

  static void move(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
