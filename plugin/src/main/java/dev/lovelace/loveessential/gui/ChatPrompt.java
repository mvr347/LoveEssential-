package dev.lovelace.loveessential.gui;

import dev.lovelace.loveessential.lang.Lang;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

/** All prompt text is localized; cancel tokens prevent old chat buttons cancelling new input. */
public final class ChatPrompt {
  private static final com.github.benmanes.caffeine.cache.Cache<java.util.UUID, String> OWNERS =
      com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
          .maximumSize(1024)
          .expireAfterWrite(java.time.Duration.ofMinutes(4))
          .build();

  private ChatPrompt() {}

  public static void claim(java.util.UUID player, String token) {
    OWNERS.put(player, token);
  }

  public static boolean owns(java.util.UUID player, String token) {
    return token.equals(OWNERS.getIfPresent(player));
  }

  public static void release(java.util.UUID player, String token) {
    OWNERS.asMap().remove(player, token);
  }

  public static void send(
      Player player,
      Lang lang,
      String token,
      Component context,
      Component instruction,
      String example,
      String current) {
    send(
        player,
        lang,
        token,
        context,
        instruction,
        example,
        current,
        "/loveessential:loveessential cancel " + token);
  }

  public static void send(
      Player player,
      Lang lang,
      String token,
      Component context,
      Component instruction,
      String example,
      String current,
      String cancelCommand) {
    claim(player.getUniqueId(), token);
    MenuStyle.suspend(player);
    player.sendMessage(lang.rich("input.context", Map.of("context", context)));
    player.sendMessage(instruction);
    if (current != null && !current.isBlank())
      lang.send(player, "input.current", Map.of("value", current));
    if (example != null && !example.isBlank())
      lang.send(player, "input.example", Map.of("value", example));
    player.sendMessage(
        lang.render("input.cancel-button")
            .clickEvent(ClickEvent.runCommand(cancelCommand))
            .hoverEvent(lang.render("input.cancel-hover")));
    lang.send(player, "input.delivery");
  }
}
