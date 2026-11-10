package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.achievements.AchievementUnlockedEvent;
import dev.lovelace.lovecore.api.achievements.Achievements;
import dev.lovelace.lovecore.api.stats.StatChangedEvent;
import dev.lovelace.lovecore.api.stats.StatSubject;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.module.Secrets;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The shared achievements: unlocking from the stat bus and the contract, queueing, persistence, migration, menu. */
class AchievementsTest implements Listener {
  ServerMock server;
  LoveEssentialPlugin plugin;
  final List<String> events = new ArrayList<>();

  @EventHandler
  public void unlocked(AchievementUnlockedEvent e) {
    events.add(e.player().getName() + ":" + e.achievementId());
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

  Achievements service() {
    return LoveCore.service(Achievements.class).orElseThrow();
  }

  PlayerMock join(String name) throws Exception {
    var p = server.addPlayer(name);
    waitFor(() -> plugin.achievements().loaded(p.getUniqueId()));
    return p;
  }

  void stat(PlayerMock p, String metric, double value, StatChangedEvent.Mode mode) {
    server.getPluginManager().callEvent(new StatChangedEvent(StatSubject.player(p.getUniqueId()), metric, value, mode));
  }

  @Test
  void theContractIsRegisteredAndKnowsTheCatalogue() throws Exception {
    assertTrue(service().catalogIds().size() >= 50, "the starting catalogue is large");
    assertTrue(service().catalogIds().contains("hunt_1"));
    assertTrue(service().catalogIds().contains("secret_toast"));
  }

  @Test
  void aStatBusMetricUnlocksAnAchievementOnceWithAnEvent() throws Exception {
    var p = join("Hunter");
    stat(p, "bounties.completed", 1, StatChangedEvent.Mode.ADD);
    assertTrue(service().unlocked(p.getUniqueId(), "hunt_1"));
    assertEquals(List.of("Hunter:hunt_1"), events);
    stat(p, "bounties.completed", 1, StatChangedEvent.Mode.ADD);
    assertEquals(List.of("Hunter:hunt_1"), events, "the second kill adds nothing new");
    stat(p, "bounties.completed", 8, StatChangedEvent.Mode.ADD);
    assertTrue(service().unlocked(p.getUniqueId(), "hunt_10"));
  }

  @Test
  void anAbsoluteMetricIsSetNotAdded() throws Exception {
    var p = join("Hunter");
    stat(p, "hunter.rating", 5, StatChangedEvent.Mode.SET);
    assertTrue(service().unlocked(p.getUniqueId(), "hunt_rating"));
  }

  @Test
  void aClanMetricIsNotAPlayersAchievement() throws Exception {
    var p = join("Hunter");
    server.getPluginManager().callEvent(
        new StatChangedEvent(StatSubject.clan(p.getUniqueId()), "bounties.completed", 100, StatChangedEvent.Mode.ADD));
    assertFalse(service().unlocked(p.getUniqueId(), "hunt_1"));
  }

  @Test
  void aFactBeforeTheProgressHasLoadedIsQueuedNotLost() throws Exception {
    var p = server.addPlayer("Quick");
    // The progress arrives asynchronously; the bounty is paid in the same moment.
    service().progress(p.getUniqueId(), "bounties.completed", 10);
    waitFor(() -> plugin.achievements().loaded(p.getUniqueId()));
    assertTrue(service().unlocked(p.getUniqueId(), "hunt_10"), "queued facts are applied after loading");
  }

  @Test
  void anUnknownAchievementOrKeyIsIgnored() throws Exception {
    var p = join("Hunter");
    assertFalse(service().unlock(p.getUniqueId(), "no_such_achievement"));
    service().progress(p.getUniqueId(), "no.such.key", 5);
    service().collect(p.getUniqueId(), "no.such.set", "x");
    assertTrue(events.isEmpty());
    assertFalse(service().unlock(java.util.UUID.randomUUID(), "hunt_1"), "an unknown player has nothing in memory");
  }

  @Test
  void progressSurvivesARejoin() throws Exception {
    var p = join("Hunter");
    var id = p.getUniqueId();
    stat(p, "bounties.completed", 4, StatChangedEvent.Mode.ADD);
    assertTrue(service().unlock(id, "hunt_1") || service().unlocked(id, "hunt_1"));
    for (int i = 0; i < 40; i++) {
      server.getScheduler().performTicks(1);
      Thread.sleep(5);
    }
    p.disconnect();
    assertTrue(service().unlockedIds(id).isEmpty(), "an offline player has nothing in memory");
    var again = new PlayerMock(server, "Hunter", id);
    server.addPlayer(again);
    waitFor(() -> plugin.achievements().loaded(id));
    assertTrue(service().unlocked(id, "hunt_1"));
    assertEquals(4.0, plugin.achievements().counter(id, "bounties.completed"), 0.001);
    stat(again, "bounties.completed", 6, StatChangedEvent.Mode.ADD);
    assertTrue(service().unlocked(id, "hunt_10"), "the counter continued where it stopped");
  }

  @Test
  void theOldBookOfSecretsBecomesAchievementsOnTheFirstLoad() throws Exception {
    var id = UUID.randomUUID();
    var field = LoveEssentialPlugin.class.getDeclaredField("store");
    field.setAccessible(true);
    var store = (SqliteStore) field.get(plugin);
    store.save("secrets", id.toString(), 0, new Secrets.Stored(Map.of("toast", 1234L, "gone", 5L)), "test").get();
    var again = new PlayerMock(server, "Old", id);
    server.addPlayer(again);
    waitFor(() -> plugin.achievements().loaded(id));
    assertTrue(service().unlocked(id, "secret_toast"));
    assertEquals(1234L, plugin.achievements().unlockedAt(id).get("secret_toast"));
    assertFalse(service().unlocked(id, "secret_gone"), "a secret that left the catalogue is dropped");
    assertEquals(java.util.Set.of("toast"), plugin.secrets().found(id));
  }

  @Test
  void theFirstJoinCountsTheDayAndTheStreak() throws Exception {
    var p = join("Regular");
    assertEquals(1.0, plugin.achievements().counter(p.getUniqueId(), "join.days"), 0.001);
    assertEquals(1.0, plugin.achievements().counter(p.getUniqueId(), "join.streak"), 0.001);
  }

  @Test
  void aRevivePaysTheReviverOnly() throws Exception {
    var downed = join("Downed");
    var helper = join("Helper");
    server.getPluginManager().callEvent(new dev.lovelace.loveessential.api.revive.ReviveEvent(downed, helper));
    assertTrue(service().unlocked(helper.getUniqueId(), "revive_1"));
    assertFalse(service().unlocked(downed.getUniqueId(), "revive_1"));
  }

  @Test
  void onlyLegendaryAchievementsAreAnnouncedToEveryone() throws Exception {
    var winner = join("Winner");
    var watcher = join("Watcher");
    plugin.getConfig().set("achievements.broadcast-rarity", "LEGENDARY");
    service().unlock(winner.getUniqueId(), "hunt_100");
    while (watcher.nextComponentMessage() != null) {}
    service().unlock(winner.getUniqueId(), "hunt_250");
    var seen = new StringBuilder();
    net.kyori.adventure.text.Component line;
    while ((line = watcher.nextComponentMessage()) != null)
      seen.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line));
    assertTrue(seen.toString().contains("Гроза преступников"), seen.toString());
    // An epic one stayed private.
    var epicSeen = new StringBuilder();
    service().unlock(winner.getUniqueId(), "brew_100");
    while ((line = watcher.nextComponentMessage()) != null)
      epicSeen.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line));
    assertFalse(epicSeen.toString().contains("Мастер-пивовар"), epicSeen.toString());
  }

  @Test
  void administratorsGiveTakeAndResetAndPlayersOnlyOpenTheMenu() throws Exception {
    var admin = join("Admin");
    admin.addAttachment(plugin, "loveessential.achievements.admin", true);
    var target = join("Target");
    server.dispatchCommand(admin, "achievements give Target hunt_1");
    assertTrue(service().unlocked(target.getUniqueId(), "hunt_1"));
    server.dispatchCommand(admin, "achievements take Target hunt_1");
    assertFalse(service().unlocked(target.getUniqueId(), "hunt_1"));
    service().unlock(target.getUniqueId(), "hunt_1");
    server.dispatchCommand(admin, "achievements reset Target");
    assertTrue(service().unlockedIds(target.getUniqueId()).isEmpty());
    // Without the permission the same words just open the menu, nothing is given.
    server.dispatchCommand(target, "achievements give Admin hunt_1");
    assertFalse(service().unlocked(admin.getUniqueId(), "hunt_1"));
  }

  @Test
  void theMenuOpensAndEveryCategoryButtonWorks() throws Exception {
    var p = join("Reader");
    service().unlock(p.getUniqueId(), "hunt_1");
    server.dispatchCommand(p, "achievements");
    server.getScheduler().performTicks(2);
    assertTrue(p.getOpenInventory().getTopInventory().getHolder() instanceof dev.lovelace.loveessential.gui.GuiLayout);
    for (String category : List.of("hunt", "craft", "duel", "play", "secret", "all")) {
      server.dispatchCommand(p, "achievements " + category);
      server.getScheduler().performTicks(2);
      var top = p.getOpenInventory().getTopInventory();
      // The menu shrinks to the rows its content needs (gui_gen sizes), so a short category is smaller than the whole.
      assertTrue(java.util.Set.of(27, 36, 45, 54).contains(top.getSize()), category + " " + top.getSize());
      assertTrue(top.getHolder() instanceof dev.lovelace.loveessential.gui.GuiLayout, category);
    }
  }
}
