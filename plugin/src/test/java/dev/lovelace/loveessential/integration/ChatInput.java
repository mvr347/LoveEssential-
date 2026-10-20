package dev.lovelace.loveessential.integration;

import java.util.HashSet;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.mockbukkit.mockbukkit.ServerMock;

final class ChatInput {
  static void submit(ServerMock server, Player player, String text) {
    var component = Component.text(text);
    var event =
        new io.papermc.paper.event.player.AsyncChatEvent(
            false,
            player,
            new HashSet<>(),
            io.papermc.paper.chat.ChatRenderer.defaultRenderer(),
            component,
            component,
            SignedMessage.system(text, component));
    server.getPluginManager().callEvent(event);
    server.getScheduler().performOneTick();
  }
}
