package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

class TabOverridesTest {
  ServerMock server;
  final AtomicLong clock = new AtomicLong();

  @BeforeEach
  void setup() {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
  }

  @AfterEach
  void end() {
    MockBukkit.unmock();
  }

  TabOverrides overrides(int perPlayer) {
    return new TabOverrides(clock::get, () -> 600, () -> perPlayer);
  }

  @Test
  void higherPriorityWinsEachKeyAndTheNewestLeaseWinsEqualPriority() {
    var plugin = MockBukkit.createMockPlugin("Owner");
    var alice = server.addPlayer("Alice");
    var service = overrides(8);
    service.properties(plugin, alice.getUniqueId(), 1, Duration.ofSeconds(30), Map.of("tabprefix", "low", "tagprefix", "tag"));
    service.properties(plugin, alice.getUniqueId(), 5, Duration.ofSeconds(30), Map.of("tabprefix", "high"));
    service.properties(plugin, alice.getUniqueId(), 5, Duration.ofSeconds(30), Map.of("tabsuffix", "newest"));
    var merged = service.properties(alice.getUniqueId());
    assertEquals("high", merged.get("tabprefix"));
    assertEquals("tag", merged.get("tagprefix"));
    assertEquals("newest", merged.get("tabsuffix"));
  }

  @Test
  void leasesExpireOnTheirTtl() {
    var plugin = MockBukkit.createMockPlugin("Owner");
    var alice = server.addPlayer("Alice");
    var service = overrides(8);
    service.properties(plugin, alice.getUniqueId(), 0, Duration.ofSeconds(5), Map.of("tabprefix", "x"));
    assertEquals("x", service.properties(alice.getUniqueId()).get("tabprefix"));
    clock.addAndGet(Duration.ofSeconds(6).toNanos());
    assertTrue(service.properties(alice.getUniqueId()).isEmpty());
  }

  @Test
  void perPlayerLimitAndKeyWhitelistAreEnforced() {
    var plugin = MockBukkit.createMockPlugin("Owner");
    var alice = server.addPlayer("Alice");
    var service = overrides(2);
    service.properties(plugin, alice.getUniqueId(), 0, Duration.ofSeconds(5), Map.of("tabprefix", "1"));
    service.properties(plugin, alice.getUniqueId(), 0, Duration.ofSeconds(5), Map.of("tabprefix", "2"));
    assertThrows(
        IllegalStateException.class,
        () -> service.properties(plugin, alice.getUniqueId(), 0, Duration.ofSeconds(5), Map.of("tabprefix", "3")));
    // Header and footer are not overridable through the API.
    var fresh = overrides(8);
    assertThrows(
        IllegalArgumentException.class,
        () -> fresh.properties(plugin, alice.getUniqueId(), 0, Duration.ofSeconds(5), Map.of("header", "x")));
    assertThrows(
        IllegalArgumentException.class,
        () -> fresh.properties(plugin, alice.getUniqueId(), 0, Duration.ofSeconds(601), Map.of("tabprefix", "x")));
  }

  @Test
  void nametagAndBossBarRequestsFollowTheirPriority() {
    var plugin = MockBukkit.createMockPlugin("Owner");
    var alice = server.addPlayer("Alice");
    var service = overrides(8);
    service.hideNametag(plugin, alice.getUniqueId(), Duration.ofSeconds(10));
    assertTrue(service.nametagHidden(alice.getUniqueId()));
    service.bossBar(plugin, alice.getUniqueId(), 1, Duration.ofSeconds(10), Component.text("low"), 0.2f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
    service.bossBar(plugin, alice.getUniqueId(), 9, Duration.ofSeconds(10), Component.text("high"), 0.9f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
    var shown = service.bossBar(alice.getUniqueId()).orElseThrow();
    assertEquals(BossBar.Color.RED, shown.color());
    assertEquals(0.9f, shown.progress());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.bossBar(plugin, alice.getUniqueId(), 0, Duration.ofSeconds(1), Component.text("x"), 1.5f, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
  }

  @Test
  void leavingOrDisablingTheOwnerReleasesEverything() {
    var plugin = MockBukkit.createMockPlugin("Owner");
    var alice = server.addPlayer("Alice");
    var service = overrides(8);
    service.properties(plugin, alice.getUniqueId(), 0, Duration.ofSeconds(5), Map.of("tabprefix", "x"));
    service.hideNametag(plugin, alice.getUniqueId(), Duration.ofSeconds(5));
    service.removePlayer(alice.getUniqueId());
    assertTrue(service.properties(alice.getUniqueId()).isEmpty());
    assertFalse(service.nametagHidden(alice.getUniqueId()));
    var bob = server.addPlayer("Bob");
    service.properties(plugin, bob.getUniqueId(), 0, Duration.ofSeconds(5), Map.of("tabprefix", "y"));
    service.removeOwner(plugin);
    assertTrue(service.properties(bob.getUniqueId()).isEmpty());
  }
}
