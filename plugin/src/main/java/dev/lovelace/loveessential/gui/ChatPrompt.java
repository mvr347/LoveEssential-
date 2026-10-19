package dev.lovelace.loveessential.gui;

import dev.lovelace.loveessential.lang.Lang;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

/** All prompt text is localized; cancel tokens prevent old chat buttons cancelling new input. */
public final class ChatPrompt {
  private ChatPrompt() {}

  public static void send(
      Player player,
      Lang lang,
      String token,
      Component context,
      Component instruction,
      String example,
      String current) {
    player.sendMessage(lang.rich("input.context", Map.of("context", context)));
    player.sendMessage(instruction);
    if (current != null && !current.isBlank())
      lang.send(player, "input.current", Map.of("value", current));
    if (example != null && !example.isBlank())
      lang.send(player, "input.example", Map.of("value", example));
    player.sendMessage(
        lang.render("input.cancel-button")
            .clickEvent(ClickEvent.runCommand("/loveessential:loveessential cancel " + token))
            .hoverEvent(lang.render("input.cancel-hover")));
    lang.send(player, "input.delivery");
  }
}
