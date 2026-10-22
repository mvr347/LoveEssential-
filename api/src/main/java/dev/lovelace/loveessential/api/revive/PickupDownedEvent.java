package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A player is about to pick up a downed player. Cancel to refuse. Fired on the server thread. */
public final class PickupDownedEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private final Player carrier;
  private boolean cancelled;

  public PickupDownedEvent(Player downed, Player carrier) {
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
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void setCancelled(boolean cancel) {
    cancelled = cancel;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
