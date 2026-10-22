package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A downed player is about to die. Cancel to keep them downed. Fired on the server thread. */
public final class DownedDeathEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();

  public enum Cause {
    TIMER,
    SUICIDE,
    COMMAND,
    DAMAGE,
    QUIT
  }

  private final Player downed;
  private final Cause cause;
  private boolean cancelled;

  public DownedDeathEvent(Player downed, Cause cause) {
    this.downed = downed;
    this.cause = cause;
  }

  public Player getDowned() {
    return downed;
  }

  public Cause getCause() {
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
