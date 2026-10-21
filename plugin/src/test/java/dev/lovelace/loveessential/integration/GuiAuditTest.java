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
  void navigationReturnsToTheActualRootAndReleasesClosedSessions() throws Exception {
    var player = admin();
    var field = LoveEssentialPlugin.class.getDeclaredField("profiles");
    field.setAccessible(true);
    var profiles = (dev.lovelace.loveessential.module.PlayerProfiles) field.get(plugin);
    waitFor(() -> profiles.privacy().loaded(player.getUniqueId()));
    for (int cycle = 0; cycle < 50; cycle++) {
      server.dispatchCommand(player, "profile");
      var root = player.getOpenInventory().getTopInventory();
      click(player, 12);
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
}
