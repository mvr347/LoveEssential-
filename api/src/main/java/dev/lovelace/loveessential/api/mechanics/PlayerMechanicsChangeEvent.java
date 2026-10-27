package dev.lovelace.loveessential.api.mechanics;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Main-thread notification after a state change, or a successful skin/ability refresh. */
public final class PlayerMechanicsChangeEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final UUID playerId;
  private final PlayerMechanics state;
  private final boolean transientChange;

  public PlayerMechanicsChangeEvent(UUID playerId, PlayerMechanics state, boolean transientChange) {
    this.playerId = playerId;
    this.state = state;
    this.transientChange = transientChange;
  }

  public UUID getPlayerId() {
    return playerId;
  }

  public PlayerMechanics getState() {
    return state;
  }

  public boolean isTransientChange() {
    return transientChange;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
