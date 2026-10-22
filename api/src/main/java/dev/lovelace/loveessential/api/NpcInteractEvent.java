package dev.lovelace.loveessential.api;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;

public final class NpcInteractEvent extends Event implements Cancellable {
  public enum Click {
    LEFT,
    RIGHT
  }

  private static final HandlerList HANDLERS = new HandlerList();
  private final Player player;
  private final NpcDefinition npc;
  private final Click click;
  private final Entity entity;
  private boolean cancelled;

  public NpcInteractEvent(Player p, NpcDefinition n) {
    this(p, n, Click.RIGHT, null);
  }

  public NpcInteractEvent(Player p, NpcDefinition n, Click click, Entity entity) {
    player = p;
    npc = n;
    this.click = click;
    this.entity = entity;
  }

  /** Which mouse button was used. */
  public Click getClick() {
    return click;
  }

  /** The clicked body, or null when the event was built by a caller that has none. */
  public Entity getEntity() {
    return entity;
  }

  public Player getPlayer() {
    return player;
  }

  public NpcDefinition getNpc() {
    return npc;
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public void setCancelled(boolean c) {
    cancelled = c;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
