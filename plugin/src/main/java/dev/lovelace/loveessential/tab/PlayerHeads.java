package dev.lovelace.loveessential.tab;

import dev.lovelace.loveessential.MainThread;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.Bukkit;

/**
 * Inline heads of connected players use their actual skin, including offline-mode /skin choices.
 */
final class PlayerHeads {
  private PlayerHeads() {}

  static Component resolve(Component component) {
    if (!component.children().isEmpty())
      component =
          component.children(component.children().stream().map(PlayerHeads::resolve).toList());
    if (!(component instanceof ObjectComponent object)
        || !(object.contents() instanceof PlayerHeadObjectContents head)
        || head.texture() != null
        || !head.profileProperties().isEmpty()) return component;
    MainThread.require();
    var player =
        head.id() != null
            ? Bukkit.getPlayer(head.id())
            : head.name() != null ? Bukkit.getPlayerExact(head.name()) : null;
    if (player == null || !player.isOnline()) return component;
    var profile = player.getPlayerProfile();
    if (profile.getProperties().stream()
        .noneMatch(property -> property.getName().equals("textures"))) return component;
    // Embedding the signed properties avoids a client lookup of an offline UUID or nickname.
    // Paper's SkinSource adapter copies the current properties without completing the profile.
    return object.contents(ObjectContents.playerHead().skin(profile).hat(head.hat()).build());
  }
}
