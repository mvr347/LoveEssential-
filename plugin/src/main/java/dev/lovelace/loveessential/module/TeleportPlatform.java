package dev.lovelace.loveessential.module;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * What the teleport code asks the server: an asynchronous chunk load, the shared safety check and
 * the move itself. Production uses Paper. The test server leaves the first and last of these
 * unimplemented and cannot answer block passability, so tests substitute a simpler fake.
 */
public interface TeleportPlatform {
  TeleportPlatform PAPER =
      new TeleportPlatform() {
        @Override
        public CompletableFuture<Chunk> loadChunk(World world, int x, int z, boolean generate) {
          return world.getChunkAtAsync(x, z, generate);
        }

        @Override
        public boolean standable(Location at) {
          return TeleportService.standable(at);
        }

        @Override
        public CompletableFuture<Boolean> move(Player player, Location to) {
          return player.teleportAsync(to);
        }
      };

  CompletableFuture<Chunk> loadChunk(World world, int x, int z, boolean generate);

  /** True when a player fits at {@code at} on solid ground that cannot hurt them. */
  boolean standable(Location at);

  CompletableFuture<Boolean> move(Player player, Location to);
}
