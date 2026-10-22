package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDamageEvent;

/** A player is about to fall into the downed state. Cancel to let the death happen normally. Fired on the server thread. */
public final class DownedEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private final EntityDamageEvent cause;
  private boolean cancelled;

  public DownedEvent(Player downed, EntityDamageEvent cause) {
    this.downed = downed;
    this.cause = cause;
  }

  public Player getDowned() {
    return downed;
  }

  public EntityDamageEvent getCause() {
    return cause;
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
