package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Material;
import java.util.List;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class BugfixRegressionTest {
  ServerMock server;

  @BeforeEach
  void setup() {
    server = MockBukkit.mock();
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  @Test
  void lavaFireAndTrapsAreHazardsButWaterAndAirAreNot() {
    for (var danger :
        new Material[] {
          Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW, Material.COBWEB
        })
      assertTrue(TeleportService.hazardous(danger), danger.name());
    // Lake homes and plain air stay valid destinations.
    assertFalse(TeleportService.hazardous(Material.WATER));
    assertFalse(TeleportService.hazardous(Material.AIR));
    assertFalse(TeleportService.hazardous(Material.STONE));
  }

  @Test
  void theShippedDenyListBlocksFlyingAndWaterMobsButNotGroundMobsOrPlayers() throws Exception {
    var yaml = new org.bukkit.configuration.file.YamlConfiguration();
    try (var in = getClass().getResourceAsStream("/modules/sitride/sitride.yml")) {
      yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    var denied = yaml.getStringList("ride-denied-types");
    for (var type :
        List.of(
            EntityType.BAT, EntityType.PHANTOM, EntityType.PARROT, EntityType.GHAST,
            EntityType.WITHER, EntityType.BLAZE, EntityType.SQUID, EntityType.DOLPHIN))
      assertFalse(Comfort.rideableType(type, denied), type.name());
    assertFalse(Comfort.rideableType(EntityType.valueOf("HAPPY_GHAST"), denied));
    assertFalse(Comfort.rideableType(EntityType.valueOf("BREEZE"), denied));
    for (var type : List.of(EntityType.PLAYER, EntityType.PIG, EntityType.HORSE, EntityType.COW))
      assertTrue(Comfort.rideableType(type, denied), type.name());
    // The configured names are case-insensitive.
    assertFalse(Comfort.rideableType(EntityType.BAT, List.of("bat")));
  }
}
