package dev.lovelace.loveessential.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired once on the server thread when the service is registered and storage is loaded. Plugins
 * that start before LoveEssential wait for it instead of reading a service that is still null.
 */
public final class LoveEssentialReadyEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final LoveEssentialService service;

  public LoveEssentialReadyEvent(LoveEssentialService service) {
    this.service = service;
  }

  public LoveEssentialService getService() {
    return service;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
