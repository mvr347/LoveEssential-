package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.Position;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class TeleportService {
  private final JavaPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final Map<UUID, UUID> pending = new HashMap<>();

  public TeleportService(
      JavaPlugin plugin, MainThread main, SqliteStore store, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
  }

  public void teleport(Player p, Position destination) {
    MainThread.require();
    if (!love.authenticated(p) || love.combat(p)) {
      lang.send(p, "errors.teleport-blocked");
      return;
    }
    var at = destination.location();
    if (!love.territorialAccess(p, at)) {
      lang.send(p, "errors.territory");
      return;
    }
    UUID token = UUID.randomUUID();
    pending.put(p.getUniqueId(), token);
    Position before = Position.of(p.getLocation());
    at.getWorld()
        .getChunkAtAsync(at.getBlockX() >> 4, at.getBlockZ() >> 4, false)
        .whenComplete(
            (chunk, error) ->
                main.execute(
                    () -> {
                      if (!Objects.equals(pending.get(p.getUniqueId()), token) || !p.isOnline())
                        return;
                      if (error != null
                          || chunk == null
                          || !love.authenticated(p)
                          || love.combat(p)
                          || !love.territorialAccess(p, at)) {
                        pending.remove(p.getUniqueId());
                        lang.send(p, "errors.teleport-blocked");
                        return;
                      }
                      Location safe = safe(at);
                      if (safe == null) {
                        pending.remove(p.getUniqueId());
                        lang.send(p, "errors.unsafe");
                        return;
                      }
                      store
                          .save(
                              "back",
                              p.getUniqueId().toString(),
                              0,
                              before,
                              p.getUniqueId().toString())
                          .exceptionallyCompose(
                              e ->
                                  store
                                      .get("back", p.getUniqueId().toString(), Position.class)
                                      .thenCompose(
                                          old ->
                                              store.save(
                                                  "back",
                                                  p.getUniqueId().toString(),
                                                  old.orElseThrow().revision(),
                                                  before,
                                                  p.getUniqueId().toString())))
                          .whenComplete(
                              (rev, saveError) ->
                                  main.execute(
                                      () -> {
                                        if (!pending.remove(p.getUniqueId(), token)
                                            || !p.isOnline()) return;
                                        if (saveError != null) {
                                          lang.send(p, "errors.storage");
                                          return;
                                        }
                                        if (!love.authenticated(p)
                                            || love.combat(p)
                                            || !love.territorialAccess(p, safe)
                                            || !safe.getWorld()
                                                .isChunkLoaded(
                                                    safe.getBlockX() >> 4, safe.getBlockZ() >> 4)) {
                                          lang.send(p, "errors.teleport-blocked");
                                          return;
                                        }
                                        p.teleportAsync(safe)
                                            .thenAccept(
                                                ok ->
                                                    main.execute(
                                                        () -> {
                                                          if (ok && p.isOnline()) {
                                                            lang.send(p, "teleport.done");
                                                            love.stat(p, "essential.teleports", 1);
                                                          }
                                                        }));
                                      }));
                    }));
  }

  private Location safe(Location target) {
    for (int dy = 0; dy <= 4; dy++)
      for (int sign : dy == 0 ? new int[] {1} : new int[] {1, -1}) {
        var at = target.clone().add(0, dy * sign, 0);
        if (at.getY() <= at.getWorld().getMinHeight()
            || at.getY() + 2 >= at.getWorld().getMaxHeight()) continue;
        var ground = at.clone().subtract(0, 1, 0).getBlock().getType();
        if (at.getBlock().isPassable()
            && at.clone().add(0, 1, 0).getBlock().isPassable()
            && ground.isSolid()
            && !Set.of(
                    Material.LAVA,
                    Material.FIRE,
                    Material.MAGMA_BLOCK,
                    Material.CACTUS,
                    Material.CAMPFIRE,
                    Material.SOUL_CAMPFIRE)
                .contains(ground)) return at;
      }
    return null;
  }

  public void forget(Player p) {
    pending.remove(p.getUniqueId());
  }
}
