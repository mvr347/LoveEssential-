package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.gui.GuiLayout;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class GuiAuditTest {
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
    var future = plugin.module("scoreboard", false);
    waitFor(future::isDone);
    future.join();
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  void waitFor(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  PlayerMock admin() {
    var player = server.addPlayer("Builder");
    player.setOp(true);
    return player;
  }

  String name(PlayerMock p, int slot) {
    var item = p.getOpenInventory().getTopInventory().getItem(slot);
    return item == null
        ? ""
        : PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
  }

  void click(PlayerMock player, int slot) throws Exception {
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

  @Test
  void directAdminEditorHasNoInventedProfileParent() throws Exception {
    var player = admin();
    server.dispatchCommand(player, "le scoreboard edit");
    var inventory = player.getOpenInventory().getTopInventory();
    assertInstanceOf(GuiLayout.class, inventory.getHolder());
    assertTrue(name(player, inventory.getSize() - 1).contains("Закрыть"));
    assertFalse(name(player, inventory.getSize() - 2).contains("Назад"));
    server.dispatchCommand(player, "le scoreboard settings");
    assertSame(inventory, player.getOpenInventory().getTopInventory());
  }

  @Test
  void queuedMainThreadCallsSettleWhenPluginStops() throws Exception {
    var main = new MainThread(plugin);
    var queued =
        CompletableFuture.supplyAsync(() -> main.call(() -> "should not run"))
            .get(2, TimeUnit.SECONDS);
    assertFalse(queued.isDone());
    MainThread.stop(plugin);
    assertTrue(queued.isCompletedExceptionally());
    assertTrue(main.call(() -> "late").isCompletedExceptionally());
  }

  @Test
  void moduleFilesHaveLiteralGuiTextAndNoPersistedLanguageReferences() throws Exception {
    var path = plugin.getDataFolder().toPath().resolve("modules/profile/gui.yml");
    var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(path.toFile());
    assertTrue(yaml.getString("menus.profile-menu-title.title").contains("Профиль"));
    assertFalse(yaml.contains("texts"));
    assertFalse(yaml.contains("buttons"));
    assertTrue(
        yaml.getKeys(true).stream()
            .noneMatch(key -> key.endsWith("-key") || key.endsWith("-keys")));
  }

  @Test
  void schemaSixMovesCustomScreenDataAndKeepsOldSettingPathsCompatible() throws Exception {
    var root = plugin.getDataFolder().toPath();
    var guiFile = root.resolve("modules/profile/gui.yml");
    var moduleFile = root.resolve("modules/profile/profile.yml");
    var gui = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(guiFile.toFile());
    var module =
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(moduleFile.toFile());
    String location = "menus.profile-menu-statistics.categories";
    Object categories = dev.lovelace.loveessential.config.ModuleConfigs.snapshot(gui.get(location));
    dev.lovelace.loveessential.config.ModuleConfigs.setValue(
        module, "statistics.categories", categories);
    module.set(
        "statistics.categories.world.details.visits.lore",
        java.util.List.of("<gold>♜ <playtime_hours> | %custom_value%"));
    gui.set(location, null);
    gui.set("messages.profile-stats.next", "<green>ЛКМ — мой следующий раздел");
    gui.set("menus.profile-stats-details-title.size", 54);
    gui.set("menus.profile-menu-statistics.labels.profile-stats.next", null);
    gui.save(guiFile.toFile());
    module.save(moduleFile.toFile());
    String original = java.nio.file.Files.readString(guiFile);
    var coreFile = root.resolve("config.yml");
    var core = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(coreFile.toFile());
    core.set("config-version", 5);
    core.save(coreFile.toFile());
    var reload = plugin.reloadSettings();
    waitFor(reload::isDone);
    reload.join();
    assertEquals(
        original,
        java.nio.file.Files.readString(root.resolve("modules/profile/gui.yml.pre-gui6.bak")));
    gui.load(guiFile.toFile());
    module.load(moduleFile.toFile());
    assertFalse(module.contains("statistics.categories"));
    assertFalse(gui.contains("messages.profile-stats.next"));
    assertFalse(gui.contains("menus.profile-stats-details-title"));
    assertEquals(
        java.util.List.of("<gold>♜ <playtime_hours> | %custom_value%"),
        gui.getStringList(location + ".world.details.visits.lore"));
    assertEquals(
        "<green>ЛКМ — мой следующий раздел",
        gui.getString("menus.profile-menu-statistics.labels.profile-stats.next"));
    var edit = plugin.editSetting("profile", "statistics.categories.world.enabled", true, false);
    waitFor(edit::isDone);
    edit.join();
    gui.load(guiFile.toFile());
    assertFalse(gui.getBoolean(location + ".world.enabled"));
    assertFalse(plugin.getConfig().getBoolean("profile.statistics.categories.world.enabled"));
    module.load(moduleFile.toFile());
    assertFalse(module.contains("statistics.categories"));
  }

  @Test
  void malformedScreenMigrationDoesNotWriteAnyActiveConfig() throws Exception {
    var root = plugin.getDataFolder().toPath();
    var file = root.resolve("modules/profile/gui.yml");
    String broken = "menus: [invalid YAML";
    java.nio.file.Files.writeString(file, broken);
    var board = root.resolve("modules/scoreboard/scoreboard.yml");
    String before = java.nio.file.Files.readString(board);
    var reload = plugin.reloadSettings();
    waitFor(reload::isDone);
    assertTrue(reload.isCompletedExceptionally());
    assertEquals(broken, java.nio.file.Files.readString(file));
    assertEquals(before, java.nio.file.Files.readString(board));
  }

  @Test
  void navigationReturnsToTheActualRootAndReleasesClosedSessions() throws Exception {
    var player = admin();
    var field = LoveEssentialPlugin.class.getDeclaredField("profiles");
    field.setAccessible(true);
    var profiles = (dev.lovelace.loveessential.module.PlayerProfiles) field.get(plugin);
    waitFor(() -> profiles.privacy().loaded(player.getUniqueId()));
    for (int cycle = 0; cycle < 50; cycle++) {
      server.dispatchCommand(player, "profile");
      var root = player.getOpenInventory().getTopInventory();
      int settingsSlot = -1;
      for (int slot = 0; slot < root.getSize(); slot++)
        if (root.getItem(slot) != null && name(player, slot).contains("Настройки")) settingsSlot = slot;
      assertTrue(settingsSlot >= 0, "settings button on the profile");
      click(player, settingsSlot);
      var settings = player.getOpenInventory().getTopInventory();
      assertNotSame(root, settings);
      assertTrue(name(player, settings.getSize() - 2).contains("Назад"));
      click(player, settings.getSize() - 2);
      assertSame(root, player.getOpenInventory().getTopInventory());
      click(player, root.getSize() - 1);
    }
    var menus = profiles.getClass().getDeclaredField("menus");
    menus.setAccessible(true);
    assertTrue(((java.util.Map<?, ?>) menus.get(profiles)).isEmpty());
    var navField = LoveEssentialPlugin.class.getDeclaredField("navigation");
    navField.setAccessible(true);
    var navigation = navField.get(plugin);
    var routes = navigation.getClass().getDeclaredField("routes");
    routes.setAccessible(true);
    var cache = (com.github.benmanes.caffeine.cache.Cache<?, ?>) routes.get(navigation);
    cache.cleanUp();
    assertEquals(0, cache.estimatedSize());
  }

  @Test
  void nestedSettingsWithTheSameScreenTypeHaveSeparateParents() throws Exception {
    var player = admin();
    var field = LoveEssentialPlugin.class.getDeclaredField("menus");
    field.setAccessible(true);
    var menus = (dev.lovelace.loveessential.gui.ControlMenus) field.get(plugin);
    menus.settings(player, "scoreboard", "", 0);
    var root = player.getOpenInventory().getTopInventory();
    menus.settings(player, "scoreboard", "overrides", 0);
    var section = player.getOpenInventory().getTopInventory();
    assertNotSame(root, section);
    assertTrue(name(player, section.getSize() - 2).contains("Назад"));
    click(player, section.getSize() - 2);
    assertSame(root, player.getOpenInventory().getTopInventory());
    assertFalse(name(player, root.getSize() - 2).contains("Назад"));
  }

  @Test
  void snapshotWritesKeepOnlyTheLatestPendingValueAndWaitForIt() throws Exception {
    var profileField = LoveEssentialPlugin.class.getDeclaredField("profiles");
    profileField.setAccessible(true);
    var profiles = (dev.lovelace.loveessential.module.PlayerProfiles) profileField.get(plugin);
    var storeField = LoveEssentialPlugin.class.getDeclaredField("store");
    storeField.setAccessible(true);
    var store = (dev.lovelace.loveessential.storage.SqliteStore) storeField.get(plugin);
    var writerField = store.getClass().getDeclaredField("writer");
    writerField.setAccessible(true);
    var writer = (ThreadPoolExecutor) writerField.get(store);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    writer.execute(
        () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException ignored) {
          }
        });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    var id = java.util.UUID.randomUUID();
    var persist =
        profiles.getClass().getDeclaredMethod("persist", java.util.UUID.class, java.util.Map.class);
    persist.setAccessible(true);
    try {
      for (int version = 1; version <= 20; version++)
        persist.invoke(profiles, id, java.util.Map.of("version", "" + version));
      var writes = profiles.getClass().getDeclaredField("snapshotWrites");
      writes.setAccessible(true);
      var pending = (java.util.Map<?, ?>) writes.get(profiles);
      assertEquals(1, pending.size());
      assertEquals(1, store.queuedWrites());
      var finished = (CompletableFuture<?>) pending.get(id);
      release.countDown();
      waitFor(finished::isDone);
      finished.join();
      assertTrue(
          store
              .profile(id, "loveessential.player-info")
              .get(2, TimeUnit.SECONDS)
              .orElseThrow()
              .contains("20"));
      assertTrue(pending.isEmpty());
    } finally {
      release.countDown();
    }
  }

  @Test
  void inventoryInspectorReturnsToThePlayerListThatOpenedIt() throws Exception {
    var player = admin();
    var subject = server.addPlayer("Target");
    var field = LoveEssentialPlugin.class.getDeclaredField("profiles");
    field.setAccessible(true);
    var profiles = (dev.lovelace.loveessential.module.PlayerProfiles) field.get(plugin);
    waitFor(
        () ->
            profiles.privacy().loaded(player.getUniqueId())
                && profiles.privacy().loaded(subject.getUniqueId()));
    server.getScheduler().performTicks(2);
    profiles.players(player, "invsee", 0);
    var list = player.getOpenInventory().getTopInventory();
    int target = -1;
    for (int slot = 0; slot < list.getSize(); slot++)
      if (name(player, slot).contains("Target")) {
        target = slot;
        break;
      }
    assertTrue(
        target >= 0,
        java.util.stream.IntStream.range(0, list.getSize())
            .mapToObj(slot -> name(player, slot))
            .toList()
            .toString());
    click(player, target);
    assertEquals(54, player.getOpenInventory().getTopInventory().getSize());
    assertTrue(name(player, 52).contains("Назад"));
    click(player, 52);
    assertSame(list, player.getOpenInventory().getTopInventory());
  }

  @Test
  void publicNpcNumbersDoNotShadowExistingNumericApiIds() throws Exception {
    var player = admin();
    var api =
        server.getServicesManager().load(dev.lovelace.loveessential.api.LoveEssentialService.class);
    var original =
        dev.lovelace.loveessential.wizard.NpcDraft.create(
                player.getUniqueId(),
                dev.lovelace.loveessential.api.Position.of(player.getLocation()))
            .npc();
    for (String id : java.util.List.of("guard", "1")) {
      var npc =
          new dev.lovelace.loveessential.api.NpcDefinition(
              id,
              0,
              original.owner(),
              original.nameKey(),
              original.entityType(),
              original.spawn(),
              original.settings(),
              original.visibility(),
              original.access(),
              original.route(),
              original.dialog(),
              original.actions(),
              true);
      var saved = api.save(npc, 0);
      waitFor(saved::isDone);
      saved.join();
    }
    assertEquals("1", api.npc("1").orElseThrow().id());
    server.dispatchCommand(player, "npc delete 1 confirm");
    waitFor(() -> api.npc("guard").isEmpty());
    assertEquals("1", api.npc("1").orElseThrow().id());
  }

  @Test
  void sharedHeadTemplatesNeverShareMutableNamesOrLore() {
    String texture = "8672085ff5df0ee852136e2d41a769229291d666b28e9fb04d6c92f158e42bb";
    var first =
        dev.lovelace.loveessential.gui.MenuStyle.head(
            texture, net.kyori.adventure.text.Component.text("Первый"), java.util.List.of());
    first.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("Изменён")));
    var second =
        dev.lovelace.loveessential.gui.MenuStyle.head(
            texture, net.kyori.adventure.text.Component.text("Второй"), java.util.List.of());
    assertEquals(
        "Второй",
        PlainTextComponentSerializer.plainText().serialize(second.getItemMeta().displayName()));
    assertNotSame(first, second);
  }

  @Test
  void playerCannotOpenAdministrativeModuleRoot() {
    var player = server.addPlayer("Visitor");
    server.dispatchCommand(player, "le");
    assertTrue(
        player.getOpenInventory().getTopInventory() == null
            || !(player.getOpenInventory().getTopInventory().getHolder() instanceof GuiLayout));
  }

  @Test
  void emptyNpcListCreatesInTheWorkAreaAndDirectOpenHasNoBack() {
    var player = admin();
    server.dispatchCommand(player, "le npc");
    var inventory = player.getOpenInventory().getTopInventory();
    assertEquals(27, inventory.getSize());
    assertTrue(name(player, 13).contains("Создать"));
    assertFalse(name(player, 25).contains("Назад"));
    assertTrue(name(player, 26).contains("Закрыть"));
    assertTrue(
        inventory.getItem(13).getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(line -> line.contains("автоматически")));
  }

  @Test
  void aForeignMenuReturnsToTheSameProfileWithItsHubContext() throws Exception {
    var player = admin();
    var f = LoveEssentialPlugin.class.getDeclaredField("profiles");
    f.setAccessible(true);
    var profiles = (dev.lovelace.loveessential.module.PlayerProfiles) f.get(plugin);
    waitFor(() -> profiles.privacy().loaded(player.getUniqueId()));
    server.dispatchCommand(player, "profile back");
    var profile = player.getOpenInventory().getTopInventory();
    assertTrue(name(player, profile.getSize() - 2).contains("Назад"));
    dev.lovelace.loveessential.gui.MenuStyle.external(player);
    player.openInventory(
        org.bukkit.Bukkit.createInventory(
            null, 27, net.kyori.adventure.text.Component.text("Внешнее меню")));
    server.dispatchCommand(player, "profile");
    assertSame(profile, player.getOpenInventory().getTopInventory());
    assertTrue(name(player, profile.getSize() - 2).contains("Назад"));
    // A fresh explicit command has no foreign return context and opens a root.
    server.dispatchCommand(player, "profile");
    var direct = player.getOpenInventory().getTopInventory();
    assertNotSame(profile, direct);
    assertFalse(name(player, direct.getSize() - 2).contains("Назад"));
  }

  @Test
  void backUsesTheInventoryRebuiltWhenTheParentResumes() {
    var p = admin();
    class Resizing implements GuiLayout {
      org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(this, 27);
      final java.util.Map<Integer, Runnable> actions = new java.util.HashMap<>();
      final String id;

      Resizing(String id) {
        this.id = id;
      }

      public String guiId() {
        return id;
      }

      public org.bukkit.inventory.Inventory getInventory() {
        return inv;
      }

      public void guiInventory(org.bukkit.inventory.Inventory value) {
        inv = value;
      }

      public java.util.Map<Integer, ?> guiActions() {
        return actions;
      }

      public void guiPut(int slot, Runnable action) {
        actions.put(slot, action);
      }

      public void guiResume() {
        inv = org.bukkit.Bukkit.createInventory(this, 36);
      }
    }
    var parent = new Resizing("gui.test-parent");
    var child = new Resizing("gui.test-child");
    dev.lovelace.loveessential.gui.MenuStyle.open(p, parent.inv);
    dev.lovelace.loveessential.gui.MenuStyle.open(p, child.inv);
    dev.lovelace.loveessential.gui.MenuStyle.back(p);
    assertSame(parent.inv, p.getOpenInventory().getTopInventory());
    assertEquals(36, parent.inv.getSize());
  }
}
