package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** The downed player was brought back to their feet. Fired on the server thread. */
public final class ReviveEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private final Player reviver;

  public ReviveEvent(Player downed, Player reviver) {
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
