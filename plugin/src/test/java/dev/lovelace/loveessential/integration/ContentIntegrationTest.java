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
  void recipesCanBeCreatedEditedAndDeletedEntirelyThroughGui() throws Exception {
    var p = admin();
    assertFalse(LoveEssentialPlugin.MODULES.contains("customgui"));
    assertNull(plugin.getCommand("customgui"));
    command(p, "le crafts");
    click(p, 50, false);
    command(p, "le input emblem");
    var ingredient = new ItemStack(Material.DIAMOND, 8);
    p.getInventory().setItemInMainHand(ingredient);
    click(p, 19, false);
    assertEquals(8, p.getInventory().getItemInMainHand().getAmount());
    var output = new ItemStack(Material.EMERALD, 3);
    output.editMeta(
        m -> {
          m.displayName(Component.text("Символ королевства"));
          m.lore(List.of(Component.text("Создан через редактор крафтов")));
        });
    p.getInventory().setItemInMainHand(output);
    click(p, 24, false);
    click(p, 50, false);
    waitFor(() -> library.craft("emblem").isPresent());
    waitFor(
        () ->
            p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getItem(22) != null);
    assertEquals(3, p.getInventory().getItemInMainHand().getAmount());
    assertEquals(
        "Символ королевства",
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(
                library
                    .craft("emblem")
                    .orElseThrow()
                    .value()
                    .result()
                    .item(lang)
                    .getItemMeta()
                    .displayName()));
    assertTrue(
        java.nio.file.Files.readString(plugin.getDataFolder().toPath().resolve("lang.yml"))
            .contains("Создан через редактор крафтов"));
    click(p, 22, true); // edit from catalog
    p.getInventory().setItemInMainHand(new ItemStack(Material.EMERALD, 5));
    click(p, 24, false);
    click(p, 1, false);
    click(p, 3, false); // shapeless, material comparison
    click(p, 19, true);
    p.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
    click(p, 19, false);
    click(p, 50, false);
    waitFor(() -> library.craft("emblem").orElseThrow().revision() > 1);
    var nativeRecipe =
        assertInstanceOf(ShapelessRecipe.class, Bukkit.getRecipe(library.key("emblem")));
    assertEquals(5, nativeRecipe.getResult().getAmount());
    assertInstanceOf(RecipeChoice.MaterialChoice.class, nativeRecipe.getChoiceList().getFirst());
    assertEquals(
        Material.STONE,
        library
            .craft("emblem")
            .orElseThrow()
            .value()
            .ingredients()
            .getFirst()
            .item(lang)
            .getType());
    command(p, "le recipes");
    click(p, 22, true);
    click(p, 33, false);
    click(p, 20, false);
    waitFor(() -> library.craft("emblem").isEmpty());
    assertNull(Bukkit.getRecipe(library.key("emblem")));
  }

  @Test
  void editorRejectsVisitorsAndNativeRecipeRequiresAllFields() throws Exception {
    var p = server.addPlayer("Visitor");
    command(p, "recipes create denied");
    assertTrue(library.craft("denied").isEmpty());
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
