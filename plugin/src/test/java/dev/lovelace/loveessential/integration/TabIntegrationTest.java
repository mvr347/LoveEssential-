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
    plugin
        .getConfig()
        .set("tab.per-world-playerlist.bypass-permission", "loveessential.tab.worldlist.bypass");
    plugin.getConfig().set("tab.per-world-playerlist.groups.overworld", List.of("world"));
    plugin.getConfig().set("tab.per-world-playerlist.groups.nether", List.of("world_nether"));
  }

  @Test
  void aPlayerLeavingDoesNotBreakTheTabUpdateOfTheOthers() throws Exception {
    var failures = new ArrayList<String>();
    var handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord record) {
            if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue())
              failures.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    plugin.getLogger().addHandler(handler);
    var stays = player("Stays", "world");
    var leaves = player("Leaves", "world");
    settle(60);
    leaves.disconnect();
    settle(60);
    plugin.getLogger().removeHandler(handler);
    assertTrue(
        failures.stream().noneMatch(m -> m != null && m.contains("IllegalStateException")),
        failures.toString());
    // The team of the player who left is gone from the remaining viewer's board.
    assertTrue(
        stays.getScoreboard().getTeams().stream().noneMatch(t -> t.hasEntry("Leaves")),
        "stale team of the player who left");
  }

  @Test
  void rankMarkersAreRemovedButOrdinaryHashAndSuffixStay() throws Exception {
    plugin
        .getConfig()
        .set(
            "tab.groups._DEFAULT_.tabprefix",
            "<head:name:%player%> <green>#1 <gray>#>3 <gray># <gold>Легенда ");
    plugin.getConfig().set("tab.groups._DEFAULT_.tabsuffix", " <gray>🏰 Тест");
    var alice = player("Alice", "world");
    settle(20);
    String name = PlainTextComponentSerializer.plainText().serialize(alice.playerListName());
    assertFalse(name.contains("#1"), name);
    assertFalse(name.contains("#>3"), name);
    assertTrue(name.contains("# Легенда"), name);
    assertTrue(name.contains("🏰 Тест"), name);
    assertTrue(name.contains("Alice"), name);
  }

  @Test
  void aNicknameColorReachesTheTabListName() throws Exception {
    var alice = player("Alice", "world");
    var nick = internal("nicknames", dev.lovelace.loveessential.module.Nicknames.class);
    waitFor(
        () -> {
          try {
            nick.settings(alice);
            return true;
          } catch (IllegalArgumentException e) {
            return false;
          }
        });
    server.dispatchCommand(alice, "le nick color #ff0000");
    waitFor(() -> nick.settings(alice).colors().size() == 1);
    settle(40);
    var name = alice.playerListName();
    assertNotNull(name);
    var colors = new java.util.HashSet<net.kyori.adventure.text.format.TextColor>();
    collect(name, colors);
    assertTrue(colors.contains(net.kyori.adventure.text.format.TextColor.fromHexString("#ff0000")), name.toString());
  }

  private static void collect(
      net.kyori.adventure.text.Component c, java.util.Set<net.kyori.adventure.text.format.TextColor> out) {
    if (c.color() != null) out.add(c.color());
    c.children().forEach(child -> collect(child, out));
  }

  @Test
  void joiningAndStoppingNeverUseTheNullTeamNameFallback() throws Exception {
    var alice = player("Alice", "world");
    assertNotNull(alice.playerListName(), "join sets an explicit name immediately");
    settle(20);
    internal("tab", TabModule.class).stop();
    assertNotNull(alice.playerListName());
    assertEquals(
        "Alice", PlainTextComponentSerializer.plainText().serialize(alice.playerListName()));
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
    assertEquals(
        settled, tab.sentValues(), "a second update with the same state must send nothing");
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
  void aTabNameOverwrittenAfterAnUpdateIsRestoredWithoutRepeatedSends() throws Exception {
    var alice = player("Alice", "world");
    settle(40);
    var tab = internal("tab", TabModule.class);
    var expected = alice.playerListName();
    // Simulate a one-time write after TAB has cached its last successful update.
    alice.playerListName(net.kyori.adventure.text.Component.text("&a#1 &fAlice"));
    settle(40);
    assertEquals(expected, alice.playerListName(), "TAB must restore its configured player name");
    long restored = tab.sentValues();
    settle(120);
    assertEquals(restored, tab.sentValues(), "A stable restored name must not be resent");
  }

  @Test
  void tabStyleTextRendersLegacyColorsAndBuiltInPlaceholders() throws Exception {
    var alice = player("Alice", "world");
    var placeholders = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    plugin.getConfig().set("tab.placeholders.date-format", "yyyy");
    var text =
        placeholders.renderRaw(
            "&7ДОБРО ПОЖАЛОВАТЬ &c▐%player%▐ %world% %online% 100% hp &#ff8800%date%",
            alice, alice);
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
        PlainTextComponentSerializer.plainText()
            .serialize(placeholders.renderRaw("&7text", alice, alice)));
  }

  @Test
  void headTagGetsThePlayerNameAndKeepsTheSurroundingText() throws Exception {
    var alice = player("Alice", "world");
    var placeholders = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    var text = placeholders.renderRaw("<head:name:%player%> %player%", alice, alice);
    var heads =
        text.children().stream()
            .filter(
                c ->
                    c instanceof net.kyori.adventure.text.ObjectComponent o
                        && o.contents()
                            instanceof net.kyori.adventure.text.object.PlayerHeadObjectContents)
            .count();
    assertEquals(1, heads, text.toString());
    assertTrue(
        ((net.kyori.adventure.text.object.PlayerHeadObjectContents)
                ((net.kyori.adventure.text.ObjectComponent) text.children().get(0)).contents())
            .name()
            .equals("Alice"),
        text.toString());
    assertTrue(PlainTextComponentSerializer.plainText().serialize(text).endsWith("Alice"));
  }

  @Test
  void aTabPrefixWithAHeadAndLegacyColorsRendersAsConfigured() throws Exception {
    var alice = player("Alice", "world");
    var placeholders = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    var text = placeholders.renderTabName("<head:name:%player%> &7# <suffix> ", alice, alice);
    String plain = PlainTextComponentSerializer.plainText().serialize(text);
    assertFalse(plain.contains("&"), plain);
    assertTrue(plain.contains("# "), plain);
    assertTrue(
        text.children().stream()
            .anyMatch(c -> c instanceof net.kyori.adventure.text.ObjectComponent),
        text.toString());
  }

  @Test
  void anOutputReplacementWithLegacyColorsIsNotShownRaw() throws Exception {
    var alice = player("Alice", "world");
    var placeholders = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    var rank =
        dev.lovelace.loveessential.tab.PlaceholderOutput.parse(
            java.util.Map.of("1", "&a#1", "else", "&7&l#>3"));
    placeholders.configure(
        new dev.lovelace.loveessential.tab.PlaceholderRefresh(200, java.util.Map.of()),
        java.util.Map.of("%loveleaderboards_myrank%", rank));
    var text = placeholders.renderTabName("%loveleaderboards_myrank% &f<player>", alice, alice);
    String plain = PlainTextComponentSerializer.plainText().serialize(text);
    assertEquals("#>3 Alice", plain, text.toString());
    placeholders.configure(
        new dev.lovelace.loveessential.tab.PlaceholderRefresh(200, java.util.Map.of()),
        java.util.Map.of());
  }

  @Test
  void changingTheSkinImmediatelyRestoresTheTabEntryWithoutChangingVisibility() throws Exception {
    var alice = player("Alice", "world");
    var bob = player("Bob", "world");
    settle(20);
    var tab = internal("tab", TabModule.class);
    var expected = alice.playerListName();
    bob.hidePlayer(plugin, alice);
    alice.playerListName(net.kyori.adventure.text.Component.text("reset-by-profile"));
    tab.refreshProfile(alice);
    assertEquals(expected, alice.playerListName());
    assertFalse(bob.canSee(alice));
    assertEquals(alice.getUniqueId(), alice.getPlayerProfile().getId());
  }

  private static final String FIRST_SKIN =
      java.util.Base64.getEncoder()
          .encodeToString(
              "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/first\"}}}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private static final String SECOND_SKIN =
      java.util.Base64.getEncoder()
          .encodeToString(
              "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/second\"}}}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));

  PlayerMock offlinePlayer() {
    var uuid =
        UUID.nameUUIDFromBytes(
            "OfflinePlayer:Ada123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var p = new PlayerMock(server, "Ada123", uuid);
    server.addPlayer(p);
    return p;
  }

  void texture(PlayerMock player, String value) {
    var profile = player.getPlayerProfile();
    profile.removeProperty("textures");
    profile.setProperty(
        new com.destroystokyo.paper.profile.ProfileProperty("textures", value, "signed-texture"));
    player.setPlayerProfile(profile);
  }

  List<net.kyori.adventure.text.object.PlayerHeadObjectContents> heads(
      net.kyori.adventure.text.Component c) {
    var found = new ArrayList<net.kyori.adventure.text.object.PlayerHeadObjectContents>();
    if (c instanceof net.kyori.adventure.text.ObjectComponent o
        && o.contents() instanceof net.kyori.adventure.text.object.PlayerHeadObjectContents head)
      found.add(head);
    c.children().forEach(child -> found.addAll(heads(child)));
    return found;
  }

  @Test
  void offlineAccountHeadsEmbedTheCurrentSignedTextureByNameAndUuid() throws Exception {
    var p = offlinePlayer();
    assertEquals(3, p.getUniqueId().version());
    texture(p, FIRST_SKIN);
    var placeholders = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    for (String source : List.of("<head:name:%player%>", "<head:uuid:" + p.getUniqueId() + ">")) {
      var text = placeholders.renderRaw("<red>До " + source + ": после", p, p);
      var head = heads(text).getFirst();
      var property =
          head.profileProperties().stream()
              .filter(v -> v.name().equals("textures"))
              .findFirst()
              .orElseThrow();
      assertEquals(FIRST_SKIN, property.value());
      assertEquals("signed-texture", property.signature());
      assertTrue(PlainTextComponentSerializer.plainText().serialize(text).endsWith(": после"));
      assertEquals(p.getUniqueId(), p.getPlayerProfile().getId());
      assertEquals("Ada123", p.getPlayerProfile().getName());
    }
  }

  @Test
  void skinChangedImmediatelyRepaintsInlineHeadsForOfflinePlayersAndKeepsVisibility()
      throws Exception {
    plugin.getConfig().set("tab.groups._DEFAULT_.tabprefix", "<head:%player%> <gold># ");
    var p = offlinePlayer();
    var viewer = player("Observer", "world");
    viewer.hidePlayer(plugin, p);
    texture(p, FIRST_SKIN);
    plugin.skinChanged(p);
    var before = p.playerListName();
    assertEquals(FIRST_SKIN, heads(before).getFirst().profileProperties().getFirst().value());
    texture(p, SECOND_SKIN);
    plugin.skinChanged(p);
    var after = p.playerListName();
    assertNotEquals(
        before, after, "The list entry must change even though the player's nickname is unchanged");
    assertEquals(SECOND_SKIN, heads(after).getFirst().profileProperties().getFirst().value());
    assertFalse(viewer.canSee(p), "Refreshing a head must not reveal a hidden player");
    settle(2);
    assertEquals(after, p.playerListName());
  }

  @Test
  void liveTexturesKeepTheConfiguredHatAndExplicitOrUnconnectedHeadsStayUntouched()
      throws Exception {
    var p = offlinePlayer();
    texture(p, FIRST_SKIN);
    var placeholders = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    var text = placeholders.renderRaw("<head:%player%:false> <head:NotConnected>", p, p);
    assertFalse(heads(text).get(0).hat());
    assertFalse(heads(text).get(0).profileProperties().isEmpty());
    assertEquals("NotConnected", heads(text).get(1).name());
    assertTrue(heads(text).get(1).profileProperties().isEmpty());
    String explicitSource = "<head:entity/player/wide/steve>";
    var explicit = placeholders.renderRaw(explicitSource, p, p);
    var configured = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(explicitSource);
    assertNotNull(heads(explicit).getFirst().texture());
    assertEquals(heads(configured).getFirst().texture(), heads(explicit).getFirst().texture());
  }
}
