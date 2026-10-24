package dev.lovelace.loveessential.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where a world to import may live. Servers before 26.1 keep every world in its own folder with a
 * {@code level.dat}; later ones keep extra dimensions inside the main world, as
 * {@code <main world>/dimensions/minecraft/<name>}, with no {@code level.dat} of their own.
 */
final class WorldFolders {
  /** Folders a dimension creates when it has been played in. */
  private static final List<String> DIMENSION_DATA = List.of("region", "entities", "poi", "data");

  private WorldFolders() {}

  /**
   * True when {@code name} is an importable world: a folder under the server container with a
   * {@code level.dat}, or a dimension folder of the main world. Links leaving the container are
   * refused in both cases.
   */
  static boolean importable(Path container, Path mainWorld, String name) throws IOException {
    Path root = container.toRealPath();
    Path legacy = root.resolve(name);
    if (Files.isRegularFile(legacy.resolve("level.dat"))) {
      return legacy.toRealPath().getParent().equals(root);
    }
    if (mainWorld == null || !Files.isDirectory(mainWorld)) return false;
    Path main = mainWorld.toRealPath();
    if (!main.startsWith(root)) return false;
    Path dimension = main.resolve("dimensions").resolve("minecraft").resolve(name);
    if (!Files.isDirectory(dimension)) return false;
    if (!dimension.toRealPath().getParent().equals(main.resolve("dimensions").resolve("minecraft").toRealPath()))
      return false;
    for (String folder : DIMENSION_DATA) if (Files.isDirectory(dimension.resolve(folder))) return true;
    return false;
  }
}
