package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** The reviver stopped before the revive finished. Fired on the server thread. */
public final class StopRevivingEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private final Player reviver;

  public StopRevivingEvent(Player downed, Player reviver) {
    this.downed = downed;
    this.reviver = reviver;
  }

  public Player getDowned() {
    return downed;
  }

  public Player getReviver() {
    return reviver;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
