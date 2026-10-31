package dev.lovelace.loveessential.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.module.PlayerProfiles;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.meta.SkullMeta;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlayerHeadsTest {
  ServerMock server;
  PlayerMock player;

  @BeforeEach
  void setup() {
    server = MockBukkit.mock();
    player =
        new PlayerMock(
            server,
            "Ada123",
            UUID.nameUUIDFromBytes("OfflinePlayer:Ada123".getBytes(StandardCharsets.UTF_8)));
    server.addPlayer(player);
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  @Test
  void repeatedUntexturedHeadsHaveNoAccountForPaperToComplete() {
    assertEquals(UUID.fromString("276d13c9-b8b6-3421-9c33-4960f44bf2d4"), player.getUniqueId());
    for (int i = 0; i < 10; i++) {
      var head = PlayerProfiles.head(player, Component.text("Профиль"), List.of());
      assertNull(((SkullMeta) head.getItemMeta()).getPlayerProfile());
    }
  }

  @Test
  void texturedHeadsCarryOnlyTheTextureAndDoNotShareMutableItems() throws Exception {
    var profile = player.getPlayerProfile();
    var textures = profile.getTextures();
    var url = URI.create("https://textures.minecraft.net/texture/" + "d".repeat(64)).toURL();
    textures.setSkin(url);
    profile.setTextures(textures);
    player.setPlayerProfile(profile);

    var first = PlayerProfiles.head(player, Component.text("Первый"), List.of());
    var meta = (SkullMeta) first.getItemMeta();
    assertEquals(url, meta.getPlayerProfile().getTextures().getSkin());
    assertNotEquals(player.getUniqueId(), meta.getPlayerProfile().getId());
    assertEquals("LoveTexture", meta.getPlayerProfile().getName());
    assertTrue(
        meta.getPlayerProfile().getProperties().stream()
            .anyMatch(property -> property.getName().equals("textures")));
    meta.displayName(Component.text("Изменено"));
    first.setItemMeta(meta);

    var second = PlayerProfiles.head(player, Component.text("Второй"), List.of());
    assertNotSame(first, second);
    assertEquals("Второй", GuiRules.name(second));
    assertEquals(player.getUniqueId(), player.getPlayerProfile().getId());
  }
}
