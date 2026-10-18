package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.api.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PluginLifecycleTest {
  ServerMock server;
  LoveEssentialPlugin plugin;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    await(plugin.module("tab", false));
  }

  @AfterEach
  void teardown() {
    MockBukkit.unmock();
  }

  void waitFor(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean(), "async server operation did not finish");
  }

  <T> T await(CompletableFuture<T> future) throws Exception {
    waitFor(future::isDone);
    return future.get(1, TimeUnit.SECONDS);
  }

  PlayerMock admin() {
    var p = server.addPlayer("Builder");
    p.setOp(true);
    return p;
  }

  @Test
  void registersApiAndCreatesPersistentNpcThroughWizard() throws Exception {
    PlayerMock p = admin();
    assertTrue(server.dispatchCommand(p, "npc wizard"));
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getSize() == 54);
    server.dispatchCommand(p, "npc set id guard");
    server.dispatchCommand(p, "npc set name Страж ворот");
    server.dispatchCommand(p, "npc set type VILLAGER");
    server.dispatchCommand(p, "npc path add");
    server.dispatchCommand(p, "npc publish");
    var api = Bukkit.getServicesManager().load(LoveEssentialService.class);
    assertNotNull(api);
    waitFor(() -> api.npc("guard").isPresent());
    var n = api.npc("guard").orElseThrow();
    assertEquals("VILLAGER", n.entityType());
    assertEquals(1, n.route().points().size());
    assertTrue(n.revision() > 0);
    String text =
        java.nio.file.Files.readString(plugin.getDataFolder().toPath().resolve("lang.yml"));
    assertTrue(text.contains("Страж ворот"));
    assertTrue(
        java.nio.file.Files.exists(plugin.getDataFolder().toPath().resolve("loveessential.db")));
  }

  @Test
  void playerWithoutEditorPermissionCannotCreateNpc() throws Exception {
    var p = server.addPlayer("Visitor");
    server.dispatchCommand(p, "npc wizard");
    server.getScheduler().performTicks(2);
    assertTrue(
        p.getOpenInventory().getTopInventory() == null
            || p.getOpenInventory().getTopInventory().getSize() != 54);
    assertTrue(Bukkit.getServicesManager().load(LoveEssentialService.class).npcs().isEmpty());
  }

  @Test
  void profileRoundTripsThroughPublicApi() throws Exception {
    var api = Bukkit.getServicesManager().load(LoveEssentialService.class);
    var id = java.util.UUID.randomUUID();
    await(api.profile(id, "lovequests.chapter", "3"));
    assertEquals("3", await(api.profile(id, "lovequests.chapter")).orElseThrow());
  }

  @Test
  void disabledNpcModuleRejectsCommands() throws Exception {
    PlayerMock p = admin();
    await(plugin.module("npc", false));
    server.dispatchCommand(p, "npc wizard");
    server.getScheduler().performTicks(2);
    assertTrue(
        p.getOpenInventory().getTopInventory() == null
            || p.getOpenInventory().getTopInventory().getSize() != 54);
  }

  @Test
  void reloadAppliesConfigAndPreservesSqliteModuleOverrides() throws Exception {
    plugin.getConfig().set("modules.home", false);
    plugin.saveConfig();
    await(plugin.reloadSettings());
    assertFalse(plugin.module("home"));
    await(plugin.module("home", true));
    await(plugin.reloadSettings());
    assertTrue(plugin.module("home"));
  }

  java.util.List<String> suggestions(PlayerMock p, String command, String... args) {
    return java.util.Objects.requireNonNull(plugin.getCommand(command))
        .tabComplete(p, command, args);
  }

  void click(PlayerMock p, int slot) {
    var event =
        new org.bukkit.event.inventory.InventoryClickEvent(
            p.getOpenInventory(),
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
            slot,
            org.bukkit.event.inventory.ClickType.LEFT,
            org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    server.getScheduler().performOneTick();
    assertTrue(event.isCancelled(), "menu items must never enter the player inventory");
  }

  @Test
  void allCommandsAreDiscoverableUnderLeAndHomesRoundTrip() throws Exception {
    var p = admin();
    var names = suggestions(p, "loveessential", "");
    for (String name : plugin.getDescription().getCommands().keySet())
      if (!name.equals("loveessential")) assertTrue(names.contains(name), name);
    assertTrue(
        suggestions(p, "loveessential", "npc", "")
            .containsAll(java.util.List.of("create", "delete", "clone", "enable", "disable")));
    assertEquals(
        suggestions(p, "npc", "set", ""), suggestions(p, "loveessential", "npc", "set", ""));
    assertTrue(suggestions(p, "loveessential", "module", "").contains("tab"));
    assertTrue(
        suggestions(p, "loveessential", "npc", "set", "interaction-mode", "")
            .contains("RIGHT_CLICK"));
    server.dispatchCommand(p, "le sethome cabin");
    waitFor(() -> suggestions(p, "loveessential", "home", "").contains("cabin"));
    assertTrue(suggestions(p, "home", "").contains("cabin"));
    server.dispatchCommand(p, "le удалитьдом cabin");
    waitFor(() -> !suggestions(p, "home", "").contains("cabin"));
  }

  @Test
  void npcCanBeCreatedAndDeletedFromRootMenus() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le");
    assertLayout(p);
    click(p, 20); // NPC browser
    click(p, 50); // create prompt
    server.dispatchCommand(p, "le input menu_guard");
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getSize() == 54);
    assertLayout(p);
    click(p, dev.lovelace.loveessential.gui.MenuStyle.slot(3, 5)); // entity picker
    assertLayout(p);
    click(p, dev.lovelace.loveessential.gui.MenuStyle.slot(1, 11)); // VILLAGER
    server.dispatchCommand(p, "le npc publish");
    var api = Bukkit.getServicesManager().load(LoveEssentialService.class);
    waitFor(() -> api.npc("menu_guard").isPresent());
    assertEquals("VILLAGER", api.npc("menu_guard").orElseThrow().entityType());
    assertTrue(suggestions(p, "loveessential", "npc", "delete", "").contains("menu_guard"));
    server.dispatchCommand(p, "le npc");
    click(p, dev.lovelace.loveessential.gui.MenuStyle.slot(0, 1));
    click(p, 33); // delete -> confirmation
    assertTrue(api.npc("menu_guard").isPresent(), "opening confirmation must not delete anything");
    String target =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(
                p.getOpenInventory().getTopInventory().getItem(22).getItemMeta().displayName());
    assertEquals("menu_guard", target);
    click(p, 20);
    waitFor(() -> api.npc("menu_guard").isEmpty());
  }

  void assertLayout(PlayerMock p) {
    var inventory = p.getOpenInventory().getTopInventory();
    assertEquals(54, inventory.getSize());
    for (int slot = 0; slot < 18; slot++) assertNotNull(inventory.getItem(slot), "header " + slot);
    for (int slot = 9; slot < 18; slot++)
      assertEquals(org.bukkit.Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(slot).getType());
    for (int slot = 45; slot < 54; slot++) assertNotNull(inventory.getItem(slot), "footer " + slot);
    for (int slot : new int[] {18, 26, 27, 35})
      assertNull(inventory.getItem(slot), "content walls " + slot);
    for (int slot = 18; slot < 45; slot++)
      if (inventory.getItem(slot) != null)
        assertNotEquals(
            org.bukkit.Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(slot).getType());
  }

  @Test
  void moduleEditorPersistsOnlyToItsOwnFileAndRejectsStaleChanges() throws Exception {
    await(plugin.editSetting("home", "default-limit", 3, 7));
    assertEquals(7, plugin.getConfig().getInt("home.default-limit"));
    var folder = plugin.getDataFolder().toPath();
    var core =
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            folder.resolve("config.yml").toFile());
    assertFalse(core.contains("home"));
    assertFalse(core.contains("npc"));
    assertFalse(core.contains("tab"));
    assertEquals(
        7,
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                folder.resolve("modules/home.yml").toFile())
            .getInt("default-limit"));
    assertThrows(
        java.util.concurrent.ExecutionException.class,
        () -> await(plugin.editSetting("home", "default-limit", 3, 9)));
    assertEquals(7, plugin.getConfig().getInt("home.default-limit"));
    await(plugin.reloadSettings());
    assertEquals(7, plugin.getConfig().getInt("home.default-limit"));
  }

  @Test
  void legacyConfigMigratesWithoutLosingValuesAndKeepsBackup() throws Exception {
    var folder = plugin.getDataFolder().toPath();
    try (var files = java.nio.file.Files.list(folder.resolve("modules"))) {
      for (var path : files.toList()) java.nio.file.Files.delete(path);
    }
    String legacy =
        "config-version: 2\n"
            + "modules:\n"
            + "  tab: false\n"
            + "  traces: true\n"
            + "home:\n"
            + "  default-limit: 17\n"
            + "npc:\n"
            + "  max-active: 0\n"
            + "tab:\n"
            + "  refresh-ticks: 25\n"
            + "  external:\n"
            + "    config:\n"
            + "      custom-value: preserved\n"
            + "actions:\n"
            + "  max-economy-amount: 42\n";
    java.nio.file.Files.writeString(folder.resolve("config.yml"), legacy);
    await(plugin.reloadSettings());
    assertEquals(17, plugin.getConfig().getInt("home.default-limit"));
    assertEquals(25, plugin.getConfig().getInt("tab.refresh-ticks"));
    assertEquals("preserved", plugin.getConfig().getString("tab.external.config.custom-value"));
    assertEquals(42, plugin.getConfig().getInt("actions.max-economy-amount"));
    assertTrue(plugin.module("traces"));
    assertEquals(
        legacy, java.nio.file.Files.readString(folder.resolve("config.yml.pre-modules.bak")));
    assertFalse(
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                folder.resolve("config.yml").toFile())
            .contains("tab"));
  }

  @Test
  void malformedModuleFileDoesNotReplaceActiveConfiguration() throws Exception {
    var previous = plugin.getConfig();
    var file = plugin.getDataFolder().toPath().resolve("modules/tab.yml");
    String original = java.nio.file.Files.readString(file);
    String broken = "engine: [invalid yaml";
    java.nio.file.Files.writeString(file, broken);
    assertThrows(
        java.util.concurrent.ExecutionException.class, () -> await(plugin.reloadSettings()));
    assertSame(previous, plugin.getConfig());
    assertEquals(broken, java.nio.file.Files.readString(file));
    java.nio.file.Files.writeString(file, original);
    var npcFile = plugin.getDataFolder().toPath().resolve("modules/npc.yml");
    String wrongType = "enabled: true\npathfinding: invalid-section\n";
    java.nio.file.Files.writeString(npcFile, wrongType);
    assertThrows(
        java.util.concurrent.ExecutionException.class, () -> await(plugin.reloadSettings()));
    assertSame(previous, plugin.getConfig());
    assertEquals(wrongType, java.nio.file.Files.readString(npcFile));
  }

  @Test
  void tabAndModuleSettingsOpenMenusAndPermissionIsCheckedAgainOnClick() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le tab");
    assertLayout(p);
    p.setOp(false);
    click(p, 4); // module toggle
    assertFalse(plugin.module("tab"));
    assertFalse(suggestions(p, "loveessential", "npc", "").contains("delete"));
    server.dispatchCommand(p, "le module home");
    assertFalse(p.isOp());
  }

  @Test
  void tabBooleanCanBeEditedFromGuiAndSurvivesReload() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le tab");
    var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    int slot = -1;
    var inventory = p.getOpenInventory().getTopInventory();
    for (int i = 18; i < 45; i++) {
      var item = inventory.getItem(i);
      if (item != null
          && plain.serialize(item.getItemMeta().displayName()).equals("Сортировка включена"))
        slot = i;
    }
    assertTrue(slot >= 0, "sorting editor must be visible");
    click(p, slot);
    waitFor(() -> !plugin.getConfig().getBoolean("tab.sorting-enabled"));
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getSize() == 54);
    await(plugin.reloadSettings());
    assertFalse(plugin.getConfig().getBoolean("tab.sorting-enabled"));
    var file = plugin.getDataFolder().toPath().resolve("modules/tab.yml");
    assertFalse(
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile())
            .getBoolean("sorting-enabled"));
  }
}
