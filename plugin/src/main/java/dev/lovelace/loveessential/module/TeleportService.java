package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.Position;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class TeleportService {
  private final JavaPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;

  private record PortalCommand(
      UUID player, dev.lovelace.loveessential.portal.PortalDefinition region) {}

  private PortalCommand portalCommand;

  /** Scoped to synchronous command dispatch; an RTP request captures its own immutable region. */
  public boolean portalCommand(
      Player player,
      dev.lovelace.loveessential.portal.PortalDefinition region,
      java.util.function.BooleanSupplier command) {
    MainThread.require();
    var previous = portalCommand;
    portalCommand = new PortalCommand(player.getUniqueId(), region);
    try {
      return command.getAsBoolean();
    } finally {
      portalCommand = previous;
    }
  }

  public dev.lovelace.loveessential.portal.PortalDefinition portalCommandRegion(Player player) {
    MainThread.require();
    return portalCommand != null && portalCommand.player().equals(player.getUniqueId())
        ? portalCommand.region()
        : null;
  }

  /** One in-flight move per player; a newer request replaces (and refuses) the older one. */
  private record Attempt(Consumer<Boolean> outcome) {}

  private final Map<UUID, Attempt> pending = new HashMap<>();
  /** Replaced in tests: the mock server leaves async chunk loads and async moves unimplemented. */
  private TeleportPlatform platform = TeleportPlatform.PAPER;

  public TeleportService(
      JavaPlugin plugin, MainThread main, SqliteStore store, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
  }

  public void teleport(Player p, Position destination) {
    teleport(p, destination, ignored -> {});
  }

  /**
   * Same as {@link #teleport(Player, Position)}, and reports the result exactly once on the server
   * thread: {@code true} when the player has arrived, {@code false} when the move was refused or
   * superseded. Callers that charge for the move must charge only on {@code true}.
   */
  public void teleport(Player p, Position destination, Consumer<Boolean> outcome) {
    MainThread.require();
    if (!love.authenticated(p) || love.combat(p)) {
      lang.send(p, "errors.teleport-blocked");
      outcome.accept(false);
      return;
    }
    var at = destination.location();
    if (!love.territorialAccess(p, at)) {
      lang.send(p, "errors.territory");
      outcome.accept(false);
      return;
    }
    var attempt = new Attempt(outcome);
    var superseded = pending.put(p.getUniqueId(), attempt);
    if (superseded != null) superseded.outcome().accept(false);
    platform
        .loadChunk(at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4, false)
        .whenComplete(
            (chunk, error) ->
                main.execute(
                    () -> {
                      // Superseded or forgotten attempts have already reported their outcome.
                      if (pending.get(p.getUniqueId()) != attempt) return;
                      pending.remove(p.getUniqueId());
                      if (!p.isOnline()) {
                        attempt.outcome().accept(false);
                        return;
                      }
                      if (error != null
                          || chunk == null
                          || !love.authenticated(p)
                          || love.combat(p)
                          || !love.territorialAccess(p, at)) {
                        lang.send(p, "errors.teleport-blocked");
                        attempt.outcome().accept(false);
                        return;
                      }
                      Location safe = safe(at);
                      if (safe == null) {
                        lang.send(p, "errors.unsafe");
                        attempt.outcome().accept(false);
                        return;
                      }
                      if (!safe.getWorld()
                          .isChunkLoaded(safe.getBlockX() >> 4, safe.getBlockZ() >> 4)) {
                        lang.send(p, "errors.teleport-blocked");
                        attempt.outcome().accept(false);
                        return;
                      }
                      platform
                          .move(p, safe)
                          .whenComplete(
                              (ok, failure) ->
                                  main.execute(
                                      () -> {
                                        if (!p.isOnline()) {
                                          attempt.outcome().accept(false);
                                          return;
                                        }
                                        if (failure != null || !Boolean.TRUE.equals(ok)) {
                                          lang.send(p, "errors.teleport-blocked");
                                          attempt.outcome().accept(false);
                                          return;
                                        }
                                        love.stat(p, "essential.teleports", 1);
                                        attempt.outcome().accept(true);
                                      }));
                    }));
  }

  private Location safe(Location target) {
    for (int dy = 0; dy <= 4; dy++)
      for (int sign : dy == 0 ? new int[] {1} : new int[] {1, -1}) {
        var at = target.clone().add(0, dy * sign, 0);
        if (at.getY() <= at.getWorld().getMinHeight()
            || at.getY() + 2 >= at.getWorld().getMaxHeight()) continue;
        if (platform.standable(at)) return at;
      }
    return null;
  }

  /** True when a player fits at {@code at} on solid ground that cannot hurt them. */
  public static boolean standable(Location at) {
    var feet = at.getBlock();
    var head = at.clone().add(0, 1, 0).getBlock();
    var ground = at.clone().subtract(0, 1, 0).getBlock().getType();
    return feet.isPassable()
        && head.isPassable()
        && !hazardous(feet.getType())
        && !hazardous(head.getType())
        && ground.isSolid()
        && !HARMFUL_GROUND.contains(ground);
  }

  /**
   * Passable blocks that still harm or trap whoever stands in them. Plain water is allowed: lake
   * homes depend on it.
   */
  public static boolean hazardous(Material type) {
    return type == Material.LAVA || FIRE_AND_TRAPS.contains(type);
  }

  private static final Set<Material> FIRE_AND_TRAPS =
      Set.of(
          Material.FIRE,
          Material.SOUL_FIRE,
          Material.POWDER_SNOW,
          Material.COBWEB,
          Material.SWEET_BERRY_BUSH,
          Material.WITHER_ROSE,
          Material.CAMPFIRE,
          Material.SOUL_CAMPFIRE,
          Material.POINTED_DRIPSTONE);

  private static final Set<Material> HARMFUL_GROUND =
      Set.of(
          Material.LAVA,
          Material.FIRE,
          Material.MAGMA_BLOCK,
          Material.CACTUS,
          Material.CAMPFIRE,
          Material.SOUL_CAMPFIRE);

  public void forget(Player p) {
    var attempt = pending.remove(p.getUniqueId());
    if (attempt != null) attempt.outcome().accept(false);
  }
}
