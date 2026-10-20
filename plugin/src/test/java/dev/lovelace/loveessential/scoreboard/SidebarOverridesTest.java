package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.api.ScoreboardService;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

class SidebarOverridesTest {
  ServerMock server;

  @BeforeEach
  void setup() {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
  }

  @AfterEach
  void end() {
    MockBukkit.unmock();
  }

  ScoreboardService.Board board(String value) {
    return new ScoreboardService.Board(Component.text(value), List.of(Component.text(value)));
  }

  @Test
  void priorityLatestTieExpiryAndCloseRestorePreviousRequest() {
    var owner = MockBukkit.createMockPlugin("LoveArena");
    var player = server.addPlayer();
    var clock = new AtomicLong();
    var service = new SidebarOverrides(clock::get, () -> 600, () -> 8);
    var low =
        service.show(owner, player.getUniqueId(), 1, Duration.ofSeconds(100), true, board("low"));
    var high =
        service.show(owner, player.getUniqueId(), 5, Duration.ofSeconds(5), true, board("high"));
    assertEquals(board("high"), service.current(player.getUniqueId(), true, true).orElseThrow());
    var latest =
        service.show(owner, player.getUniqueId(), 5, Duration.ofSeconds(2), true, board("latest"));
    latest.update(board("updated"));
    assertEquals(board("updated"), service.current(player.getUniqueId(), true, true).orElseThrow());
    clock.set(Duration.ofSeconds(2).toNanos());
    assertEquals(board("high"), service.current(player.getUniqueId(), true, true).orElseThrow());
    assertThrows(IllegalStateException.class, () -> latest.update(board("expired")));
    high.close();
    high.close();
    assertEquals(board("low"), service.current(player.getUniqueId(), true, true).orElseThrow());
    low.close();
    assertTrue(service.current(player.getUniqueId(), true, true).isEmpty());
  }

  @Test
  void personalToggleForcedPolicyOwnerCleanupAndBounds() {
    var owner = MockBukkit.createMockPlugin("LoveArena");
    var other = MockBukkit.createMockPlugin("LoveQuests");
    var p = server.addPlayer();
    var service = new SidebarOverrides(() -> 0, () -> 60, () -> 2);
    service.show(owner, p.getUniqueId(), 1, Duration.ofSeconds(10), false, board("normal"));
    assertTrue(service.current(p.getUniqueId(), false, true).isEmpty());
    service.show(other, p.getUniqueId(), 2, Duration.ofSeconds(10), true, board("forced"));
    assertTrue(service.current(p.getUniqueId(), false, false).isEmpty());
    assertEquals(board("forced"), service.current(p.getUniqueId(), false, true).orElseThrow());
    assertThrows(
        IllegalStateException.class,
        () ->
            service.show(owner, p.getUniqueId(), 2, Duration.ofSeconds(10), true, board("third")));
    service.removeOwner(other);
    assertEquals(board("normal"), service.current(p.getUniqueId(), true, true).orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.show(owner, p.getUniqueId(), 2, Duration.ofSeconds(61), true, board("long")));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.show(owner, p.getUniqueId(), 2, Duration.ZERO, true, board("zero")));
    service.removePlayer(p.getUniqueId());
    assertTrue(service.current(p.getUniqueId(), true, true).isEmpty());
    service.show(owner, p.getUniqueId(), 2, Duration.ofSeconds(10), true, board("disabled"));
    server.getPluginManager().disablePlugin(owner);
    assertTrue(service.current(p.getUniqueId(), true, true).isEmpty());
    service.close();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.show(other, p.getUniqueId(), 1, Duration.ofSeconds(1), true, board("closed")));
  }
}
