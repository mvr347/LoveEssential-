package dev.lovelace.loveessential.content;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.RegisteredListener;

/** Transfer only the legacy first-join listener through Bukkit's public registration API. */
public final class TweaksStarterBridge implements Listener, AutoCloseable {
  private record Suspended(HandlerList handlers, RegisteredListener listener) {}

  private final LoveEssentialPlugin plugin;
  private final List<Suspended> suspended = new ArrayList<>();

  public TweaksStarterBridge(LoveEssentialPlugin plugin) {
    this.plugin = plugin;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public void takeOwnership() {
    var tweaks = Bukkit.getPluginManager().getPlugin("LoveTweaks");
    if (tweaks == null || !tweaks.isEnabled()) return;
    for (var handlers : HandlerList.getHandlerLists())
      for (var listener : handlers.getRegisteredListeners())
        if (listener.getPlugin() == tweaks
            && listener
                .getListener()
                .getClass()
                .getName()
                .equals("me.lovelace.loveTweaks.listeners.FirstJoinItemsListener")) {
          suspended.add(new Suspended(handlers, listener));
          handlers.unregister(listener);
        }
  }

  @EventHandler
  public void enable(PluginEnableEvent e) {
    if (e.getPlugin().getName().equals("LoveTweaks"))
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (plugin.ready()) takeOwnership();
              });
  }

  @Override
  public void close() {
    HandlerList.unregisterAll(this);
    for (var entry : suspended)
      if (entry.listener().getPlugin().isEnabled()) entry.handlers().register(entry.listener());
    suspended.clear();
  }
}
