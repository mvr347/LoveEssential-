package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.module.ModulePolicy;
import java.time.Duration;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ModulePolicyTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  PlayerMock player;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!plugin.ready() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(plugin.ready());
    player = server.addPlayer("Visitor");
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  @Test
  void worldGlobsAreCaseInsensitiveAndDenyWinsEvenForOp() {
    plugin.getConfig().set("home.access.allowed-worlds", List.of("WOR*"));
    assertTrue(plugin.allows("home", player));
    player.setOp(true);
    plugin.getConfig().set("home.access.denied-worlds", List.of("world"));
    var denied =
        assertThrows(ModulePolicy.Denied.class, () -> plugin.requireAccess("home", player));
    assertEquals("module-access.world", denied.getMessage());
    assertEquals("world", denied.variables().get("world"));
    plugin.getConfig().set("home.access.denied-worlds", List.of());
    plugin.getConfig().set("home.access.allowed-worlds", List.of("world.*"));
    assertFalse(plugin.allows("home", player), "dot must be literal, not a regex wildcard");
  }

  @Test
  void gamemodesAndAllRequiredVersusAnyAllowedStates() {
    plugin.getConfig().set("home.access.denied-gamemodes", List.of("creative"));
    player.setGameMode(GameMode.CREATIVE);
    assertFalse(plugin.allows("home", player));
    player.setGameMode(GameMode.SURVIVAL);
    plugin.getConfig().set("home.access.required-states", List.of("authenticated", "sneaking"));
    assertFalse(plugin.allows("home", player));
    player.setSneaking(true);
    assertTrue(plugin.allows("home", player));
    plugin.getConfig().set("home.access.allowed-states", List.of("flying", "sneaking"));
    assertTrue(plugin.allows("home", player));
    plugin.getConfig().set("home.access.denied-states", List.of("sneaking"));
    var denied =
        assertThrows(ModulePolicy.Denied.class, () -> plugin.requireAccess("home", player));
    assertEquals("module-access.denied-state", denied.getMessage());
    assertEquals("приседание", denied.variables().get("states"));
  }

  @Test
  void nightSkipUsesActualConfigurationPrefixAndSeatingRetainsWorldRestrictions() {
    plugin.getConfig().set("night-skip.access.denied-worlds", List.of("world"));
    assertFalse(plugin.allows("nightskip", player));
    plugin.getConfig().set("sitride.access.denied-states", List.of("sneaking"));
    player.setSneaking(true);
    assertFalse(plugin.allows("sitride", player));
    assertTrue(plugin.environmentAllows("sitride", player));
    plugin.getConfig().set("sitride.access.denied-worlds", List.of("world"));
    assertFalse(plugin.environmentAllows("sitride", player));
  }

  @Test
  void invalidListsStatesAndModesAreRejectedBeforeSaving() {
    for (Object invalid : List.of("combat", List.of("unknown"), List.of(7))) {
      var config = new YamlConfiguration();
      config.set("home.access.denied-states", invalid);
      assertThrows(IllegalArgumentException.class, () -> ModulePolicy.validate(config, "home"));
    }
    var config = new YamlConfiguration();
    config.set("home.access.allowed-gamemodes", List.of("builder"));
    assertThrows(IllegalArgumentException.class, () -> ModulePolicy.validate(config, "home"));
  }
}
