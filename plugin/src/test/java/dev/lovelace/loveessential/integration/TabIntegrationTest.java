package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.api.TabService;
import dev.lovelace.loveessential.module.Visibility;
import dev.lovelace.loveessential.tab.TabModule;
import java.time.Duration;
import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class TabIntegrationTest {
  ServerMock server;
  LoveEssentialPlugin plugin;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    server.addSimpleWorld("world_nether");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    // The scoreboard module is not under test here; keep its ticks out of the way.
    var scoreboardOff = plugin.module("scoreboard", false);
    waitFor(scoreboardOff::isDone);
    scoreboardOff.join();
    // A static header keeps the animation out of the "nothing changed" checks.
    plugin.getConfig().set("tab.groups._DEFAULT_.header", List.of("static"));
  }

  @AfterEach
  void end() {
    MockBukkit.unmock();
  }

  void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  <T> T internal(String name, Class<T> type) throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField(name);
    f.setAccessible(true);
    return type.cast(f.get(plugin));
  }

  PlayerMock player(String name, String world) {
    var p = server.addPlayer(name);
    p.teleport(new Location(server.getWorld(world), 0, 64, 0));
    return p;
  }

  void settle(int ticks) {
    server.getScheduler().performTicks(ticks);
  }

  /** Writes the per-world list as individual keys, the way the configuration stores it. */
  void worldList(boolean enabled) {
    plugin.getConfig().set("tab.per-world-playerlist.enabled", enabled);
    plugin.getConfig().set("tab.per-world-playerlist.refresh-ticks", 2);
    plugin.getConfig().set("tab.per-world-playerlist.bypass-permission", "loveessential.tab.worldlist.bypass");
    plugin.getConfig().set("tab.per-world-playerlist.groups.overworld", List.of("world"));
    plugin.getConfig().set("tab.per-world-playerlist.groups.nether", List.of("world_nether"));
  }

  @Test
  void perWorldPlayerListHidesOtherWorldGroupsAndVanishDoesNotUndoIt() throws Exception {
    var alice = player("Alice", "world");
    var bob = player("Bob", "world_nether");
    worldList(true);
    settle(40);
    assertFalse(alice.canSee(bob), "players in different world groups must not see each other");
    assertFalse(bob.canSee(alice));

    // Vanish holds its own reason on the same pair; releasing it must keep the world-list hide.
    var visibility = internal("visibility", Visibility.class);
    visibility.set("vanish", alice.getUniqueId(), bob.getUniqueId(), true);
    visibility.set("vanish", alice.getUniqueId(), bob.getUniqueId(), false);
    settle(40);
    assertFalse(alice.canSee(bob));

    // Turning the list off releases the world-list reason and shows the players again.
    worldList(false);
    settle(40);
    assertTrue(alice.canSee(bob));
  }

  @Test
  void bypassPermissionSeesEveryWorld() throws Exception {
    var alice = player("Alice", "world");
    var bob = player("Bob", "world_nether");
    alice.addAttachment(plugin, "loveessential.tab.worldlist.bypass", true);
    worldList(true);
    settle(40);
    assertTrue(alice.canSee(bob));
    assertFalse(bob.canSee(alice));
  }

  @Test
  void unchangedTabValuesAreNotSentAgain() throws Exception {
    player("Alice", "world");
    player("Bob", "world");
    settle(40);
    var tab = internal("tab", TabModule.class);
    long settled = tab.sentValues();
    settle(120);
    assertEquals(settled, tab.sentValues(), "a second update with the same state must send nothing");
    // A real change is sent again, once.
    plugin.getConfig().set("tab.groups._DEFAULT_.customtabname", "<gold><player>");
    settle(40);
    assertTrue(tab.sentValues() > settled);
    long afterChange = tab.sentValues();
    settle(120);
    assertEquals(afterChange, tab.sentValues());
    assertNotNull(server.getPlayer("Alice").playerListName());
  }

  @Test
  void aServiceLeaseSetsATemporaryTabNameAndExpires() throws Exception {
    var bob = player("Bob", "world");
    var service = plugin.getServer().getServicesManager().load(TabService.class);
    assertNotNull(service);
    var lease =
        service.properties(
            plugin,
            bob.getUniqueId(),
            5,
            Duration.ofSeconds(30),
            Map.of("customtabname", "<gold>Босс"));
    settle(20);
    var plain = PlainTextComponentSerializer.plainText();
    assertTrue(plain.serialize(bob.playerListName()).contains("Босс"));
    lease.close();
    settle(20);
    assertFalse(plain.serialize(bob.playerListName()).contains("Босс"));
  }

  @Test
  void tabStyleTextRendersLegacyColorsAndBuiltInPlaceholders() throws Exception {
    var alice = player("Alice", "world");
    var placeholders = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    plugin.getConfig().set("tab.placeholders.date-format", "yyyy");
    var text =
        placeholders.renderRaw(
            "&7ДОБРО ПОЖАЛОВАТЬ &c▐%player%▐ %world% %online% 100% hp &#ff8800%date%", alice, alice);
    assertEquals(
        "ДОБРО ПОЖАЛОВАТЬ ▐Alice▐ world 1 100% hp " + java.time.Year.now(),
        PlainTextComponentSerializer.plainText().serialize(text));
    assertEquals("0", placeholders.papi(alice, alice, "%ping%"));
    assertEquals("20", placeholders.papi(alice, alice, "%health%"));
  }

  @Test
  void legacyColorsCanBeSwitchedOff() throws Exception {
    var alice = player("Alice", "world");
    var placeholders = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    plugin.getConfig().set("tab.legacy-colors", false);
    assertEquals(
        "&7text",
        PlainTextComponentSerializer.plainText().serialize(placeholders.renderRaw("&7text", alice, alice)));
  }
}
