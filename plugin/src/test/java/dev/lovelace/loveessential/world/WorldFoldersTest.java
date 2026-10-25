package dev.lovelace.loveessential.world;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldFoldersTest {
  @TempDir Path root;

  @Test
  void legacyFolderWithLevelDat() throws Exception {
    Files.createDirectories(root.resolve("arena"));
    Files.createFile(root.resolve("arena/level.dat"));
    assertTrue(WorldFolders.importable(root, root.resolve("world"), "arena"));
    assertFalse(WorldFolders.importable(root, root.resolve("world"), "other"));
  }

  @Test
  void dimensionInsideTheMainWorld() throws Exception {
    Files.createDirectories(root.resolve("world/dimensions/minecraft/spawn/region"));
    assertTrue(WorldFolders.importable(root, root.resolve("world"), "spawn"));
  }

  @Test
  void emptyDimensionFolderIsNotAWorld() throws Exception {
    Files.createDirectories(root.resolve("world/dimensions/minecraft/spawn"));
    assertFalse(WorldFolders.importable(root, root.resolve("world"), "spawn"));
  }

  @Test
  void traversalIsRefused() throws Exception {
    Files.createDirectories(root.resolve("world/dimensions/minecraft/x/region"));
    assertFalse(WorldFolders.importable(root, root.resolve("world"), "../x"));
  }

  @Test
  void mainWorldFolderPointingAtItsOverworldDimension() throws Exception {
    Files.createDirectories(root.resolve("world/dimensions/minecraft/overworld/region"));
    Files.createDirectories(root.resolve("world/dimensions/minecraft/spawn/region"));
    assertTrue(
        WorldFolders.importable(root, root.resolve("world/dimensions/minecraft/overworld"), "spawn"));
    assertTrue(WorldFolders.importable(root, null, "spawn"));
  }
}
