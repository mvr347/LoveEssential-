package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.gui.GuiLayout;
import dev.lovelace.loveessential.gui.GuiRules;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Vanish 2.0: the player menu, the jump, and everything a hidden player stops causing. */
class VanishTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  PlayerMock admin;
  dev.lovelace.loveessential.module.Gameplay gameplay;
  final java.util.List<org.bukkit.Location> jumps = new java.util.ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    var tab = plugin.module("tab", false);
    waitFor(tab::isDone);
    var board = plugin.module("scoreboard", false);
    waitFor(board::isDone);
    var field = LoveEssentialPlugin.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    gameplay = (dev.lovelace.loveessential.module.Gameplay) field.get(plugin);
    var menus = LoveEssentialPlugin.class.getDeclaredField("menus");
    menus.setAccessible(true);
    ((dev.lovelace.loveessential.gui.ControlMenus) menus.get(plugin))
        .jumper((who, to) -> jumps.add(to));
    admin = server.addPlayer("Staff");
    admin.setOp(true);
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

  /** A new player is hidden from everybody until their saved state has loaded. */
  PlayerMock join(String name) {
    var p = server.addPlayer(name);
    try {
      waitFor(() -> admin.canSee(p));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    return p;
  }

  void hide(PlayerMock p) throws Exception {
    server.dispatchCommand(p, "vanish on");
    waitFor(() -> gameplay.vanished(p));
  }

  void click(PlayerMock player, int slot, ClickType type) {
    server
        .getPluginManager()
        .callEvent(
            new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                slot,
                type,
                InventoryAction.PICKUP_ALL));
    server.getScheduler().performTicks(2);
  }

  /** Slot of the first listed player's head in the work zone (not a texture button), or -1. */
  int head(org.bukkit.inventory.Inventory inventory) {
    for (int slot = 9; slot < inventory.getSize() - 9; slot++) {
      var item = inventory.getItem(slot);
      if (item != null
          && item.getType() == Material.PLAYER_HEAD
          && server.getPlayerExact(GuiRules.name(item)) != null) return slot;
    }
    return -1;
  }

  /** Slot of the "nobody to show" tile in the work zone, or -1. */
  int emptyTile(org.bukkit.inventory.Inventory inventory) {
    for (int slot = 9; slot < inventory.getSize() - 9; slot++) {
      var item = inventory.getItem(slot);
      if (item != null && item.getType() != Material.AIR && !item.getType().name().endsWith("PANE"))
        return slot;
    }
    return -1;
  }

  @Test
  void commandWithoutArgumentsOpensThePlayerMenuInTheLayoutStandard() {
    join("Target");
    server.dispatchCommand(admin, "vanish");
    server.getScheduler().performTicks(2);
    var top = admin.getOpenInventory().getTopInventory();
    assertInstanceOf(GuiLayout.class, top.getHolder());
    assertTrue(java.util.Set.of(27, 36, 45, 54).contains(top.getSize()), "size " + top.getSize());
    assertEquals(java.util.List.of(), GuiRules.violations(top, false));
    assertEquals(java.util.List.of(), GuiRules.unresolvedText(top));
    assertTrue(head(top) >= 0, "the other player must be listed");
    int listed = 0;
    for (int slot = 9; slot < top.getSize() - 9; slot++) {
      var item = top.getItem(slot);
      if (item != null
          && item.getType() == Material.PLAYER_HEAD
          && server.getPlayerExact(GuiRules.name(item)) != null) {
        listed++;
        assertEquals("Target", GuiRules.name(item), "the viewer is not listed");
        assertNull(
            ((org.bukkit.inventory.meta.SkullMeta) item.getItemMeta()).getPlayerProfile(),
            "an untextured list entry must not trigger an account lookup");
      }
    }
    assertEquals(1, listed);
  }

  @Test
  void commandWithoutTheMenuPermissionStillToggles() throws Exception {
    var staff = server.addPlayer("Plain");
    staff.addAttachment(plugin, "loveessential.vanish", true);
    server.dispatchCommand(staff, "vanish");
    waitFor(() -> gameplay.vanished(staff));
    var top = staff.getOpenInventory().getTopInventory();
    assertTrue(top == null || !(top.getHolder() instanceof GuiLayout), "no menu without .gui");
  }

  @Test
  void leftClickHidesTheViewerAndJumpsToThePlayer() throws Exception {
    var target = join("Target");
    target.teleport(new org.bukkit.Location(server.getWorld("world"), 120, 70, -40));
    server.dispatchCommand(admin, "vanish");
    server.getScheduler().performTicks(2);
    int slot = head(admin.getOpenInventory().getTopInventory());
    click(admin, slot, ClickType.LEFT);
    waitFor(() -> gameplay.vanished(admin));
    server.getScheduler().performTicks(5);
    assertEquals(1, jumps.size());
    assertEquals(120, jumps.get(0).getBlockX());
    assertEquals(-40, jumps.get(0).getBlockZ());
    assertFalse(target.canSee(admin), "the viewer is hidden before the jump");
  }

  @Test
  void jumpKeepsTheViewerVisibleWhenAutoVanishIsOff() throws Exception {
    plugin.getConfig().set("vanish.gui.auto-vanish", false);
    join("Target").teleport(new org.bukkit.Location(server.getWorld("world"), 9, 70, 9));
    server.dispatchCommand(admin, "vanish");
    server.getScheduler().performTicks(2);
    click(admin, head(admin.getOpenInventory().getTopInventory()), ClickType.LEFT);
    server.getScheduler().performTicks(5);
    assertFalse(gameplay.vanished(admin));
    assertEquals(1, jumps.size());
    assertEquals(9, jumps.get(0).getBlockX());
  }

  @Test
  void filterChangesWhatIsListed() {
    var far = join("Far");
    far.teleport(new org.bukkit.Location(server.getWorld("world"), 5000, 70, 5000));
    server.dispatchCommand(admin, "vanish");
    server.getScheduler().performTicks(2);
    var all = admin.getOpenInventory().getTopInventory();
    assertTrue(head(all) >= 0);
    click(admin, 4, ClickType.LEFT); // all -> world
    click(admin, 4, ClickType.LEFT); // world -> near
    var near = admin.getOpenInventory().getTopInventory();
    assertEquals(-1, head(near), "a player 5000 blocks away is outside the near radius");
    int empty = emptyTile(near);
    assertTrue(empty >= 0, "an empty list shows the empty tile");
    assertNotEquals(Material.AIR, near.getItem(empty).getType());
  }

  @Test
  void mobsDoNotTargetAHiddenPlayer() throws Exception {
    hide(admin);
    var zombie = admin.getWorld().spawnEntity(admin.getLocation(), EntityType.ZOMBIE);
    var event =
        new EntityTargetLivingEntityEvent(
            zombie, admin, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
    var visible = server.addPlayer("Visible");
    var other =
        new EntityTargetLivingEntityEvent(
            zombie, visible, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
    server.getPluginManager().callEvent(other);
    assertFalse(other.isCancelled(), "ordinary players are still targeted");
  }

  @Test
  void hiddenPlayersDoNotPickUpItems() throws Exception {
    hide(admin);
    var item = admin.getWorld().dropItem(admin.getLocation(), new ItemStack(Material.DIRT));
    var event = new EntityPickupItemEvent(admin, item, 0);
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
  }

  @Test
  void hiddenPlayersDoNotTriggerPressurePlates() throws Exception {
    hide(admin);
    var block = admin.getWorld().getBlockAt(0, 64, 0);
    var event =
        new PlayerInteractEvent(admin, Action.PHYSICAL, null, block, BlockFace.SELF);
    server.getPluginManager().callEvent(event);
    assertTrue(event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY || event.isCancelled());
  }

  @Test
  void hidingSetsAndShowingClearsEveryFlag() throws Exception {
    hide(admin);
    assertTrue(admin.isSilent());
    assertFalse(admin.isCollidable());
    assertFalse(admin.getAffectsSpawning());
    assertTrue(admin.getAllowFlight(), "fly option lets a hidden survival player fly");
    assertTrue(admin.hasMetadata("vanished"));

    server.dispatchCommand(admin, "vanish off");
    waitFor(() -> !gameplay.vanished(admin));
    assertFalse(admin.isSilent());
    assertTrue(admin.isCollidable());
    assertTrue(admin.getAffectsSpawning());
    assertFalse(admin.getAllowFlight(), "flight granted by vanish is taken back");
    assertFalse(admin.hasMetadata("vanished"));
  }

  @Test
  void vanishKeepsTheOwnGameMode() throws Exception {
    admin.setGameMode(org.bukkit.GameMode.SURVIVAL);
    hide(admin);
    assertEquals(org.bukkit.GameMode.SURVIVAL, admin.getGameMode());
  }

  @Test
  void hiddenPlayerIsNotSuggestedToThoseWhoCannotSeeThem() throws Exception {
    var plain = server.addPlayer("Plain");
    hide(admin);
    var suggestions = server.getCommandMap().tabComplete(plain, "le profile ");
    assertTrue(suggestions == null || !suggestions.contains("Staff"), String.valueOf(suggestions));
    var seer = server.addPlayer("Seer");
    seer.addAttachment(plugin, "loveessential.vanish.see", true);
    gameplay.applyVanish();
    // The loading visibility lease is intentionally stronger than vanish-see permission.
    var profilesField = LoveEssentialPlugin.class.getDeclaredField("profiles");
    profilesField.setAccessible(true);
    var profiles = (dev.lovelace.loveessential.module.PlayerProfiles) profilesField.get(plugin);
    waitFor(() -> seer.canSee(admin) && profiles.privacy().loaded(admin.getUniqueId()));
    var seen = server.getCommandMap().tabComplete(seer, "le profile ");
    assertTrue(seen != null && seen.contains("Staff"), String.valueOf(seen));
  }
}
