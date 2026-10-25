package dev.lovelace.loveessential.scoreboard;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.plugin.*;
import org.bukkit.scheduler.BukkitTask;

/** Version-checked legacy adapter: no unrelated LoveTweaks tasks or listeners are touched. */
public final class TweaksScoreboardBridge implements AutoCloseable {
  private record Suspended(HandlerList list, RegisteredListener listener) {}

  private final LoveEssentialPlugin plugin;
  private final List<Suspended> suspended = new ArrayList<>();
  private Plugin tweaks;
  private Object display, config;
  private Field taskField;
  private BukkitTask previousTask;
  private CommandExecutor previousExecutor;
  private PluginCommand command;
  private boolean taken;
  private boolean hadTask;

  public TweaksScoreboardBridge(LoveEssentialPlugin plugin) {
    this.plugin = plugin;
  }

  public boolean take(CommandExecutor executor) {
    tweaks = Bukkit.getPluginManager().getPlugin("LoveTweaks");
    if (tweaks == null || !tweaks.isEnabled()) return true;
    try {
      var type = tweaks.getClass();
      // LoveTweaks no longer owns a sidebar. Treat current versions like no legacy owner, while
      // retaining the adapter for older installed versions that still expose this API.
      try {
        type.getMethod("getScoreboardDisplayManager");
        type.getMethod("getScoreboardConfig");
      } catch (NoSuchMethodException removed) {
        return true;
      }
      display = type.getMethod("getScoreboardDisplayManager").invoke(tweaks);
      config = type.getMethod("getScoreboardConfig").invoke(tweaks);
      taskField = taskField(type);
      if (!BukkitTask.class.isAssignableFrom(taskField.getType())) return false;
      taskField.setAccessible(true);
      previousTask = (BukkitTask) taskField.get(tweaks);
      display.getClass().getMethod("removeAll");
      display.getClass().getMethod("updateAll");
      config.getClass().getMethod("getUpdateInterval");
      if (previousTask != null && previousTask.getOwner() != tweaks) return false;
      if (previousTask != null) {
        previousTask.cancel();
        hadTask = true;
      }
      taskField.set(tweaks, null);
      display.getClass().getMethod("removeAll").invoke(display);
      for (var handlers : HandlerList.getHandlerLists())
        for (var listener : handlers.getRegisteredListeners())
          if (listener.getPlugin() == tweaks
              && listener
                  .getListener()
                  .getClass()
                  .getName()
                  .equals("me.lovelace.loveTweaks.listeners.ScoreboardListener")) {
            suspended.add(new Suspended(handlers, listener));
            handlers.unregister(listener);
          }
      if (tweaks instanceof org.bukkit.plugin.java.JavaPlugin java) {
        command = java.getCommand("scoreboard");
        if (command != null) {
          previousExecutor = command.getExecutor();
          command.setExecutor(executor);
        }
      }
      taken = true;
      return true;
    } catch (ReflectiveOperationException | RuntimeException e) {
      plugin.getLogger().warning("[scoreboard migration] " + e.getClass().getSimpleName());
      restore();
      return false;
    }
  }

  private static Field taskField(Class<?> type) throws NoSuchFieldException {
    for (Class<?> c = type;
        c != null && org.bukkit.plugin.java.JavaPlugin.class.isAssignableFrom(c);
        c = c.getSuperclass())
      try {
        return c.getDeclaredField("scoreboardTask");
      } catch (NoSuchFieldException ignored) {
      }
    throw new NoSuchFieldException("scoreboardTask");
  }

  public boolean poll() {
    if (!taken || tweaks == null || !tweaks.isEnabled()) return true;
    try {
      var task = (BukkitTask) taskField.get(tweaks);
      if (task != null) {
        if (task.getOwner() != tweaks) return false;
        task.cancel();
        hadTask = true;
        taskField.set(tweaks, null);
        display.getClass().getMethod("removeAll").invoke(display);
      }
      return true;
    } catch (ReflectiveOperationException | RuntimeException e) {
      plugin.getLogger().warning("[scoreboard legacy reload] " + e.getClass().getSimpleName());
      return false;
    }
  }

  private void restore() {
    if (tweaks == null || !tweaks.isEnabled()) return;
    for (var s : suspended)
      if (s.listener().getPlugin().isEnabled()) s.list().register(s.listener());
    suspended.clear();
    if (command != null && previousExecutor != null) command.setExecutor(previousExecutor);
    if (taskField != null && hadTask)
      try {
        int interval =
            Math.max(
                1,
                ((Number) config.getClass().getMethod("getUpdateInterval").invoke(config))
                    .intValue());
        var task =
            Bukkit.getScheduler()
                .runTaskTimer(
                    tweaks,
                    () -> {
                      try {
                        display.getClass().getMethod("updateAll").invoke(display);
                      } catch (ReflectiveOperationException e) {
                        plugin
                            .getLogger()
                            .warning("[scoreboard legacy] " + e.getClass().getSimpleName());
                      }
                    },
                    interval,
                    interval);
        taskField.set(tweaks, task);
      } catch (ReflectiveOperationException e) {
        plugin.getLogger().warning("[scoreboard legacy restore] " + e.getClass().getSimpleName());
      }
  }

  @Override
  public void close() {
    if (taken) {
      restore();
      taken = false;
    }
  }
}
