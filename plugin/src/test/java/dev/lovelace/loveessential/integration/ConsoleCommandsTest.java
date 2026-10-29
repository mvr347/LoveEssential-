package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.bukkit.GameMode;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** heal, feed, fly, god, speed and gamemode take a player and run from the console; others need the .others right. */
class ConsoleCommandsTest {
  ServerMock server;
  LoveEssentialPlugin plugin;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    server.getScheduler().performTicks(40);
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  void waitFor(BooleanSupplier test) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!test.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(test.getAsBoolean(), "async server operation did not finish");
  }

  void console(String command) {
    server.dispatchCommand(server.getConsoleSender(), command);
  }

  @Test
  void consoleHealsAndFeedsTheNamedPlayer() {
    var bob = server.addPlayer("Bob");
    bob.setHealth(4);
    bob.setFoodLevel(3);
    console("heal Bob");
    console("feed Bob");
    assertEquals(20.0, bob.getHealth(), 1e-9);
    assertEquals(20, bob.getFoodLevel());
  }

  @Test
  void consoleWithoutAPlayerNameChangesNothing() {
    var bob = server.addPlayer("Bob");
    bob.setHealth(4);
    console("heal");
    console("heal Nobody");
    assertEquals(4.0, bob.getHealth(), 1e-9);
  }

  @Test
  void consoleSetsGodModeGameModeAndSpeed() {
    var bob = server.addPlayer("Bob");
    console("god on Bob");
    assertTrue(bob.isInvulnerable());
    console("god off Bob");
    assertFalse(bob.isInvulnerable());
    console("god Bob");
    assertTrue(bob.isInvulnerable());
    console("gamemode creative Bob");
    assertEquals(GameMode.CREATIVE, bob.getGameMode());
    console("speed 5 Bob");
    assertEquals(0.5f, bob.getWalkSpeed(), 1e-6);
    console("speed 11 Bob");
    assertEquals(0.5f, bob.getWalkSpeed(), 1e-6);
  }

  @Test
  void consoleToggleFly() {
    var bob = server.addPlayer("Bob");
    console("fly Bob");
    assertTrue(bob.getAllowFlight());
    console("fly Bob");
    assertFalse(bob.getAllowFlight());
  }

  @Test
  void playerNeedsTheOthersRightForSomeoneElse() {
    PlayerMock alice = server.addPlayer("Alice");
    PlayerMock bob = server.addPlayer("Bob");
    // A joined player is visible to others only after the join handling has run.
    server.getScheduler().performTicks(40);
    alice.addAttachment(plugin, "loveessential.heal", true);
    alice.setHealth(5);
    bob.setHealth(5);
    server.dispatchCommand(alice, "heal Bob");
    assertEquals(5.0, bob.getHealth(), 1e-9, "no .others right, the other player stays as he was");
    server.dispatchCommand(alice, "heal");
    assertEquals(20.0, alice.getHealth(), 1e-9);
    alice.addAttachment(plugin, "loveessential.heal.others", true);
    assertTrue(alice.hasPermission("loveessential.heal.others"));
    while (alice.nextComponentMessage() != null) {
      // drop what was said before the command under test
    }
    server.dispatchCommand(alice, "heal Bob");
    var heard = new StringBuilder();
    for (var m = alice.nextComponentMessage(); m != null; m = alice.nextComponentMessage())
      heard.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(m)).append(" | ");
    assertEquals(20.0, bob.getHealth(), 1e-9, "alice heard: " + heard);
  }

  @Test
  void playerWithoutTheBaseRightChangesNothing() {
    PlayerMock alice = server.addPlayer("Alice");
    alice.setHealth(5);
    server.dispatchCommand(alice, "heal");
    assertEquals(5.0, alice.getHealth(), 1e-9);
  }
}
