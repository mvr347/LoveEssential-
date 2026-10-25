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
    var roots = new java.util.LinkedHashSet<Path>();
    // The folder Bukkit reports for the main world may be the world itself or its overworld
    // dimension, so its ancestors count too; every folder of the container is tried as well.
    for (Path at = mainWorld; at != null && at.toAbsolutePath().normalize().startsWith(root) && !at.equals(root); at = at.getParent())
      if (Files.isDirectory(at)) roots.add(at.toRealPath());
    try (var children = Files.newDirectoryStream(root, p -> Files.isDirectory(p))) {
      for (Path child : children) roots.add(child.toRealPath());
    }
    for (Path world : roots) {
      if (!world.startsWith(root)) continue;
      Path parent = world.resolve("dimensions").resolve("minecraft");
      Path dimension = parent.resolve(name);
      if (!Files.isDirectory(dimension)) continue;
      if (!dimension.toRealPath().getParent().equals(parent.toRealPath())) continue;
      for (String folder : DIMENSION_DATA) if (Files.isDirectory(dimension.resolve(folder))) return true;
    }
    return false;
  }
}
