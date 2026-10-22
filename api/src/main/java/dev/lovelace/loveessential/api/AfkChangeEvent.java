package dev.lovelace.loveessential.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the server thread when a player goes AFK or comes back, automatically or by command. */
public final class AfkChangeEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final Player player;
  private final boolean afk;

  public AfkChangeEvent(Player player, boolean afk) {
    this.player = player;
    this.afk = afk;
  }

  public Player getPlayer() {
    return player;
  }

  public boolean isAfk() {
    return afk;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
