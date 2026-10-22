package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Somebody is about to start reviving a downed player. Cancel to refuse. Fired on the server thread. */
public final class StartRevivingEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private final Player reviver;
  private boolean cancelled;

  public StartRevivingEvent(Player downed, Player reviver) {
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
