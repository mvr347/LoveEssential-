package dev.lovelace.loveessential.api;

import java.util.Optional;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the server thread after the database has committed. */
public final class NpcChangedEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final NpcDefinition previous, current;

  public NpcChangedEvent(NpcDefinition previous, NpcDefinition current) {
    this.previous = previous;
    this.current = current;
  }

  public Optional<NpcDefinition> getPrevious() {
    return Optional.ofNullable(previous);
  }

  public Optional<NpcDefinition> getCurrent() {
    return Optional.ofNullable(current);
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
