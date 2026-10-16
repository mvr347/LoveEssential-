package dev.lovelace.loveessential.api;

import org.bukkit.entity.Player;
import org.bukkit.event.*;

public final class NpcInteractEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();
  private final Player player;
  private final NpcDefinition npc;
  private boolean cancelled;

  public NpcInteractEvent(Player p, NpcDefinition n) {
    player = p;
    npc = n;
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
