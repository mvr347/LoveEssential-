package dev.lovelace.loveessential;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Shared per-plugin dispatcher: accepted calls always settle, including shutdown. */
public final class MainThread {
  private static final Map<JavaPlugin, State> STATES =
      Collections.synchronizedMap(new IdentityHashMap<>());

  private static final class State implements Listener {
    final JavaPlugin plugin;
    final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    volatile boolean stopped;

    State(JavaPlugin plugin) {
      this.plugin = plugin;
      if (plugin.isEnabled()) Bukkit.getPluginManager().registerEvents(this, plugin);
      else stopped = true;
    }

    void stop() {
      stopped = true;
      for (var future : pending)
        future.completeExceptionally(new CancellationException("plugin-disabled"));
      pending.clear();
    }

    @EventHandler
    public void disabled(PluginDisableEvent event) {
      if (event.getPlugin() == plugin) {
        stop();
        STATES.remove(plugin, this);
        HandlerList.unregisterAll(this);
      }
    }
  }

  private final JavaPlugin plugin;
  private final State state;

  public MainThread(JavaPlugin plugin) {
    this.plugin = plugin;
    state = plugin.isEnabled() ? STATES.computeIfAbsent(plugin, State::new) : new State(plugin);
  }

  public static void stop(JavaPlugin plugin) {
    var state = STATES.computeIfAbsent(plugin, State::new);
    state.stop();
  }

  public static void detach(JavaPlugin plugin) {
    var state = STATES.remove(plugin);
    if (state != null) {
      state.stop();
      HandlerList.unregisterAll(state);
    }
  }

  public void execute(Runnable task) {
    if (!plugin.isEnabled() || state.stopped) return;
    if (Bukkit.isPrimaryThread()) task.run();
    else
      try {
        Bukkit.getScheduler()
            .runTask(
                plugin,
                () -> {
                  if (plugin.isEnabled() && !state.stopped) task.run();
                });
      } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
        state.stop();
      }
  }

  public <T> CompletableFuture<T> call(Supplier<T> task) {
    var future = new CompletableFuture<T>();
    state.pending.add(future);
    future.whenComplete((value, error) -> state.pending.remove(future));
    if (!plugin.isEnabled() || state.stopped) {
      future.completeExceptionally(new CancellationException("plugin-disabled"));
      return future;
    }
    try {
      execute(
          () -> {
            if (future.isDone()) return;
            try {
              future.complete(task.get());
            } catch (Throwable error) {
              future.completeExceptionally(error);
            }
          });
    } catch (RuntimeException error) {
      future.completeExceptionally(error);
    }
    if (!plugin.isEnabled() || state.stopped) state.stop();
    return future;
  }

  public static void require() {
    if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server-thread-required");
  }
}
