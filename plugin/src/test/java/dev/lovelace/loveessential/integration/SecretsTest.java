package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.secrets.SecretBook;
import dev.lovelace.lovecore.api.secrets.SecretDiscoveredEvent;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The book of secrets: finding once, remembering across a rejoin, events, the placeholder values. */
class SecretsTest implements Listener {
  ServerMock server;
  LoveEssentialPlugin plugin;
  final List<String> events = new ArrayList<>();

  @EventHandler
  public void found(SecretDiscoveredEvent e) {
    events.add(e.player().getName() + ":" + e.secretId());
  }

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    server.getPluginManager().registerEvents(this, MockBukkit.createMockPlugin("Observer"));
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
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

  SecretBook book() {
    return LoveCore.provider(SecretBook.class, "loveessential").orElseThrow();
  }

  PlayerMock join(String name) throws Exception {
    var p = server.addPlayer(name);
    // The list arrives asynchronously after the join.
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      if (plugin.secrets().loaded(p.getUniqueId())) break;
      Thread.sleep(5);
    }
    assertTrue(plugin.secrets().loaded(p.getUniqueId()));
    return p;
  }

  @Test
  void aSecretIsFoundOnceAndAnnounced() throws Exception {
    var p = join("Finder");
    assertTrue(book().discover(p.getUniqueId(), "toast"));
    assertFalse(book().discover(p.getUniqueId(), "toast"), "the second find is not news");
    assertEquals(java.util.Set.of("toast"), book().found(p.getUniqueId()));
    assertEquals(List.of("Finder:toast"), events);
  }

  @Test
  void anUnknownSecretIsIgnored() throws Exception {
    var p = join("Finder");
    assertFalse(book().discover(p.getUniqueId(), "no_such_secret"));
    assertTrue(book().found(p.getUniqueId()).isEmpty());
    assertTrue(events.isEmpty());
  }

  @Test
  void nothingIsRecordedBeforeTheListHasLoaded() {
    assertFalse(book().discover(java.util.UUID.randomUUID(), "toast"));
  }

  @Test
  void findsSurviveARejoin() throws Exception {
    var p = join("Finder");
    var id = p.getUniqueId();
    assertTrue(book().discover(id, "anniversary"));
    // Give the asynchronous save time to finish before the player leaves.
    for (int i = 0; i < 40; i++) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    p.disconnect();
    assertTrue(book().found(id).isEmpty(), "an offline player has no list in memory");
    var again = new PlayerMock(server, "Finder", id);
    server.addPlayer(again);
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < end && book().found(again.getUniqueId()).isEmpty()) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertEquals(java.util.Set.of("anniversary"), book().found(again.getUniqueId()));
  }

  @Test
  void placeholderValuesCountOnlyCataloguedSecrets() throws Exception {
    var p = join("Finder");
    var before = plugin.secrets().values(p.getUniqueId());
    assertEquals("0", before.get("secrets"));
    int total = Integer.parseInt(before.get("secrets_total"));
    assertTrue(total >= 8);
    book().discover(p.getUniqueId(), "western");
    assertEquals("1", plugin.secrets().values(p.getUniqueId()).get("secrets"));
  }

  @Test
  void theModuleAccessPolicyAppliesToTheCommand() throws Exception {
    var p = join("Finder");
    plugin.getConfig().set("secrets.access.denied-worlds", java.util.List.of(p.getWorld().getName()));
    server.dispatchCommand(p, "secrets");
    server.getScheduler().performTicks(2);
    var top = p.getOpenInventory().getTopInventory();
    assertFalse(
        top != null && top.getHolder() instanceof dev.lovelace.loveessential.gui.GuiLayout,
        "a denied world must not open the book");
  }

  @Test
  void theMenuOpensForALoadedPlayer() throws Exception {
    var p = join("Finder");
    book().discover(p.getUniqueId(), "toast");
    server.dispatchCommand(p, "secrets");
    server.getScheduler().performTicks(2);
    assertNotNull(p.getOpenInventory().getTopInventory());
    assertTrue(java.util.Set.of(27, 36, 45, 54).contains(p.getOpenInventory().getTopInventory().getSize()));
  }
}
