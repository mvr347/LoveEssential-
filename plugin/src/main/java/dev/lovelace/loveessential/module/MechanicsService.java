package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.mechanics.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * No Player retention. Publishes only actual, committed module state; refused commands do nothing.
 */
public final class MechanicsService implements PlayerMechanicsService, Listener, AutoCloseable {
  private final JavaPlugin plugin;
  private final Gameplay gameplay;
  private final Revive revive;
  private final Map<UUID, PlayerMechanics> published = new HashMap<>();
  private org.bukkit.scheduler.BukkitTask task;
  private boolean closed;

  public MechanicsService(JavaPlugin plugin, Gameplay gameplay, Revive revive) {
    this.plugin = plugin;
    this.gameplay = gameplay;
    this.revive = revive;
  }

  public void start() {
    MainThread.require();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    task =
        Bukkit.getScheduler()
            .runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::refresh), 1, 1);
  }

  @Override
  public PlayerMechanics snapshot(UUID id) {
    MainThread.require();
    if (closed) return PlayerMechanics.NONE;
    var states = EnumSet.noneOf(PlayerMechanics.State.class);
    states.addAll(gameplay.mechanics(id));
    states.addAll(revive.mechanics(id));
    return states.isEmpty() ? PlayerMechanics.NONE : new PlayerMechanics(states);
  }

  public void refresh(Player p) {
    publish(p, false);
  }

  public void pulse(Player p) {
    publish(p, true);
  }

  private void publish(Player p, boolean pulse) {
    MainThread.require();
    if (closed || p == null) return;
    UUID id = p.getUniqueId();
    var current = snapshot(id);
    var previous = published.getOrDefault(id, PlayerMechanics.NONE);
    if (current.states().isEmpty()) published.remove(id);
    else published.put(id, current);
    if (pulse || !current.equals(previous))
      Bukkit.getPluginManager().callEvent(new PlayerMechanicsChangeEvent(id, current, pulse));
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    published.remove(event.getPlayer().getUniqueId());
  }

  @Override
  public void close() {
    MainThread.require();
    if (closed) return;
    closed = true;
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    for (UUID id : List.copyOf(published.keySet()))
      Bukkit.getPluginManager()
          .callEvent(new PlayerMechanicsChangeEvent(id, PlayerMechanics.NONE, false));
    published.clear();
  }
}
