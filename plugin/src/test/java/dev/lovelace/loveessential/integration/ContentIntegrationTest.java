package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.auth.AuthOracle;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.content.*;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ContentIntegrationTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  ContentLibrary library;
  StarterItems starter;
  SqliteStore store;
  Lang lang;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    await(plugin.module("tab", false));
    await(plugin.module("starter", false));
    library = internal("content", ContentLibrary.class);
    starter = internal("starter", StarterItems.class);
    store = internal("store", SqliteStore.class);
    lang = internal("lang", Lang.class);
  }

  @AfterEach
  void end() {
    MockBukkit.unmock();
  }

  <T> T internal(String name, Class<T> type) throws Exception {
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
    assertTrue(condition.getAsBoolean(), "async operation did not finish");
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

  void command(PlayerMock p, String cmd) {
    assertTrue(server.dispatchCommand(p, cmd));
  }

  void click(PlayerMock p, int slot, boolean right) {
    var e =
        new InventoryClickEvent(
            p.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            slot,
            right ? ClickType.RIGHT : ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(e);
    server.getScheduler().performOneTick();
    assertTrue(e.isCancelled());
  }

  ContentItems data(ItemStack stack) {
    return ContentItems.capture(stack, "content-text.tests." + UUID.randomUUID(), new HashMap<>());
  }

  @Test
  void menuEditorCopiesItemsPersistsTextsAndExecutesButtonActions() throws Exception {
    var p = admin();
    var icon = new ItemStack(Material.EMERALD, 8);
    icon.editMeta(m -> m.displayName(Component.text("Trade")));
    p.getInventory().setItemInMainHand(icon);
    command(p, "le gui create market");
    click(p, 22, false); // create button with a copy; opens its editor
    click(p, 24, false); // left actions
    click(p, 20, false); // message action (second action)
    command(p, "le input <gold>Welcome!</gold>");
    click(p, 51, false); // back to menu editor
    click(p, 50, false);
    waitFor(() -> library.menu("market").isPresent());
    assertEquals(8, p.getInventory().getItemInMainHand().getAmount());
    assertEquals(
        1,
        library
            .menu("market")
            .orElseThrow()
            .value()
            .buttons()
            .get(22)
            .icon()
            .item(lang)
            .getAmount());
    var document =
        await(store.get("custom-menu", "market", ContentLibrary.Menu.class)).orElseThrow();
    assertEquals(1, document.value().buttons().get(22).left().size());
    String text =
        java.nio.file.Files.readString(plugin.getDataFolder().toPath().resolve("lang.yml"));
    assertTrue(text.contains("Welcome!"));
    command(p, "le gui market");
    click(p, 22, false);
    boolean found = false;
    Component msg;
    while ((msg = p.nextComponentMessage()) != null)
      if (net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
          .serialize(msg)
          .contains("Welcome!")) found = true;
    assertTrue(found);
    assertEquals(8, p.getInventory().getItemInMainHand().getAmount());
  }

  @Test
  void nativeRecipeEditorPreservesPdcAndUnregistersWhenDisabled() throws Exception {
    var p = admin();
    command(p, "le recipes create sigil");
    var input = new ItemStack(Material.DIAMOND);
    input.editMeta(
        m ->
            m.getPersistentDataContainer()
                .set(new NamespacedKey(plugin, "special"), PersistentDataType.BYTE, (byte) 1));
    p.getInventory().setItemInMainHand(input);
    click(p, 19, false);
    p.getInventory().setItemInMainHand(new ItemStack(Material.EMERALD, 3));
    click(p, 24, false);
    click(p, 50, false);
    waitFor(() -> library.craft("sigil").isPresent());
    var key = library.key("sigil");
    var recipe = assertInstanceOf(ShapedRecipe.class, Bukkit.getRecipe(key));
    assertEquals(3, recipe.getResult().getAmount());
    var choice = assertInstanceOf(RecipeChoice.ExactChoice.class, recipe.getChoiceMap().get('a'));
    assertTrue(choice.test(input));
    assertFalse(choice.test(new ItemStack(Material.DIAMOND)));
    assertEquals(
        3,
        await(store.get("custom-craft", "sigil", ContentLibrary.Craft.class))
            .orElseThrow()
            .value()
            .result()
            .item(lang)
            .getAmount());
    await(plugin.module("crafting", false));
    assertNull(Bukkit.getRecipe(key));
    await(plugin.module("crafting", true));
    assertNotNull(Bukkit.getRecipe(key));
    assertTrue(
        plugin
            .getCommand("loveessential")
            .tabComplete(p, "le", new String[] {"recipes", "edit", ""})
            .contains("sigil"));
  }

  @Test
  void permissionAndConditionsGateMenuAndRecheckOnClick() throws Exception {
    var p = admin();
    var texts = new HashMap<String, String>();
    texts.put("content-text.tests.title", "Test");
    texts.put("content-text.tests.message", "SECRET");
    var icon = data(new ItemStack(Material.PAPER));
    var button =
        new ContentLibrary.Button(
            icon,
            new Condition("permission", Map.of("value", "test.button"), List.of()),
            List.of(new Action("message", Map.of("key", "content-text.tests.message"))),
            List.of());
    await(
        library.save(
            new ContentLibrary.Menu(
                "secret", "content-text.tests.title", "test.open", Map.of(22, button)),
            0,
            "test",
            texts));
    var user = server.addPlayer("Visitor");
    command(user, "gui secret");
    assertTrue(
        user.getOpenInventory().getTopInventory() == null
            || user.getOpenInventory().getTopInventory().getSize() != 54);
    var permission = user.addAttachment(plugin, "test.open", true);
    command(user, "gui secret");
    assertNull(user.getOpenInventory().getTopInventory().getItem(22));
    permission.setPermission("test.button", true);
    command(user, "gui secret");
    assertNotNull(user.getOpenInventory().getTopInventory().getItem(22));
    permission.setPermission("test.button", false);
    click(user, 22, false);
    Component msg;
    while ((msg = user.nextComponentMessage()) != null)
      assertFalse(
          net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(msg)
              .contains("SECRET"));
  }

  @Test
  void editorRejectsVisitorsAndNativeRecipeRequiresAllFields() throws Exception {
    var p = server.addPlayer("Visitor");
    command(p, "gui create denied");
    assertTrue(library.menu("denied").isEmpty());
    p.setOp(true);
    command(p, "recipes create incomplete");
    click(p, 50, false);
    assertTrue(library.craft("incomplete").isEmpty());
    assertEquals(54, p.getOpenInventory().getTopInventory().getSize());
    var drag =
        new InventoryDragEvent(
            p.getOpenInventory(),
            new ItemStack(Material.DIRT),
            new ItemStack(Material.DIRT),
            false,
            Map.of(22, new ItemStack(Material.DIRT)));
    server.getPluginManager().callEvent(drag);
    assertTrue(drag.isCancelled());
  }

  public static class SavedPlayer extends PlayerMock {
    boolean played;
    int saves;

    SavedPlayer(ServerMock server, String name) {
      super(server, name);
    }

    @Override
    public boolean hasPlayedBefore() {
      return played;
    }

    @Override
    public void saveData() {
      played = true;
      saves++;
    }
  }

  SavedPlayer newcomer(String name) {
    var p = new SavedPlayer(server, name);
    server.addPlayer(p);
    return p;
  }

  @Test
  void starterWaitsForAuthAndUsesFirstSessionEligibilityAfterPlayerSave() throws Exception {
    var auth = MockBukkit.createMockPlugin("LoveAuth");
    var authenticated = new AtomicBoolean();
    server
        .getServicesManager()
        .register(AuthOracle.class, id -> authenticated.get(), auth, ServicePriority.Normal);
    await(plugin.module("starter", true));
    var p = newcomer("Newcomer");
    waitFor(
        () ->
            awaitUnchecked(
                    store.get(
                        "starter-delivery",
                        p.getUniqueId().toString(),
                        StarterItems.Delivery.class))
                .isPresent());
    assertEquals(0, p.getInventory().all(Material.IRON_SWORD).size());
    p.saveData();
    authenticated.set(true);
    starter.claim(p, true);
    waitFor(() -> starter.received(p));
    assertEquals(1, p.getInventory().all(Material.IRON_SWORD).size());
    assertEquals(16, p.getInventory().getItem(1).getAmount());
    waitFor(
        () ->
            awaitUnchecked(
                    store.get(
                        "starter-delivery",
                        p.getUniqueId().toString(),
                        StarterItems.Delivery.class))
                .map(d -> d.value().state().equals("delivered"))
                .orElse(false));
    starter.claim(p, true);
    server.getScheduler().performTicks(5);
    assertEquals(1, p.getInventory().all(Material.IRON_SWORD).size());
  }

  <T> T awaitUnchecked(CompletableFuture<T> f) {
    try {
      return f.get(1, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void fullInventoryDefersEntireStarterAndLegacyReceiptPreventsAnotherGrant() throws Exception {
    await(plugin.module("starter", true));
    var p = new SavedPlayer(server, "Full");
    for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
    server.addPlayer(p);
    waitFor(
        () ->
            awaitUnchecked(
                    store.get(
                        "starter-delivery",
                        p.getUniqueId().toString(),
                        StarterItems.Delivery.class))
                .map(d -> d.value().state().equals("pending"))
                .orElse(false));
    assertFalse(starter.received(p));
    assertEquals(0, p.getInventory().all(Material.IRON_SWORD).size());
    p.getInventory().clear(0);
    p.getInventory().clear(1);
    starter.claim(p, true);
    waitFor(() -> starter.received(p));
    var old = new SavedPlayer(server, "Legacy");
    old.getPersistentDataContainer()
        .set(
            Objects.requireNonNull(NamespacedKey.fromString("lovetweaks:first_join_received")),
            PersistentDataType.BYTE,
            (byte) 1);
    server.addPlayer(old);
    starter.claim(old, true);
    server.getScheduler().performTicks(10);
    assertEquals(0, old.getInventory().all(Material.IRON_SWORD).size());
  }

  @Test
  void interruptedPendingDeliveryReconcilesSavedReceiptWithoutGranting() throws Exception {
    var p = newcomer("Recovery");
    var item = data(new ItemStack(Material.DIAMOND));
    await(
        store.save(
            "starter-delivery",
            p.getUniqueId().toString(),
            0,
            new StarterItems.Delivery("pending", List.of(item)),
            "test"));
    p.getPersistentDataContainer()
        .set(new NamespacedKey(plugin, "starter_received"), PersistentDataType.BYTE, (byte) 1);
    await(plugin.module("starter", true));
    starter.claim(p, true);
    waitFor(
        () ->
            awaitUnchecked(
                    store.get(
                        "starter-delivery",
                        p.getUniqueId().toString(),
                        StarterItems.Delivery.class))
                .map(d -> d.value().state().equals("delivered"))
                .orElse(false));
    assertEquals(0, p.getInventory().all(Material.DIAMOND).size());
  }

  @Test
  void tweaksConfigurationMigratesOnlyOnceAndPreservesScrollEntry() throws Exception {
    var tweaks = MockBukkit.createMockPlugin("LoveTweaks");
    var file = tweaks.getDataFolder().toPath().resolve("config.yml");
    java.nio.file.Files.createDirectories(file.getParent());
    java.nio.file.Files.writeString(
        file,
        "first-join:\n"
            + "  enabled: true\n"
            + "  items:\n"
            + "  - material: PAPER\n"
            + "    teleport-scroll: true\n"
            + "    amount: 2\n"
            + "  - material: IRON_SWORD\n"
            + "    amount: 1\n"
            + "    display-name: '&aСтарый меч'\n"
            + "    lore: ['&7Из LoveTweaks']\n");
    await(store.delete("starter-kit", "default", starter.kit().revision(), "test"));
    await(starter.load());
    assertEquals(2, starter.kit().value().items().size());
    assertTrue(starter.kit().value().items().getFirst().teleportScroll());
    assertEquals(2, starter.kit().value().items().getFirst().amount());
    var sword = starter.kit().value().items().get(1).item().item(lang);
    assertEquals(
        "Старый меч",
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(sword.getItemMeta().displayName()));
    java.nio.file.Files.writeString(file, "first-join:\n  enabled: false\n  items: []\n");
    await(starter.load());
    assertTrue(starter.kit().value().enabled());
    assertEquals(2, starter.kit().value().items().size());
    assertThrows(
        IllegalArgumentException.class,
        () -> starter.preview(starter.kit().value().items().getFirst()));
  }

  @Test
  void failedPlayerSaveRollsBackItemsAndRetriesPendingDelivery() throws Exception {
    class FailingPlayer extends SavedPlayer {
      boolean fail = true;

      FailingPlayer() {
        super(ContentIntegrationTest.this.server, "DiskFailure");
      }

      @Override
      public void saveData() {
        if (fail) throw new IllegalStateException("disk");
        super.saveData();
      }
    }
    await(plugin.module("starter", true));
    var p = new FailingPlayer();
    server.addPlayer(p);
    waitFor(
        () ->
            awaitUnchecked(
                    store.get(
                        "starter-delivery",
                        p.getUniqueId().toString(),
                        StarterItems.Delivery.class))
                .map(d -> d.value().state().equals("pending"))
                .orElse(false));
    server.getScheduler().performTicks(2);
    assertFalse(starter.received(p));
    assertTrue(p.getInventory().isEmpty());
    p.fail = false;
    starter.claim(p, true);
    waitFor(() -> starter.received(p));
    assertEquals(1, p.getInventory().all(Material.IRON_SWORD).size());
  }

  @Test
  void legacyBridgeTransfersOnlyFirstJoinListenerAndRestoresOnClose() throws Exception {
    var tweaks = MockBukkit.createMockPlugin("LoveTweaks");
    var old = new me.lovelace.loveTweaks.listeners.FirstJoinItemsListener();
    server.getPluginManager().registerEvents(old, tweaks);
    var bridge = new TweaksStarterBridge(plugin);
    try {
      bridge.takeOwnership();
      server.addPlayer("DuringOwnership");
      assertEquals(0, old.joins);
    } finally {
      bridge.close();
    }
    server.addPlayer("AfterOwnership");
    assertEquals(1, old.joins);
  }

  @Test
  void starterEditorRejectsKitThatCannotFitEvenEmptyInventory() throws Exception {
    var tooLarge =
        new StarterItems.Kit(
            true,
            "test",
            List.of(new StarterItems.Entry(data(new ItemStack(Material.IRON_SWORD)), false, 37)));
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> starter.save(tooLarge, starter.kit().revision(), "test", Map.of()));
    assertEquals("content.starter-too-large", e.getMessage());
  }

  @Test
  void packingSimulationDoesNotMutateInventoryAndRespectsStackLimits() {
    var before = new ItemStack[] {new ItemStack(Material.STONE, 63), null};
    var after = StarterItems.fit(before, List.of(new ItemStack(Material.STONE, 2)));
    assertNotNull(after);
    assertEquals(63, before[0].getAmount());
    assertNull(before[1]);
    assertEquals(64, after[0].getAmount());
    assertEquals(1, after[1].getAmount());
    assertNull(
        StarterItems.fit(
            before,
            List.of(new ItemStack(Material.DIAMOND, 64), new ItemStack(Material.EMERALD, 1))));
    assertEquals(63, before[0].getAmount());
  }
}
