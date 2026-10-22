package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A carried downed player was put down. Fired on the server thread. */
public final class DropDownedEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private final Player carrier;

  public DropDownedEvent(Player downed, Player carrier) {
    this.downed = downed;
    this.carrier = carrier;
  }

  public Player getDowned() {
    return downed;
  }

  public Player getCarrier() {
    return carrier;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
