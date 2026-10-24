package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.gui.GuiLayout;
import dev.lovelace.loveessential.gui.GuiRules;
import dev.lovelace.loveessential.module.DisplayPreferences;
import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.display.DisplayPrefs;
import dev.lovelace.lovecore.api.display.DisplayPrefs.Element;
import dev.lovelace.lovecore.api.display.DisplayPrefs.Scope;
import dev.lovelace.lovecore.api.display.DisplayPrefs.Surface;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The personal tag/suffix choice: storage, the LoveCore contract, the command and the menu. */
class DisplayPreferencesTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  DisplayPreferences prefs;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    var scoreboard = plugin.module("scoreboard", false);
    waitFor(scoreboard::isDone);
    var field = LoveEssentialPlugin.class.getDeclaredField("displayPrefs");
    field.setAccessible(true);
    prefs = (DisplayPreferences) field.get(plugin);
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

  PlayerMock join(String name) throws Exception {
    var p = server.addPlayer(name);
    waitFor(() -> prefs.loaded(p.getUniqueId()));
    return p;
  }

  /** Waits until the flags of this player are in the database, i.e. the write has finished. */
  void waitUntilSaved(java.util.UUID id, int hidden) throws Exception {
    var field = LoveEssentialPlugin.class.getDeclaredField("store");
    field.setAccessible(true);
    var store = (dev.lovelace.loveessential.storage.SqliteStore) field.get(plugin);
    waitFor(
        () ->
            store
                .get("display-prefs", id.toString(), DisplayPreferences.Stored.class)
                .join()
                .map(d -> d.value().hidden() == hidden)
                .orElse(false));
  }

  @Test
  void everythingIsShownUntilThePlayerChangesSomething() throws Exception {
    var a = join("Alice");
    for (var s : Surface.values())
      for (var e : Element.values())
        for (var c : Scope.values()) assertTrue(prefs.shown(a.getUniqueId(), s, e, c));
  }

  @Test
  void flagsSurviveAReloginAndStayIndependent() throws Exception {
    var id = java.util.UUID.randomUUID();
    var a = new PlayerMock(server, "Alice", id);
    server.addPlayer(a);
    waitFor(() -> prefs.loaded(id));
    prefs.set(id, Surface.CHAT, Element.CLAN_TAG, Scope.OWN, false);
    prefs.set(id, Surface.NAMETAG, Element.SUFFIX, Scope.OTHERS, false);
    int expected =
        DisplayPreferences.bitOf(Surface.CHAT, Element.CLAN_TAG, Scope.OWN)
            | DisplayPreferences.bitOf(Surface.NAMETAG, Element.SUFFIX, Scope.OTHERS);
    waitUntilSaved(id, expected);
    a.disconnect();
    server.getScheduler().performTicks(2);
    assertTrue(prefs.shown(id, Surface.CHAT, Element.CLAN_TAG, Scope.OWN), "unloaded players show everything");

    var again = new PlayerMock(server, "Alice", id);
    server.addPlayer(again);
    waitFor(() -> prefs.loaded(again.getUniqueId()));
    assertFalse(prefs.shown(again.getUniqueId(), Surface.CHAT, Element.CLAN_TAG, Scope.OWN));
    assertFalse(prefs.shown(again.getUniqueId(), Surface.NAMETAG, Element.SUFFIX, Scope.OTHERS));
    assertTrue(prefs.shown(again.getUniqueId(), Surface.CHAT, Element.SUFFIX, Scope.OWN));
    assertTrue(prefs.shown(again.getUniqueId(), Surface.TAB, Element.CLAN_TAG, Scope.OWN));
  }

  @Test
  void showAllResetsEveryFlagAtOnce() throws Exception {
    var id = join("Alice").getUniqueId();
    prefs.set(id, Surface.TAB, Element.SUFFIX, Scope.OWN, false);
    prefs.set(id, Surface.CHAT, Element.CLAN_TAG, Scope.OTHERS, false);
    prefs.setAll(id, true);
    for (var s : Surface.values())
      for (var e : Element.values())
        for (var c : Scope.values()) assertTrue(prefs.shown(id, s, e, c));
  }

  @Test
  void theContractIsRegisteredForLoveChatAndHidesOwnTagsOnlyWithThePermission() throws Exception {
    var owner = join("Owner").getUniqueId();
    var viewer = join("Viewer").getUniqueId();
    var contract = server.getServicesManager().load(DisplayPrefs.class);
    assertNotNull(contract, "LoveChat reads the choice through this service");
    assertTrue(LoveCore.displayVisible(owner, viewer, Surface.CHAT, Element.CLAN_TAG));
    contract.set(owner, Surface.CHAT, Element.CLAN_TAG, Scope.OWN, false);
    assertTrue(
        LoveCore.displayVisible(owner, viewer, Surface.CHAT, Element.CLAN_TAG),
        "a player without loveessential.display.own cannot hide their tag from everybody");
    server.getPlayer("Owner").addAttachment(plugin, DisplayPreferences.OWN_PERMISSION, true);
    assertFalse(LoveCore.displayVisible(owner, viewer, Surface.CHAT, Element.CLAN_TAG));
    contract.set(owner, Surface.CHAT, Element.CLAN_TAG, Scope.OWN, true);
    contract.set(viewer, Surface.CHAT, Element.CLAN_TAG, Scope.OTHERS, false);
    assertFalse(LoveCore.displayVisible(owner, viewer, Surface.CHAT, Element.CLAN_TAG));
    assertTrue(
        LoveCore.displayVisible(viewer, viewer, Surface.CHAT, Element.CLAN_TAG),
        "the viewer's own tag is not an 'other' tag");
  }

  @Test
  void switchingTheModuleOffShowsEverythingAgain() throws Exception {
    var id = join("Alice").getUniqueId();
    prefs.set(id, Surface.CHAT, Element.SUFFIX, Scope.OWN, false);
    assertFalse(prefs.shown(id, Surface.CHAT, Element.SUFFIX, Scope.OWN));
    var off = plugin.module("display", false);
    waitFor(off::isDone);
    assertTrue(prefs.shown(id, Surface.CHAT, Element.SUFFIX, Scope.OWN));
  }

  @Test
  void placeholdersExposeEveryFlag() throws Exception {
    var id = join("Alice").getUniqueId();
    prefs.set(id, Surface.TAB, Element.CLAN_TAG, Scope.OWN, false);
    var values = prefs.values(id);
    assertEquals(12, values.size());
    assertEquals("false", values.get("display_tab_clan_own"));
    assertEquals("true", values.get("display_chat_clan_own"));
  }

  @Test
  void theCommandSetsAndTogglesAFlag() throws Exception {
    var a = join("Alice");
    server.dispatchCommand(a, "display own clan chat off");
    assertTrue(
        prefs.shown(a.getUniqueId(), Surface.CHAT, Element.CLAN_TAG, Scope.OWN),
        "the own scope needs loveessential.display.own");
    a.addAttachment(plugin, DisplayPreferences.OWN_PERMISSION, true);
    server.dispatchCommand(a, "display own clan chat off");
    assertFalse(prefs.shown(a.getUniqueId(), Surface.CHAT, Element.CLAN_TAG, Scope.OWN));
    server.dispatchCommand(a, "display own clan chat");
    assertTrue(prefs.shown(a.getUniqueId(), Surface.CHAT, Element.CLAN_TAG, Scope.OWN));
    server.dispatchCommand(a, "оформление чужие суффикс таб выкл");
    assertFalse(prefs.shown(a.getUniqueId(), Surface.TAB, Element.SUFFIX, Scope.OTHERS));
    server.dispatchCommand(a, "display suffix chat off");
    assertFalse(
        prefs.shown(a.getUniqueId(), Surface.CHAT, Element.SUFFIX, Scope.OTHERS),
        "without a scope word the command changes what the player sees");
    assertTrue(prefs.shown(a.getUniqueId(), Surface.CHAT, Element.SUFFIX, Scope.OWN));
    server.dispatchCommand(a, "display own nonsense chat");
    assertTrue(prefs.shown(a.getUniqueId(), Surface.CHAT, Element.SUFFIX, Scope.OWN), "bad input changes nothing");
  }

  @Test
  void theMenuFollowsTheLayoutStandardAndTilesToggle() throws Exception {
    var a = join("Alice");
    server.dispatchCommand(a, "display");
    server.getScheduler().performTicks(2);
    var top = a.getOpenInventory().getTopInventory();
    assertInstanceOf(GuiLayout.class, top.getHolder());
    assertEquals(List.of(), GuiRules.violations(top, false));
    assertEquals(List.of(), GuiRules.unresolvedText(top));
    assertTrue(java.util.Set.of(27, 36).contains(top.getSize()), "size " + top.getSize());

    // First tile of the work zone is the clan tag on chat; a click hides it.
    int tile = firstTile(top);
    assertTrue(tile >= 0, "six tiles are expected in the work zone");
    click(a, tile);
    assertFalse(prefs.shown(a.getUniqueId(), Surface.CHAT, Element.CLAN_TAG, Scope.OTHERS));
    assertTrue(
        prefs.shown(a.getUniqueId(), Surface.CHAT, Element.CLAN_TAG, Scope.OWN),
        "the menu never hides the player's own tag from other people");
    click(a, firstTile(a.getOpenInventory().getTopInventory()));
    assertTrue(prefs.shown(a.getUniqueId(), Surface.CHAT, Element.CLAN_TAG, Scope.OTHERS));
  }

  @Test
  void theTabTilesAreLocked() throws Exception {
    var a = join("Alice");
    server.dispatchCommand(a, "display");
    server.getScheduler().performTicks(2);
    var top = a.getOpenInventory().getTopInventory();
    assertEquals(List.of(), GuiRules.violations(top, false));
    int locked = 0;
    for (int slot = 9; slot < top.getSize() - 9; slot++) {
      var item = top.getItem(slot);
      // MenuStyle turns the barrier icon into a texture head, so the tile is found by its name.
      if (item != null
          && item.hasItemMeta()
          && item.getItemMeta().hasDisplayName()
          && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(item.getItemMeta().displayName())
              .contains("Недоступно")) locked++;
    }
    assertEquals(2, locked, "the TAB entry is shared, so both TAB tiles are locked");
  }

  int firstTile(org.bukkit.inventory.Inventory top) {
    for (int slot = 9; slot < top.getSize() - 9; slot++) {
      var item = top.getItem(slot);
      if (item != null && !item.getType().isAir() && !item.getType().name().endsWith("PANE")) return slot;
    }
    return -1;
  }

  void click(PlayerMock player, int slot) {
    server
        .getPluginManager()
        .callEvent(
            new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                slot,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL));
    server.getScheduler().performTicks(2);
  }
}
