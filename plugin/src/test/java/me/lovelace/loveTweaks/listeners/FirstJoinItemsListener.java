package me.lovelace.loveTweaks.listeners;

import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;

/** Compatibility fixture with the legacy listener's exact public class name. */
public final class FirstJoinItemsListener implements Listener {
  public int joins;

  @EventHandler
  public void join(PlayerJoinEvent event) {
    joins++;
  }
}
