package dev.lovelace.loveessential.api.revive;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired once per second while the death timer runs. Fired on the server thread. */
public final class DownedDelayCountEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final Player downed;
  private final int secondsLeft;

  public DownedDelayCountEvent(Player downed, int secondsLeft) {
    this.downed = downed;
    this.secondsLeft = secondsLeft;
  }

  public Player getDowned() {
    return downed;
  }

  public int getSecondsLeft() {
    return secondsLeft;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
