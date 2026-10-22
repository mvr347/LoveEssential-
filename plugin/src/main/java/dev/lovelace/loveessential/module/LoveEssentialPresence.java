package dev.lovelace.loveessential.module;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.lovelace.lovecore.api.presence.PresenceOracle;
import dev.lovelace.lovecore.api.presence.SkinTexture;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;

/** LoveCore {@link PresenceOracle} over the vanish, afk, nickname and skin modules. */
public final class LoveEssentialPresence implements PresenceOracle {
  public static final String ID = "loveessential";
  private final Gameplay gameplay;
  private final Nicknames nicknames;

  public LoveEssentialPresence(Gameplay gameplay, Nicknames nicknames) {
    this.gameplay = gameplay;
    this.nicknames = nicknames;
  }

  @Override
  public String providerId() {
    return ID;
  }

  @Override
  public boolean vanished(UUID playerId) {
    var player = Bukkit.getPlayer(playerId);
    return player != null && gameplay.vanished(player);
  }

  @Override
  public boolean afk(UUID playerId) {
    var player = Bukkit.getPlayer(playerId);
    return player != null && gameplay.afk(player);
  }

  @Override
  public OptionalLong lastActivity(UUID playerId) {
    return gameplay.lastActivity(playerId);
  }

  @Override
  public Optional<Component> displayName(UUID playerId) {
    var player = Bukkit.getPlayer(playerId);
    if (player == null) return Optional.empty();
    var shown = nicknames.display(player);
    return PlainTextComponentSerializer.plainText().serialize(shown).equals(player.getName())
        ? Optional.empty()
        : Optional.of(shown);
  }

  /** The skin the player currently wears; /skin writes it into the profile, so this is it. */
  @Override
  public Optional<SkinTexture> skin(UUID playerId) {
    var player = Bukkit.getPlayer(playerId);
    if (player == null) return Optional.empty();
    for (ProfileProperty property : player.getPlayerProfile().getProperties())
      if ("textures".equalsIgnoreCase(property.getName()) && property.getSignature() != null)
        return Optional.of(new SkinTexture(property.getValue(), property.getSignature()));
    return Optional.empty();
  }
}
