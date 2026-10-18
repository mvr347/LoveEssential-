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

  <T> T internal(String field, Class<T> type) throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField(field);
    f.setAccessible(true);
    return type.cast(f.get(plugin));
  }

  void messageReady(PlayerMock... players) throws Exception {
    var messages = internal("messages", dev.lovelace.loveessential.module.PrivateMessages.class);
    waitFor(
        () -> {
          try {
            for (var p : players) messages.preferences(p);
            return true;
          } catch (IllegalArgumentException e) {
            return false;
          }
        });
    plugin.getConfig().set("messages.cooldown-ms", 0);
    for (var p : players) drain(p);
  }

  String drain(PlayerMock player) {
    var out = new StringBuilder();
    String next;
    while ((next = player.nextMessage()) != null) out.append(next).append('\n');
    return out.toString();
  }

  @Test
  void privateMessagesReplyAndEscapePlayerText() throws Exception {
    var alice = server.addPlayer("Alice");
    var bob = server.addPlayer("Bob");
    messageReady(alice, bob);
    server.dispatchCommand(alice, "le tell Bob <red>привет</red>");
    assertTrue(
        drain(bob).contains("<red>привет</red>"),
        "message text must not become MiniMessage markup");
    server.dispatchCommand(bob, "le r ответ");
    assertTrue(drain(alice).contains("ответ"));
    assertTrue(suggestions(alice, "loveessential", "msg", "B").contains("Bob"));
    assertFalse(suggestions(alice, "loveessential", "").contains("invsee"));
  }

  @Test
  void ignoreAndTogglePersistAndPreventDelivery() throws Exception {
    var alice = server.addPlayer("Alice");
    var bob = server.addPlayer("Bob");
    messageReady(alice, bob);
    var messages = internal("messages", dev.lovelace.loveessential.module.PrivateMessages.class);
    server.dispatchCommand(bob, "le ignore Alice");
    waitFor(() -> messages.preferences(bob).ignored().containsKey(alice.getUniqueId().toString()));
    var store = internal("store", dev.lovelace.loveessential.storage.SqliteStore.class);
    var prefs =
        await(
                store.get(
                    "message-preferences",
                    bob.getUniqueId().toString(),
                    dev.lovelace.loveessential.module.PrivateMessages.Preferences.class))
            .orElseThrow()
            .value();
    assertEquals("Alice", prefs.ignored().get(alice.getUniqueId().toString()));
    drain(bob);
    server.dispatchCommand(alice, "le msg Bob blocked-message");
    assertFalse(drain(bob).contains("blocked-message"));
    server.dispatchCommand(bob, "le ignore Alice");
    waitFor(() -> messages.preferences(bob).ignored().isEmpty());
    server.dispatchCommand(bob, "le msgtoggle");
    waitFor(() -> !messages.preferences(bob).enabled());
    drain(bob);
    server.dispatchCommand(alice, "le msg Bob muted-message");
    assertFalse(drain(bob).contains("muted-message"));
    assertFalse(
        await(
                store.get(
                    "message-preferences",
                    bob.getUniqueId().toString(),
                    dev.lovelace.loveessential.module.PrivateMessages.Preferences.class))
            .orElseThrow()
            .value()
            .enabled());
    bob.disconnect();
    bob.reconnect();
    messageReady(bob);
    assertFalse(messages.preferences(bob).enabled());
  }

  @Test
  void moderationCancellationDoesNotCreateReplyAndHiddenPlayersStayHidden() throws Exception {
    var alice = server.addPlayer("Alice");
    var bob = server.addPlayer("Bob");
    messageReady(alice, bob);
    var listener = new org.bukkit.event.Listener() {};
    server
        .getPluginManager()
        .registerEvent(
            PrivateMessageEvent.class,
            listener,
            org.bukkit.event.EventPriority.NORMAL,
            (l, e) -> ((PrivateMessageEvent) e).setCancelled(true),
            plugin);
    server.dispatchCommand(alice, "le msg Bob rejected");
    assertFalse(drain(bob).contains("rejected"));
    server.dispatchCommand(bob, "le reply test");
    assertTrue(drain(bob).contains("Нет доступного собеседника"));
    org.bukkit.event.HandlerList.unregisterAll(listener);
    alice.hidePlayer(plugin, bob);
    assertFalse(suggestions(alice, "loveessential", "msg", "").contains("Bob"));
    server.dispatchCommand(alice, "le msg Bob invisible");
    assertFalse(drain(bob).contains("invisible"));
    await(plugin.module("messages", false));
    server.dispatchCommand(alice, "le msg Bob disabled");
    assertFalse(drain(bob).contains("disabled"));
  }

  @Test
  void messagePreferencesGuiUsesTheSamePersistentSettings() throws Exception {
    var p = server.addPlayer("Alice");
    messageReady(p);
    var messages = internal("messages", dev.lovelace.loveessential.module.PrivateMessages.class);
    server.dispatchCommand(p, "le ignore");
    assertLayout(p);
    click(p, 4);
    waitFor(() -> !messages.preferences(p).enabled());
    assertLayout(p);
  }

  @Test
  void inventoryReadOnlyAndEditingMutateOnlyTheAuthoritativeSlot() throws Exception {
    var admin = admin();
    var target = server.addPlayer("Target");
    waitFor(() -> admin.canSee(target));
    target
        .getInventory()
        .setItem(9, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND, 12));
    server.dispatchCommand(admin, "le invsee Target");
    assertEquals(54, admin.getOpenInventory().getTopInventory().getSize());
    click(admin, 0);
    assertEquals(12, target.getInventory().getItem(9).getAmount());
    assertTrue(admin.getItemOnCursor().getType().isAir());
    click(admin, 50);
    click(admin, 0);
    assertNull(target.getInventory().getItem(9));
    assertEquals(12, admin.getItemOnCursor().getAmount());
    click(admin, 1);
    assertEquals(12, target.getInventory().getItem(10).getAmount());
    assertTrue(admin.getItemOnCursor().getType().isAir());
    admin.closeInventory();
    assertNull(target.getInventory().getItem(9));
    assertEquals(12, target.getInventory().getItem(10).getAmount());
  }

  @Test
  void staleInventoryClickCannotOverwriteTargetAndUnsafeClicksAreCancelled() throws Exception {
    var admin = admin();
    var target = server.addPlayer("Target");
    waitFor(() -> admin.canSee(target));
    target
        .getInventory()
        .setItem(9, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND, 12));
    server.dispatchCommand(admin, "le invsee Target edit");
    target
        .getInventory()
        .setItem(9, new org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_INGOT, 3));
    click(admin, 0);
    assertEquals(org.bukkit.Material.GOLD_INGOT, target.getInventory().getItem(9).getType());
    assertTrue(admin.getItemOnCursor().getType().isAir());
    for (var type :
        java.util.List.of(
            org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
            org.bukkit.event.inventory.ClickType.NUMBER_KEY,
            org.bukkit.event.inventory.ClickType.DOUBLE_CLICK,
            org.bukkit.event.inventory.ClickType.DROP,
            org.bukkit.event.inventory.ClickType.MIDDLE)) {
      var event =
          new org.bukkit.event.inventory.InventoryClickEvent(
              admin.getOpenInventory(),
              org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
              0,
              type,
              org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY);
      server.getPluginManager().callEvent(event);
      assertTrue(event.isCancelled());
    }
    var drag =
        new org.bukkit.event.inventory.InventoryDragEvent(
            admin.getOpenInventory(),
            null,
            new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND),
            false,
            java.util.Map.of(0, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND)));
    server.getPluginManager().callEvent(drag);
    assertTrue(drag.isCancelled());
    assertEquals(3, target.getInventory().getItem(9).getAmount());
    target.disconnect();
    assertTrue(
        admin.getOpenInventory().getTopInventory() == null
            || admin.getOpenInventory().getTopInventory().getSize() != 54);
  }

  @Test
  void inventoryPermissionIsRecheckedAndModuleDisableClosesView() throws Exception {
    var p = server.addPlayer("Viewer");
    var target = server.addPlayer("Target");
    waitFor(() -> p.canSee(target));
    var permissions = p.addAttachment(plugin, "loveessential.invsee", true);
    server.dispatchCommand(p, "le invsee Target edit");
    assertTrue(
        p.getOpenInventory().getTopInventory() == null
            || p.getOpenInventory().getTopInventory().getSize() != 54);
    server.dispatchCommand(p, "le invsee Target");
    assertEquals(54, p.getOpenInventory().getTopInventory().getSize());
    click(p, 50); // no edit permission
    target
        .getInventory()
        .setItem(9, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND));
    server.getScheduler().performTicks(2);
    click(p, 0);
    assertNotNull(target.getInventory().getItem(9));
    p.removeAttachment(permissions);
    server.getScheduler().performOneTick();
    assertTrue(
        p.getOpenInventory().getTopInventory() == null
            || p.getOpenInventory().getTopInventory().getSize() != 54);
    var op = admin();
    server.dispatchCommand(op, "le invsee Target");
    await(plugin.module("inventory", false));
    server.getScheduler().performOneTick();
    assertTrue(
        op.getOpenInventory().getTopInventory() == null
            || op.getOpenInventory().getTopInventory().getSize() != 54);
  }

  @Test
  void profileHasCurrentSkinRichPlaceholdersAndProtectedLocationCard() throws Exception {
    var viewer = server.addPlayer("Viewer");
    var target = server.addPlayer("Target");
    waitFor(() -> viewer.canSee(target));
    var skin = target.getPlayerProfile();
    var textures = skin.getTextures();
    textures.setSkin(java.net.URI.create("https://textures.minecraft.net/texture/abcdef").toURL());
    skin.setTextures(textures);
    target.setPlayerProfile(skin);
    target.setLevel(42);
    target.setStatistic(org.bukkit.Statistic.DEATHS, 7);
    server.dispatchCommand(viewer, "le userinfo Target");
    assertLayout(viewer);
    var skull =
        (org.bukkit.inventory.meta.SkullMeta)
            viewer.getOpenInventory().getTopInventory().getItem(0).getItemMeta();
    assertEquals(textures.getSkin(), skull.getPlayerProfile().getTextures().getSkin());
    var profiles = internal("profiles", dev.lovelace.loveessential.module.PlayerProfiles.class);
    var values = profiles.values(target.getUniqueId());
    assertEquals("42", values.get("level"));
    assertEquals("7", values.get("deaths"));
    assertEquals("Нет", values.get("afk_state"));
    assertEquals(target.getUniqueId().toString(), values.get("uuid"));
    assertEquals(textures.getSkin().toExternalForm(), values.get("skin_url"));
    String lore =
        java.util.Arrays.stream(viewer.getOpenInventory().getTopInventory().getContents())
            .filter(java.util.Objects::nonNull)
            .map(item -> String.valueOf(item.getItemMeta().getLore()))
            .collect(java.util.stream.Collectors.joining());
    assertFalse(lore.contains("Координаты:"));
    assertTrue(lore.contains("42"));
    assertFalse(lore.contains("<health>"));
    viewer.setOp(true);
    server.dispatchCommand(viewer, "le profile Target");
    lore =
        java.util.Arrays.stream(viewer.getOpenInventory().getTopInventory().getContents())
            .filter(java.util.Objects::nonNull)
            .map(item -> String.valueOf(item.getItemMeta().getLore()))
            .collect(java.util.stream.Collectors.joining());
    assertTrue(lore.contains("Координаты:"));
    target.disconnect();
    var store = internal("store", dev.lovelace.loveessential.storage.SqliteStore.class);
    waitFor(
        () ->
            awaitUnchecked(store.profile(target.getUniqueId(), "loveessential.player-info"))
                .isPresent());
    assertTrue(
        await(store.profile(target.getUniqueId(), "loveessential.player-info"))
            .orElseThrow()
            .contains("abcdef"));
  }

  <T> T awaitUnchecked(CompletableFuture<T> future) {
    try {
      return future.get(1, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void ownPlaceholdersRenderInCardsAndPapiNumericValuesWorkOffThread() throws Exception {
    var target = server.addPlayer("Target");
    target.setLevel(23);
    server.dispatchCommand(target, "le profile");
    var placeholders = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    var lang = internal("lang", dev.lovelace.loveessential.lang.Lang.class);
    lang.putAll(java.util.Map.of("players.test", "<level>|<papi:loveessential_level>|<uuid>"));
    String text =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(placeholders.render("players.test", target, target));
    assertEquals("23|23|" + target.getUniqueId(), text);
    var expansion =
        new dev.lovelace.loveessential.tab.LoveExpansion(
            placeholders,
            lang,
            internal("love", dev.lovelace.loveessential.integration.LoveBridge.class),
            p -> false,
            p -> false);
    assertEquals(
        "23",
        CompletableFuture.supplyAsync(() -> expansion.onRequest(target, "level"))
            .get(1, TimeUnit.SECONDS));
    assertEquals("survival", expansion.onRequest(target, "gamemode"));
    assertEquals("false", expansion.onRequest(target, "afk"));
  }

  @Test
  void concurrentInventoryViewersCannotTakeTheSameStackTwice() throws Exception {
    var first = admin();
    var second = server.addPlayer("OtherAdmin");
    second.setOp(true);
    var target = server.addPlayer("Target");
    waitFor(() -> first.canSee(target) && second.canSee(target));
    target
        .getInventory()
        .setItem(9, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND, 12));
    server.dispatchCommand(first, "le invsee Target edit");
    server.dispatchCommand(second, "le invsee Target edit");
    // No tick between these clicks: the second GUI still contains its old copy.
    for (var p : java.util.List.of(first, second)) {
      var event =
          new org.bukkit.event.inventory.InventoryClickEvent(
              p.getOpenInventory(),
              org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
              0,
              org.bukkit.event.inventory.ClickType.LEFT,
              org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
      server.getPluginManager().callEvent(event);
      assertTrue(event.isCancelled());
    }
    assertEquals(12, first.getItemOnCursor().getAmount());
    assertTrue(second.getItemOnCursor().getType().isAir());
    assertNull(target.getInventory().getItem(9));
    first.closeInventory();
    second.closeInventory();
    assertNull(target.getInventory().getItem(9));
  }
}
