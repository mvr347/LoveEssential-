package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Material;
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
  void flyingMobsCannotBeRiddenButGroundMobsAndPlayersCan() {
    var world = server.addSimpleWorld("world");
    var at = world.getSpawnLocation();
    assertTrue(Comfort.rideableKind(server.addPlayer("Carrier")));
    assertTrue(Comfort.rideableKind(world.spawn(at, Pig.class)));
    assertFalse(Comfort.rideableKind(world.spawn(at, Bat.class)));
    assertFalse(Comfort.rideableKind(world.spawn(at, Phantom.class)));
    assertFalse(Comfort.rideableKind(world.spawn(at, Parrot.class)));
  }
}
