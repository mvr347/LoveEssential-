package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A downed player gave up. Cancel to keep them alive. Fired on the server thread. */
public final class DownedSuicideEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private boolean cancelled;

  public DownedSuicideEvent(Player downed) {
    this.downed = downed;
  }

  public Player getDowned() {
    return downed;
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
