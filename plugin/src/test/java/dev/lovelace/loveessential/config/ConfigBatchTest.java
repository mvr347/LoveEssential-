package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigBatchTest {
  @TempDir Path directory;

  @Test
  void laterWriteFailureRestoresExistingFilesAndRemovesNewFiles() throws Exception {
    var module = directory.resolve("module.yml");
    var gui = directory.resolve("gui.yml");
    var created = directory.resolve("new.yml");
    Files.writeString(module, "original module\n");
    Files.writeString(gui, "original gui\n");
    var updates = new LinkedHashMap<Path, String>();
    updates.put(module, "updated module");
    updates.put(created, "new file");
    updates.put(gui, "updated gui");
    var count = new AtomicInteger();
    var error =
        assertThrows(
            IOException.class,
            () ->
                ConfigBatch.write(
                    updates,
                    (source, target) -> {
                      if (count.incrementAndGet() == 3)
                        throw new IOException("simulated filesystem failure");
                      ConfigBatch.move(source, target);
                    }));
    assertEquals("simulated filesystem failure", error.getMessage());
    assertEquals("original module\n", Files.readString(module));
    assertEquals("original gui\n", Files.readString(gui));
    assertFalse(Files.exists(created));
    try (var files = Files.list(directory)) {
      assertEquals(Set.of(module, gui), new HashSet<>(files.toList()));
    }
  }

  @Test
  void stagingFailureNeverChangesAlreadyPreparedDocuments() throws Exception {
    var module = directory.resolve("module.yml");
    var blocked = directory.resolve("blocked");
    Files.writeString(module, "original");
    Files.writeString(blocked, "not a directory");
    var updates = new LinkedHashMap<Path, String>();
    updates.put(module, "updated");
    updates.put(blocked.resolve("gui.yml"), "new");
    assertThrows(IOException.class, () -> ConfigBatch.write(updates));
    assertEquals("original", Files.readString(module));
    assertEquals("not a directory", Files.readString(blocked));
    try (var files = Files.list(directory)) {
      assertEquals(Set.of(module, blocked), new HashSet<>(files.toList()));
    }
  }
}
