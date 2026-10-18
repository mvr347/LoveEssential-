package dev.lovelace.loveessential.api;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.*;

/** Synchronous pre-delivery hook for chat filters. Text is always rendered as plain text. */
public final class PrivateMessageEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();
  private final Player sender, recipient;
  private String message;
  private boolean cancelled;

  public PrivateMessageEvent(Player sender, Player recipient, String message) {
    this.sender = sender;
    this.recipient = recipient;
    this.message = Objects.requireNonNull(message);
  }

  public Player getSender() {
    return sender;
  }

  public Player getRecipient() {
    return recipient;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String value) {
    message = Objects.requireNonNull(value);
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public void setCancelled(boolean value) {
    cancelled = value;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
