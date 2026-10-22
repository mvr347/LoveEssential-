package dev.lovelace.loveessential.api;

import org.bukkit.entity.Player;
import org.bukkit.event.*;

/** Fired on the server thread after a player's vanish state changed (command, menu or login). */
public final class VanishChangeEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final Player player;
  private final boolean vanished;

  public VanishChangeEvent(Player player, boolean vanished) {
    this.player = player;
    this.vanished = vanished;
  }

  public Player getPlayer() {
    return player;
  }

  /** The state the player has now. */
  public boolean isVanished() {
    return vanished;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
