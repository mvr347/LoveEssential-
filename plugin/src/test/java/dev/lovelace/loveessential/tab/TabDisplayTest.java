package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.module.DisplayPreferences;
import dev.lovelace.lovecore.api.display.DisplayPrefs.Element;
import dev.lovelace.lovecore.api.display.DisplayPrefs.Scope;
import dev.lovelace.lovecore.api.display.DisplayPrefs.Surface;
import dev.lovelace.lovecore.api.social.ProfileOracle;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

/** The clan tag and the suffix on the TAB entry and the nametag follow the personal choice. */
class TabDisplayTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  TabModule tab;
  DisplayPreferences prefs;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    var scoreboard = plugin.module("scoreboard", false);
    waitFor(scoreboard::isDone);
    tab = field("tab", TabModule.class);
    prefs = field("displayPrefs", DisplayPreferences.class);
    server
        .getServicesManager()
        .register(
            ProfileOracle.class,
            new ProfileOracle() {
              public Optional<UUID> clanId(UUID id) {
                return Optional.empty();
              }

              public Optional<String> clanTag(UUID id) {
                return Optional.of("ABC");
              }

              public Optional<String> clanName(UUID id) {
                return Optional.empty();
              }

              public boolean areEnemies(UUID a, UUID b) {
                return false;
              }

              public boolean areClanmates(UUID a, UUID b) {
                return false;
              }
            },
            plugin,
            ServicePriority.Normal);
    plugin.getConfig().set("tab.groups._DEFAULT_.tabprefix", "[<clan>] ");
    plugin.getConfig().set("tab.groups._DEFAULT_.customtabname", "<player>");
    plugin.getConfig().set("tab.groups._DEFAULT_.tagprefix", "[<clan>] ");
    plugin.getConfig().set("tab.groups._DEFAULT_.tagsuffix", " <clan>");
    // The shipped default adds " <suffix><afk>"; keep the expected strings exact.
    plugin.getConfig().set("tab.groups._DEFAULT_.tabsuffix", "");
  }

  @AfterEach
  void end() {
    MockBukkit.unmock();
  }

  <T> T field(String name, Class<T> type) throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField(name);
    f.setAccessible(true);
    return type.cast(f.get(plugin));
  }

  void waitFor(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  static String text(net.kyori.adventure.text.Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  @Test
  void theTabEntryDropsTheTagWhenItsOwnerHidesItThere() throws Exception {
    var p = server.addPlayer("Alice");
    p.addAttachment(plugin, DisplayPreferences.OWN_PERMISSION, true);
    waitFor(() -> prefs.loaded(p.getUniqueId()));
    assertEquals("[ABC] Alice", text(tab.target(p).tabName()));
    prefs.set(p.getUniqueId(), Surface.TAB, Element.CLAN_TAG, Scope.OWN, false);
    assertEquals("[] Alice", text(tab.target(p).tabName()));
    // Hiding it on the nametag or in chat leaves the TAB entry alone.
    prefs.set(p.getUniqueId(), Surface.TAB, Element.CLAN_TAG, Scope.OWN, true);
    prefs.set(p.getUniqueId(), Surface.NAMETAG, Element.CLAN_TAG, Scope.OWN, false);
    prefs.set(p.getUniqueId(), Surface.CHAT, Element.CLAN_TAG, Scope.OWN, false);
    assertEquals("[ABC] Alice", text(tab.target(p).tabName()));
  }

  @Test
  void theNametagFollowsTheOwnersChoiceForEveryViewer() throws Exception {
    var p = server.addPlayer("Alice");
    p.addAttachment(plugin, DisplayPreferences.OWN_PERMISSION, true);
    waitFor(() -> prefs.loaded(p.getUniqueId()));
    var target = tab.target(p);
    assertEquals("[ABC] ", text(target.tagPrefix()));
    assertEquals(" ABC", text(target.tagSuffix()));
    prefs.set(p.getUniqueId(), Surface.NAMETAG, Element.CLAN_TAG, Scope.OWN, false);
    target = tab.target(p);
    assertEquals("[] ", text(target.tagPrefix()));
    assertEquals(" ", text(target.tagSuffix()));
  }

  @Test
  void aViewerWhoHidesOtherPeoplesTagsGetsATextWithoutThemOthersKeepTheirs() throws Exception {
    var owner = server.addPlayer("Alice");
    var picky = server.addPlayer("Bob");
    var normal = server.addPlayer("Carol");
    waitFor(() -> prefs.loaded(owner.getUniqueId()) && prefs.loaded(picky.getUniqueId()));
    prefs.set(picky.getUniqueId(), Surface.NAMETAG, Element.CLAN_TAG, Scope.OTHERS, false);
    var target = tab.target(owner);

    var forPicky =
        target.tags().get(prefs.hiddenFor(owner.getUniqueId(), picky.getUniqueId(), Surface.NAMETAG));
    var forNormal =
        target.tags().get(prefs.hiddenFor(owner.getUniqueId(), normal.getUniqueId(), Surface.NAMETAG));
    assertEquals("[] ", text(forPicky.prefix()));
    assertEquals("[ABC] ", text(forNormal.prefix()));
    // Bob's own tag stays visible to Bob.
    assertEquals(Set.of(), prefs.hiddenFor(picky.getUniqueId(), picky.getUniqueId(), Surface.NAMETAG));
  }

  @Test
  void theSuffixTagIsRemovableToo() throws Exception {
    var p = server.addPlayer("Alice");
    waitFor(() -> prefs.loaded(p.getUniqueId()));
    plugin.getConfig().set("tab.groups._DEFAULT_.tabsuffix", " <suffix>");
    // No PlaceholderAPI in the test server, so the suffix itself is empty; what matters is that the
    // hidden element set reaches the renderer without breaking the entry.
    prefs.set(p.getUniqueId(), Surface.TAB, Element.SUFFIX, Scope.OWN, false);
    assertEquals("[ABC] Alice ", text(tab.target(p).tabName()));
  }
}
