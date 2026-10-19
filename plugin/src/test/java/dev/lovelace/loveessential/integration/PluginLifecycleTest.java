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
    await(plugin.module("scoreboard", false));
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
    server.dispatchCommand(p, "le npc");
    assertLayout(p);
    click(p, 50); // create prompt
    server.dispatchCommand(p, "le input menu_guard");
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getSize() == 54);
    assertLayout(p);
    click(p, dev.lovelace.loveessential.gui.MenuStyle.slot(2, 4)); // entity picker
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
            for (var p : players) {
              messages.preferences(p);
              for (var other : players) if (!p.canSee(other)) return false;
            }
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

  dev.lovelace.loveessential.module.Nicknames nickReady(PlayerMock p) throws Exception {
    var nick = internal("nicknames", dev.lovelace.loveessential.module.Nicknames.class);
    waitFor(
        () -> {
          try {
            nick.settings(p);
            return true;
          } catch (IllegalArgumentException e) {
            return false;
          }
        });
    return nick;
  }

  @Test
  void nicknameColorGradientAndBothResetsPersistWithoutChangingIdentity() throws Exception {
    var p = server.addPlayer("Alice");
    var nick = nickReady(p);
    server.dispatchCommand(p, "le ник Аура");
    waitFor(() -> nick.settings(p).name().equals("Аура"));
    assertEquals("Alice", p.getName());
    server.dispatchCommand(p, "le nick gradient #123456 #abcdef");
    waitFor(() -> nick.settings(p).colors().size() == 2);
    assertEquals("gradient", nick.values(p.getUniqueId()).get("nickname_style"));
    assertTrue(nick.values(p.getUniqueId()).get("nickname").contains("§x§1§2§3§4§5§6"));
    var store = internal("store", dev.lovelace.loveessential.storage.SqliteStore.class);
    assertEquals(
        "Аура",
        await(
                store.get(
                    "nickname",
                    p.getUniqueId().toString(),
                    dev.lovelace.loveessential.module.Nicknames.Settings.class))
            .orElseThrow()
            .value()
            .name());
    p.disconnect();
    p.reconnect();
    nickReady(p);
    assertEquals("Аура", nick.settings(p).name());
    assertEquals(2, nick.settings(p).colors().size());
    server.dispatchCommand(p, "le nick gradient reset");
    waitFor(() -> nick.settings(p).colors().isEmpty());
    assertEquals("Аура", nick.settings(p).name());
    server.dispatchCommand(p, "le nick color gold");
    waitFor(() -> nick.settings(p).colors().equals(java.util.List.of("#ffaa00")));
    server.dispatchCommand(p, "le nick reset");
    waitFor(() -> nick.settings(p).name().isEmpty() && nick.settings(p).colors().isEmpty());
    assertEquals("Alice", nick.values(p.getUniqueId()).get("nickname_plain"));
    assertEquals(net.kyori.adventure.text.Component.text("Alice"), p.displayName());
  }

  @Test
  void nicknameRejectsTagsInvalidColorsImpersonationAndUnauthorizedChanges() throws Exception {
    var p = server.addPlayer("Alice");
    var other = server.addPlayer("Bob");
    var nick = nickReady(p);
    nickReady(other);
    waitFor(() -> p.canSee(other));
    for (String bad :
        java.util.List.of("set <red>Alice", "set Bob", "set AA", "color #123", "gradient red")) {
      server.dispatchCommand(p, "le nick " + bad);
      assertTrue(nick.settings(p).name().isEmpty());
      assertTrue(nick.settings(p).colors().isEmpty());
    }
    server.dispatchCommand(p, "le nick player Bob set Impostor");
    assertTrue(nick.settings(other).name().isEmpty());
    var permission = p.addAttachment(plugin, "loveessential.nick.gradient", false);
    server.dispatchCommand(p, "le nick gradient red blue");
    assertTrue(nick.settings(p).colors().isEmpty());
    assertFalse(suggestions(p, "loveessential", "nick", "").contains("gradient"));
    p.removeAttachment(permission);
    var admin = admin();
    nickReady(admin);
    server.dispatchCommand(admin, "le nick player Bob set Knight");
    waitFor(() -> nick.settings(other).name().equals("Knight"));
    assertTrue(suggestions(admin, "loveessential", "nick", "player", "").contains("Bob"));
    assertTrue(
        suggestions(p, "loveessential", "nick", "color", "")
            .containsAll(java.util.List.of("gold", "reset")));
  }

  @Test
  void nicknameGuiColorPresetAndConfirmedResetWork() throws Exception {
    var p = server.addPlayer("Alice");
    var nick = nickReady(p);
    server.dispatchCommand(p, "le nick");
    assertLayout(p);
    click(p, 19);
    server.dispatchCommand(p, "le input Princess");
    waitFor(() -> nick.settings(p).name().equals("Princess"));
    assertLayout(p);
    click(p, 21);
    assertLayout(p);
    click(p, dev.lovelace.loveessential.gui.MenuStyle.slot(0, 16));
    waitFor(() -> nick.settings(p).colors().size() == 1);
    click(p, 23);
    assertLayout(p);
    click(p, dev.lovelace.loveessential.gui.MenuStyle.slot(0, 4));
    waitFor(() -> nick.settings(p).colors().size() >= 2);
    click(p, 25);
    waitFor(() -> nick.settings(p).colors().isEmpty());
    assertEquals("Princess", nick.settings(p).name());
    click(p, 31);
    assertLayout(p);
    assertEquals("Princess", nick.settings(p).name());
    click(p, 20);
    waitFor(() -> nick.settings(p).name().isEmpty());
  }

  @Test
  void nicknamePlaceholdersAndTabPreserveGradientAndRealName() throws Exception {
    var p = server.addPlayer("Alice");
    var nick = nickReady(p);
    await(nick.change(p, p, "set", "Aurora"));
    await(nick.change(p, p, "gradient", "#123456", "#abcdef"));
    var ph = internal("placeholders", dev.lovelace.loveessential.tab.Placeholders.class);
    var lang = internal("lang", dev.lovelace.loveessential.lang.Lang.class);
    lang.putAll(java.util.Map.of("nick.test", "<nickname>|<nickname_plain>|<real_name>"));
    var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    assertEquals("Aurora|Aurora|Alice", plain.serialize(ph.render("nick.test", p, p)));
    assertEquals("Aurora", plain.serialize(ph.renderName("tab.name", p, p)));
    var expansion =
        new dev.lovelace.loveessential.tab.LoveExpansion(
            ph,
            lang,
            internal("love", dev.lovelace.loveessential.integration.LoveBridge.class),
            q -> false,
            q -> false);
    String value =
        CompletableFuture.supplyAsync(() -> expansion.onRequest(p, "nickname"))
            .get(1, TimeUnit.SECONDS);
    assertTrue(value.contains("§x§1§2§3§4§5§6"));
    assertEquals("Aurora", expansion.onRequest(p, "nickname_plain"));
    assertEquals("Alice", expansion.onRequest(p, "real_name"));
    assertTrue(expansion.onRequest(p, "text_tab.name").contains("§x§1§2§3§4§5§6"));
    plugin.getConfig().set("nickname.apply-to-tab", false);
    assertEquals("Alice", plain.serialize(ph.renderName("tab.name", p, p)));
    await(plugin.module("nickname", false));
    assertEquals(net.kyori.adventure.text.Component.text("Alice"), p.displayName());
    assertEquals("Alice", expansion.onRequest(p, "nickname_plain"));
    await(plugin.module("nickname", true));
    assertEquals("Aurora", expansion.onRequest(p, "nickname_plain"));
  }

  @Test
  void messagesUseNicknameButRepliesStillTargetRealAccount() throws Exception {
    var alice = server.addPlayer("Alice");
    var bob = server.addPlayer("Bob");
    messageReady(alice, bob);
    var nick = nickReady(alice);
    nickReady(bob);
    await(nick.change(alice, alice, "set", "Aurora"));
    server.dispatchCommand(alice, "le msg Bob hello");
    String incoming = drain(bob);
    assertTrue(incoming.contains("Aurora"));
    server.dispatchCommand(bob, "le reply answer");
    assertTrue(drain(alice).contains("answer"));
    assertTrue(suggestions(bob, "loveessential", "msg", "").contains("Alice"));
    assertFalse(suggestions(bob, "loveessential", "msg", "").contains("Aurora"));
  }

  @Test
  void concurrentNicknameSavesCannotClaimSameNameAndResetUsesConfiguredDefault() throws Exception {
    var alice = server.addPlayer("Alice");
    var bob = server.addPlayer("Bob");
    var nick = nickReady(alice);
    nickReady(bob);
    var pending = nick.change(alice, alice, "set", "Aurora");
    assertThrows(IllegalArgumentException.class, () -> nick.change(bob, bob, "set", "aurora"));
    await(pending);
    assertTrue(nick.settings(bob).name().isEmpty());
    await(plugin.editSetting("nickname", "default-color", "", "#445566"));
    await(nick.change(alice, alice, "color", "red"));
    await(nick.change(alice, alice, "color", "reset"));
    assertEquals("Aurora", nick.values(alice.getUniqueId()).get("nickname_plain"));
    assertEquals(
        net.kyori.adventure.text.format.TextColor.color(0x445566), nick.display(alice).color());
    await(nick.change(alice, alice, "reset"));
    assertEquals(
        net.kyori.adventure.text.Component.text(
            "Alice", net.kyori.adventure.text.format.TextColor.color(0x445566)),
        nick.display(alice));
    await(plugin.reloadSettings());
    assertEquals("#445566", plugin.getConfig().getString("nickname.default-color"));
  }

  int itemNamed(PlayerMock p, String name) {
    var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    var inv = p.getOpenInventory().getTopInventory();
    for (int i = 0; i < inv.getSize(); i++) {
      var item = inv.getItem(i);
      if (item != null
          && item.getItemMeta().displayName() != null
          && plain.serialize(item.getItemMeta().displayName()).contains(name)) return i;
    }
    fail("Missing menu item: " + name);
    return -1;
  }

  String cancelButton(PlayerMock p) {
    net.kyori.adventure.text.Component next;
    String command = null;
    while ((next = p.nextComponentMessage()) != null) {
      if (next.clickEvent() != null
          && next.clickEvent().payload()
              instanceof net.kyori.adventure.text.event.ClickEvent.Payload.Text text
          && text.value().contains(" cancel ")) command = text.value();
    }
    assertNotNull(command, "prompt must contain a clickable cancellation button");
    return command.substring(1);
  }

  @Test
  void mainMenuContainsOnlyModuleSettingsAndToggleControls() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le");
    assertLayout(p);
    var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    var lang = internal("lang", dev.lovelace.loveessential.lang.Lang.class);
    var expected =
        LoveEssentialPlugin.MODULES.stream()
            .map(id -> plain.serialize(lang.render("gui.module-names." + id)))
            .collect(java.util.stream.Collectors.toSet());
    var found = new java.util.HashSet<String>();
    for (int page = 0; page < (LoveEssentialPlugin.MODULES.size() + 20) / 21; page++) {
      for (int slot : dev.lovelace.loveessential.gui.MenuStyle.CONTENT) {
        var item = p.getOpenInventory().getTopInventory().getItem(slot);
        if (item == null) continue;
        String name = plain.serialize(item.getItemMeta().displayName());
        assertTrue(expected.contains(name), name);
        found.add(name);
        assertTrue(item.getItemMeta().getLore().stream().anyMatch(line -> line.contains("ЛКМ")));
        assertTrue(item.getItemMeta().getLore().stream().anyMatch(line -> line.contains("ПКМ")));
      }
      if ((page + 1) * 21 < LoveEssentialPlugin.MODULES.size()) {
        click(p, 44);
        assertLayout(p);
      }
    }
    assertEquals(expected, found);
    assertEquals(
        org.bukkit.Material.GRAY_STAINED_GLASS_PANE,
        p.getOpenInventory().getTopInventory().getItem(51).getType());
    var regular = server.addPlayer("Visitor");
    server.dispatchCommand(regular, "le");
    assertTrue(
        regular.getOpenInventory().getTopInventory() == null
            || regular.getOpenInventory().getTopInventory().getSize() != 54);
  }

  @Test
  void wizardIsUniversalAndPromptsExplainFieldWithClickableSafeCancellation() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le npc create universal");
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getSize() == 54);
    assertLayout(p);
    assertFalse(suggestions(p, "loveessential", "npc", "").contains("template"));
    var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    for (var item : p.getOpenInventory().getTopInventory().getContents())
      if (item != null)
        assertFalse(plain.serialize(item.getItemMeta().displayName()).contains("Шаблон"));
    click(p, itemNamed(p, "Имя NPC"));
    String first = cancelButton(p);
    server.dispatchCommand(p, first);
    assertLayout(p);
    click(p, itemNamed(p, "Имя NPC"));
    String second = cancelButton(p);
    assertNotEquals(first, second);
    server.dispatchCommand(p, first); // old button must not remove the new prompt
    server.dispatchCommand(p, "le input <gold>Универсальный");
    assertLayout(p);
    server.dispatchCommand(p, "le npc publish");
    var api = Bukkit.getServicesManager().load(LoveEssentialService.class);
    waitFor(() -> api.npc("universal").isPresent());
    assertEquals(
        "<gold>Универсальный",
        internal("lang", dev.lovelace.loveessential.lang.Lang.class)
            .text(api.npc("universal").orElseThrow().nameKey()));
  }

  @Test
  void invalidWizardInputKeepsPromptAndShowsParameterDetails() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le npc create observer");
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getSize() == 54);
    server.dispatchCommand(p, "le npc page 4");
    click(p, itemNamed(p, "Радиус взгляда"));
    String messages = drain(p);
    assertTrue(messages.contains("Радиус взгляда"));
    assertTrue(messages.contains("Пример ввода"));
    assertTrue(messages.contains("1–64"));
    server.dispatchCommand(p, "le input nonsense");
    String cancel = cancelButton(p);
    server.dispatchCommand(p, cancel);
    assertLayout(p);
    click(p, itemNamed(p, "Радиус взгляда"));
    server.dispatchCommand(p, "le input 12");
    assertLayout(p);
    server.dispatchCommand(p, "le npc publish");
    var api = Bukkit.getServicesManager().load(LoveEssentialService.class);
    waitFor(() -> api.npc("observer").isPresent());
    assertEquals("12", api.npc("observer").orElseThrow().option("look-range", ""));
  }

  @Test
  void numericSettingsHaveButtonsAndContextualCancellableManualInput() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le module home");
    click(p, itemNamed(p, "Базовый лимит домов"));
    assertLayout(p);
    int before = plugin.getConfig().getInt("home.default-limit");
    click(p, itemNamed(p, "+1"));
    waitFor(() -> plugin.getConfig().getInt("home.default-limit") == before + 1);
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getSize() == 54);
    click(p, 50);
    String command = cancelButton(p);
    server.dispatchCommand(p, command);
    assertLayout(p);
    click(p, 50);
    server.dispatchCommand(p, "le input 8");
    waitFor(() -> plugin.getConfig().getInt("home.default-limit") == 8);
  }

  @Test
  void routeAndActionEditorsManageIndividualEntriesWithConfirmation() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le npc create route_editor");
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getSize() == 54);
    server.dispatchCommand(p, "le npc path add");
    server.dispatchCommand(p, "le npc path add");
    server.dispatchCommand(p, "le npc page 5");
    click(p, itemNamed(p, "Редактировать точку"));
    assertLayout(p);
    click(p, itemNamed(p, "Точка 1"));
    assertLayout(p);
    click(p, itemNamed(p, "Ожидание в точке"));
    server.dispatchCommand(p, "le input 40");
    server.dispatchCommand(p, "le npc page 5");
    click(p, itemNamed(p, "Редактировать точку"));
    click(p, itemNamed(p, "Точка 2"));
    click(p, itemNamed(p, "Удалить эту точку"));
    assertLayout(p);
    click(p, 24); // cancelling confirmation keeps the point
    click(p, itemNamed(p, "Удалить эту точку"));
    click(p, 20);
    server.dispatchCommand(
        p,
        "le npc set actions"
            + " [{\"type\":\"sound\",\"args\":{\"sound\":\"ENTITY_EXPERIENCE_ORB_PICKUP\"}},{\"type\":\"message\",\"args\":{\"key\":\"common.done\"}}]");
    server.dispatchCommand(p, "le npc page 6");
    click(p, itemNamed(p, "Действия по клику"));
    assertLayout(p);
    click(p, 4);
    assertLayout(p);
    var deleteEvent =
        new org.bukkit.event.inventory.InventoryClickEvent(
            p.getOpenInventory(),
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
            dev.lovelace.loveessential.gui.MenuStyle.slot(0, 2),
            org.bukkit.event.inventory.ClickType.RIGHT,
            org.bukkit.event.inventory.InventoryAction.PICKUP_HALF);
    server.getPluginManager().callEvent(deleteEvent);
    server.getScheduler().performOneTick();
    click(p, 20);
    server.dispatchCommand(p, "le npc publish");
    var api = Bukkit.getServicesManager().load(LoveEssentialService.class);
    waitFor(() -> api.npc("route_editor").isPresent());
    var npc = api.npc("route_editor").orElseThrow();
    assertEquals(1, npc.route().points().size());
    assertEquals(40, npc.route().points().getFirst().waitTicks());
    assertEquals(1, npc.actions().size());
    assertEquals("message", npc.actions().getFirst().type());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  @SuppressWarnings("unchecked")
  void pausedNpcStillTracksPlayerWhileMovementRemainsPaused(boolean preview) throws Exception {
    var player = admin();
    player.teleport(new org.bukkit.Location(player.getWorld(), 4, 64, 4));
    var npcs = internal("npcs", dev.lovelace.loveessential.npc.NpcService.class);
    var rotations = new java.util.concurrent.atomic.AtomicInteger();
    var uuid = java.util.UUID.randomUUID();
    org.bukkit.entity.LivingEntity entity =
        (org.bukkit.entity.LivingEntity)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.bukkit.entity.LivingEntity.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "getLocation" -> new org.bukkit.Location(player.getWorld(), 0, 64, 0);
                      case "getEyeLocation" ->
                          new org.bukkit.Location(player.getWorld(), 0, 65.6, 0);
                      case "getWorld" -> player.getWorld();
                      case "getUniqueId" -> uuid;
                      case "isValid", "isVisibleByDefault" -> true;
                      case "setRotation" -> {
                        rotations.incrementAndGet();
                        yield null;
                      }
                      case "setBodyYaw", "remove" -> null;
                      default -> method.getReturnType() == boolean.class ? false : null;
                    });
    var definition =
        new NpcDefinition(
            "observer",
            1,
            player.getUniqueId(),
            "common.done",
            "MANNEQUIN",
            Position.of(new org.bukkit.Location(player.getWorld(), 0, 64, 0)),
            java.util.Map.of("look-at", "true"),
            Condition.always(),
            Condition.always(),
            Route.empty(),
            Dialog.empty(),
            java.util.List.of(),
            true);
    Class<?> liveClass = Class.forName("dev.lovelace.loveessential.npc.NpcService$Live");
    var constructor = liveClass.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    String key = preview ? "preview:" + player.getUniqueId() : definition.id();
    var live = constructor.newInstance(key, definition, player.getUniqueId(), entity, preview);
    if (!preview) {
      var definitionsField = npcs.getClass().getDeclaredField("definitions");
      definitionsField.setAccessible(true);
      ((java.util.Map<String, NpcDefinition>) definitionsField.get(npcs))
          .put(definition.id(), definition);
    }
    var pause = liveClass.getDeclaredField("pauseUntil");
    pause.setAccessible(true);
    pause.setLong(live, Long.MAX_VALUE);
    var liveField = npcs.getClass().getDeclaredField("live");
    liveField.setAccessible(true);
    ((java.util.Map<String, Object>) liveField.get(npcs)).put(key, live);
    server.getScheduler().performTicks(4);
    assertTrue(rotations.get() > 0, "paused preview must still update its gaze");
    assertEquals(Long.MAX_VALUE, pause.getLong(live));
  }

  @Test
  void moduleToggleAndEnginePickerPersistFromGui() throws Exception {
    var p = admin();
    server.dispatchCommand(p, "le");
    int slot = itemNamed(p, "Дома");
    var toggle =
        new org.bukkit.event.inventory.InventoryClickEvent(
            p.getOpenInventory(),
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
            slot,
            org.bukkit.event.inventory.ClickType.RIGHT,
            org.bukkit.event.inventory.InventoryAction.PICKUP_HALF);
    server.getPluginManager().callEvent(toggle);
    server.getScheduler().performOneTick();
    assertTrue(toggle.isCancelled());
    waitFor(() -> !plugin.module("home"));
    server.dispatchCommand(p, "le tab");
    click(p, itemNamed(p, "Движок списка игроков"));
    assertLayout(p);
    click(p, itemNamed(p, "NEZNAMY"));
    waitFor(() -> plugin.getConfig().getString("tab.engine").equals("tab"));
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getSize() == 54);
    await(plugin.reloadSettings());
    assertFalse(plugin.module("home"));
    assertEquals("tab", plugin.getConfig().getString("tab.engine"));
  }
}
