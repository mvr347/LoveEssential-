package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired every revive tick (0.1 s) while a reviver keeps working. Fired on the server thread. */
public final class RevivingEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private final Player reviver;
  private final double progress;
  private final double required;

  public RevivingEvent(Player downed, Player reviver, double progress, double required) {
    this.downed = downed;
    this.reviver = reviver;
    this.progress = progress;
    this.required = required;
  }

  public Player getDowned() {
    return downed;
  }

  public Player getReviver() {
    return reviver;
  }

  public double getProgress() {
    return progress;
  }

  public double getRequired() {
    return required;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
