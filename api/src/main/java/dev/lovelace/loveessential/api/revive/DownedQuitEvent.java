package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A downed player left the server. Fired on the server thread. */
public final class DownedQuitEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private final boolean killed;

  public DownedQuitEvent(Player downed, boolean killed) {
    this.downed = downed;
    this.killed = killed;
  }

  public Player getDowned() {
    return downed;
  }

  public boolean isKilled() {
    return killed;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
