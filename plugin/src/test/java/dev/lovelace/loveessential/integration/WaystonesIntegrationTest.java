package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.combat.CombatState;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.Position;
import dev.lovelace.loveessential.module.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.travel.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class WaystonesIntegrationTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  SqliteStore store;
  Waystones travel;
  World world;
  PlayerMock player;
  AtomicLong balance;
  AtomicInteger charges;
  AtomicBoolean combat;
  CompletableFuture<Chunk> chunk;
  boolean success;

  /** Only Billboard storage is missing from MockBukkit; real geometry/entity APIs remain in use. */
  static final class DisplayWorld extends org.mockbukkit.mockbukkit.world.WorldMock {
    private final ServerMock runtime;

    DisplayWorld(ServerMock runtime) {
      super(new org.bukkit.WorldCreator("world"));
      this.runtime = runtime;
    }

    @Override
    public <T extends org.bukkit.entity.Entity> T spawn(
        Location location,
        Class<T> type,
        java.util.function.Consumer<? super T> setup,
        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason,
        boolean randomize,
        boolean event) {
      if (type != org.bukkit.entity.TextDisplay.class)
        return super.spawn(location, type, setup, reason, randomize, event);
      var text =
          new org.mockbukkit.mockbukkit.entity.TextDisplayMock(runtime, UUID.randomUUID()) {
            private org.bukkit.entity.Display.Billboard billboard =
                org.bukkit.entity.Display.Billboard.FIXED;

            @Override
            public void setBillboard(org.bukkit.entity.Display.Billboard value) {
              billboard = value;
            }

            @Override
            public org.bukkit.entity.Display.Billboard getBillboard() {
              return billboard;
            }
          };
      text.setLocation(location);
      text.setSpawnReason(reason);
      T entity = type.cast(text);
      if (setup != null) setup.accept(entity);
      runtime.registerEntity(text);
      return entity;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    world = new DisplayWorld(server);
    server.addWorld((org.mockbukkit.mockbukkit.world.WorldMock) world);
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("waystones.render.enabled", false);
    plugin.getConfig().set("npc.max-active", 0);
    for (String id : List.of("tab", "scoreboard", "starter", "rtp"))
      await(plugin.module(id, false));
    var f = LoveEssentialPlugin.class.getDeclaredField("store");
    f.setAccessible(true);
    store = (SqliteStore) f.get(plugin);
    f = LoveEssentialPlugin.class.getDeclaredField("gameplay");
    f.setAccessible(true);
    travel = ((Gameplay) f.get(plugin)).waystones();
    combat = new AtomicBoolean();
    var state =
        (CombatState)
            Proxy.newProxyInstance(
                CombatState.class.getClassLoader(),
                new Class[] {CombatState.class},
                (o, m, a) ->
                    switch (m.getName()) {
                      case "inCombat" -> combat.get();
                      case "combatEndsAt", "combatSource" -> Optional.empty();
                      default -> null;
                    });
    server.getServicesManager().register(CombatState.class, state, plugin, ServicePriority.Highest);
    balance = new AtomicLong(100);
    charges = new AtomicInteger();
    var money =
        (LoveEconomy)
            Proxy.newProxyInstance(
                LoveEconomy.class.getClassLoader(),
                new Class[] {LoveEconomy.class},
                (o, m, a) ->
                    switch (m.getName()) {
                      case "currencyName" -> "монет";
                      case "has" -> balance.get() >= (long) a[1];
                      case "charge" -> {
                        long amount = (long) a[1];
                        if (balance.get() < amount) yield false;
                        balance.addAndGet(-amount);
                        charges.incrementAndGet();
                        yield true;
                      }
                      case "give" -> {
                        balance.addAndGet((long) a[1]);
                        yield null;
                      }
                      case "balance" -> balance.get();
                      default -> throw new UnsupportedOperationException(m.getName());
                    });
    server.getServicesManager().register(LoveEconomy.class, money, plugin, ServicePriority.Highest);
    success = true;
    f = Waystones.class.getDeclaredField("platform");
    f.setAccessible(true);
    f.set(
        travel,
        new TeleportPlatform() {
          public CompletableFuture<Chunk> loadChunk(World w, int x, int z, boolean generate) {
            assertFalse(generate);
            return chunk != null ? chunk : CompletableFuture.completedFuture(w.getChunkAt(x, z));
          }

          public boolean standable(Location at) {
            return true;
          }

          public CompletableFuture<Boolean> move(Player p, Location at) {
            if (success) p.teleport(at);
            return CompletableFuture.completedFuture(success);
          }
        });
    player = server.addPlayer("Traveler");
    player.teleport(new Location(world, .5, 64, .5));
    waitFor(
        () -> {
          try {
            var field = Waystones.class.getDeclaredField("loading");
            field.setAccessible(true);
            return !((Set<?>) field.get(travel)).contains(player.getUniqueId());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    define(20, 0, "");
  }

  void define(long cost, int seconds, String permission) throws Exception {
    var at = Position.of(new Location(world, .5, 64, .5, 90, 0));
    var to = Position.of(new Location(world, 20.5, 64, .5, 180, 0));
    var r = new Waystone.Route("2", cost, seconds, 30, permission);
    var existing = await(store.get("waystone", "1", Waystone.class));
    await(
        store.save(
            "waystone",
            "1",
            existing.map(SqliteStore.Document::revision).orElse(0L),
            new Waystone("1", "Спавн", at, at, "wood", "", List.of(r)),
            "test"));
    existing = await(store.get("waystone", "2", Waystone.class));
    if (existing.isEmpty())
      await(
          store.save(
              "waystone",
              "2",
              0,
              new Waystone("2", "Деревня", to, to, "rune", "", List.of()),
              "test"));
    await(travel.load());
  }

  void waitFor(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  <T> T await(CompletableFuture<T> f) throws Exception {
    waitFor(f::isDone);
    return f.get();
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  dev.lovelace.loveessential.dialog.NativeDialogs dialogs(
      AtomicReference<dev.lovelace.loveessential.dialog.NativeDialogs.Form> form) throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField("extensions");
    f.setAccessible(true);
    var ui = ((Extensions) f.get(plugin)).ui;
    ui.presenter((p, value) -> form.set(value));
    return ui;
  }

  void clickNamed(String name) {
    var top = player.getOpenInventory().getTopInventory();
    int slot = -1;
    for (int i = 0; i < top.getSize(); i++) {
      var item = top.getItem(i);
      if (item != null && dev.lovelace.loveessential.gui.GuiRules.name(item).contains(name)) {
        slot = i;
        break;
      }
    }
    assertTrue(slot >= 0, name);
    server
        .getPluginManager()
        .callEvent(
            new org.bukkit.event.inventory.InventoryClickEvent(
                player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                slot,
                org.bukkit.event.inventory.ClickType.LEFT,
                org.bukkit.event.inventory.InventoryAction.PICKUP_ALL));
  }

  @Test
  void renderSettingsCanBeEditedForAllStonesAndSurviveCatalogRemoval() throws Exception {
    player.setOp(true);
    var form = new AtomicReference<dev.lovelace.loveessential.dialog.NativeDialogs.Form>();
    var ui = dialogs(form);
    server.dispatchCommand(player, "le waystone models");
    clickNamed("Деревянный");
    assertTrue(ui.respond(player, form.get().token(), "render", Map.of()));
    assertTrue(form.get().fields().size() <= 12);
    assertTrue(
        ui.respond(
            player,
            form.get().token(),
            "save",
            Map.of(
                "height",
                "3,5",
                "scale",
                "1.5",
                "center",
                "0.25 -0.5",
                "gap",
                "0.4",
                "text-scale",
                "0.5")));
    await(store.profile(player.getUniqueId(), "render.barrier", "true"));
    String key = WaystoneRender.key("wood", "");
    var saved = await(store.get("waystone-render", key, WaystoneRender.class)).orElseThrow();
    assertEquals(3.5, saved.value().height());
    assertEquals(1.5, saved.value().scale());
    assertEquals(.5, saved.value().textScale());
    var model = await(store.get("waystone-model", "wood", Waystones.Model.class)).orElseThrow();
    await(store.delete("waystone-model", model.id(), model.revision(), "test"));
    await(travel.load());
    assertEquals(
        saved.value(),
        await(store.get("waystone-render", key, WaystoneRender.class)).orElseThrow().value());
    assertEquals(
        "wood",
        travel.all().stream().filter(n -> n.id().equals("1")).findFirst().orElseThrow().style());
  }

  <T extends org.bukkit.entity.Entity> T stoneEntity(Class<T> type) {
    var key = new org.bukkit.NamespacedKey(plugin, "waystone");
    return world.getEntitiesByClass(type).stream()
        .filter(
            e ->
                "1"
                    .equals(
                        e.getPersistentDataContainer()
                            .get(key, org.bukkit.persistence.PersistentDataType.STRING)))
        .min(java.util.Comparator.comparingDouble(e -> e.getLocation().getY()))
        .orElseThrow();
  }

  @Test
  void renderedLabelAndInteractionUseTheSameRotatedCenterAndScaledHeight() throws Exception {
    var profile = new WaystoneRender(1, .75, -1, 1.5, 3, 2, .25, .5, .25, .5, true, "NONE");
    await(store.save("waystone-render", WaystoneRender.key("wood", ""), 0, profile, "test"));
    await(travel.load());
    world.loadChunk(0, 0);
    plugin.getConfig().set("waystones.render.enabled", true);
    server.getScheduler().performTicks(20);
    var text = stoneEntity(org.bukkit.entity.TextDisplay.class);
    var box = stoneEntity(org.bukkit.entity.Interaction.class);
    var stone = travel.all().stream().filter(n -> n.id().equals("1")).findFirst().orElseThrow();
    Location expected = profile.label(stone.anchor().location());
    assertEquals(expected.getX(), text.getLocation().getX(), 1e-6);
    assertEquals(expected.getY(), text.getLocation().getY(), 1e-6);
    assertEquals(expected.getZ(), text.getLocation().getZ(), 1e-6);
    assertEquals(text.getLocation().getX(), box.getLocation().getX(), 1e-6);
    assertEquals(text.getLocation().getZ(), box.getLocation().getZ(), 1e-6);
    var model = stoneEntity(org.bukkit.entity.BlockDisplay.class);
    assertEquals(model.getLocation().getY(), box.getLocation().getY(), 1e-6);
    assertNotEquals(stone.anchor().y(), box.getLocation().getY());
    assertEquals(3, box.getInteractionWidth(), 1e-6);
    assertEquals(4.5, box.getInteractionHeight(), 1e-6);
    assertEquals(.5, text.getTransformation().getScale().x(), 1e-6);
  }

  @Test
  void hidingTheLabelKeepsTheWaystoneClickable() throws Exception {
    var profile = new WaystoneRender(0, 0, 0, 1, 2.1, 1.4, 0, 0, .25, .65, false, "NONE");
    await(store.save("waystone-render", WaystoneRender.key("wood", ""), 0, profile, "test"));
    await(travel.load());
    world.loadChunk(0, 0);
    plugin.getConfig().set("waystones.render.enabled", true);
    server.getScheduler().performTicks(20);
    assertNotNull(stoneEntity(org.bukkit.entity.Interaction.class));
    var key = new org.bukkit.NamespacedKey(plugin, "waystone");
    assertTrue(
        world.getEntitiesByClass(org.bukkit.entity.TextDisplay.class).stream()
            .noneMatch(
                e ->
                    "1"
                        .equals(
                            e.getPersistentDataContainer()
                                .get(key, org.bukkit.persistence.PersistentDataType.STRING))));
  }

  @Test
  void sharedCatalogIncludesTheFourVoidcoreModelsAndPersistsEdits() throws Exception {
    assertEquals(7, travel.availableModels().size());
    for (int i = 1; i <= 4; i++) {
      String item = "voidcore:waystone_" + i;
      assertTrue(travel.availableModels().stream().anyMatch(m -> m.item().equals(item)));
    }
    player.setOp(true);
    var form = new AtomicReference<dev.lovelace.loveessential.dialog.NativeDialogs.Form>();
    var ui = dialogs(form);
    server.dispatchCommand(player, "le waystone edit");
    assertTrue(
        dev.lovelace.loveessential.gui.GuiRules.name(
                player
                    .getOpenInventory()
                    .getTopInventory()
                    .getItem(player.getOpenInventory().getTopInventory().getSize() - 3))
            .contains("каталог"));
    server.dispatchCommand(player, "le waystone models");
    assertTrue(
        dev.lovelace.loveessential.gui.GuiRules.violations(
                player.getOpenInventory().getTopInventory(), false)
            .isEmpty());
    clickNamed("Добавить модель");
    assertTrue(
        ui.respond(
            player,
            form.get().token(),
            "save",
            Map.of("name", "Общий деревянный", "style", "Деревянный", "item", "")));
    waitFor(() -> travel.availableModels().size() == 8);
    await(travel.load());
    assertTrue(
        travel.availableModels().stream().anyMatch(m -> m.name().equals("Общий деревянный")));
    player.teleport(new Location(world, 40, 64, 0));
    await(store.nextSequence("waystone", 999));
    await(store.nextSequence("waystone", 999));
    server.dispatchCommand(player, "le waystone create");
    assertTrue(
        form.get().fields().stream()
            .filter(f -> f.id().equals("style"))
            .findFirst()
            .orElseThrow()
            .choices()
            .contains("Общий деревянный"));
    assertTrue(
        ui.respond(
            player,
            form.get().token(),
            "create",
            Map.of(
                "name", "Тест", "style", "Общий деревянный", "direction", "Север", "model", "")));
    waitFor(() -> travel.all().size() == 3);
    assertEquals(
        "wood",
        travel.all().stream()
            .filter(n -> n.name().equals("Тест"))
            .findFirst()
            .orElseThrow()
            .style());
  }

  @Test
  void aLateCatalogSaveDoesNotReopenAClosedMenu() throws Exception {
    player.setOp(true);
    var form = new AtomicReference<dev.lovelace.loveessential.dialog.NativeDialogs.Form>();
    var ui = dialogs(form);
    server.dispatchCommand(player, "le waystone models");
    clickNamed("Добавить модель");
    var field = SqliteStore.class.getDeclaredField("writer");
    field.setAccessible(true);
    var writer = (java.util.concurrent.ThreadPoolExecutor) field.get(store);
    var blocked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    writer.execute(
        () -> {
          blocked.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(blocked.await(5, TimeUnit.SECONDS));
    try {
      assertTrue(
          ui.respond(
              player,
              form.get().token(),
              "save",
              Map.of("name", "Отложенная", "style", "Деревянный", "item", "")));
      player.closeInventory();
    } finally {
      release.countDown();
    }
    waitFor(() -> travel.availableModels().stream().anyMatch(m -> m.name().equals("Отложенная")));
    var top = player.getOpenInventory().getTopInventory();
    assertTrue(
        top == null || !(top.getHolder() instanceof dev.lovelace.loveessential.gui.GuiLayout));
  }

  @Test
  void removedPresetDoesNotReturnOnReloadOrChangeExistingStones() throws Exception {
    var doc = await(store.get("waystone-model", "wood", Waystones.Model.class)).orElseThrow();
    await(store.delete("waystone-model", doc.id(), doc.revision(), "test"));
    await(travel.load());
    assertFalse(travel.availableModels().stream().anyMatch(m -> m.name().equals("Деревянный")));
    assertEquals(
        "wood",
        travel.all().stream().filter(n -> n.id().equals("1")).findFirst().orElseThrow().style());
    await(travel.load());
    assertEquals(6, travel.availableModels().size());
  }

  @Test
  void visibleNumberIsReusedAndCommandsResolveItWithoutRetargetingOldRoutes() throws Exception {
    var old = await(store.get("waystone", "1", Waystone.class)).orElseThrow();
    await(store.delete("waystone", "1", old.revision(), "test"));
    var at = Position.of(new Location(world, 40, 64, 0));
    await(
        store.save(
            "waystone",
            "1001",
            0,
            new Waystone("1001", "Новый", at, at, "wood", "", List.of()),
            "test"));
    await(travel.load());
    assertEquals(1, travel.number("1001"));
    assertEquals(2, travel.number("2"));
    player.setOp(true);
    assertTrue(server.dispatchCommand(player, "le waystone edit 1"));
    assertTrue(
        dev.lovelace.loveessential.gui.GuiRules.name(
                player.getOpenInventory().getTopInventory().getItem(11))
            .contains("Оформление"));
    assertEquals(List.of("1", "2"), travel.suggestions(player, new String[] {"edit", ""}));
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "2", "1"));
  }

  @Test
  void paidArrivalChargesOnceAndPersistsCooldown() throws Exception {
    travel.begin(player, "1", "2");
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    server.getScheduler().performTicks(2);
    assertEquals(20.5, player.getX(), .01);
    assertEquals(80, balance.get());
    assertEquals(1, charges.get());
    var pending = new AtomicReference<CompletableFuture<Optional<String>>>();
    waitFor(
        () -> {
          var query = pending.get();
          if (query == null) {
            pending.set(store.profile(player.getUniqueId(), "waystones-cooldown"));
            return false;
          }
          if (!query.isDone()) return false;
          var value = query.join();
          if (value.isPresent()) return Long.parseLong(value.get()) > System.currentTimeMillis();
          pending.set(null);
          return false;
        });
    player.teleport(new Location(world, .5, 64, .5));
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
  }

  @Test
  void failedMoveRefundsAndDoesNotStartCooldown() throws Exception {
    success = false;
    travel.begin(player, "1", "2");
    server.getScheduler().performTicks(2);
    assertEquals(100, balance.get());
    assertEquals(1, charges.get());
    assertTrue(await(store.profile(player.getUniqueId(), "waystones-cooldown")).isEmpty());
  }

  @Test
  void movementDuringChunkLoadCancelsBeforeChargeAndLateResultCannotMove() throws Exception {
    chunk = new CompletableFuture<>();
    travel.begin(player, "1", "2");
    server.getScheduler().performOneTick();
    player.teleport(new Location(world, 2, 64, 0));
    server.getScheduler().performOneTick();
    chunk.complete(world.getChunkAt(1, 0));
    server.getScheduler().performTicks(2);
    assertEquals(0, charges.get());
    assertEquals(2, player.getX(), .01);
  }

  @Test
  void insufficientFundsCombatPermissionAndRemoteCommandsAreRejected() throws Exception {
    balance.set(0);
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    balance.set(100);
    combat.set(true);
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    combat.set(false);
    define(0, 0, "private.route");
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    player.teleport(new Location(world, 100, 64, 100));
    assertThrows(IllegalArgumentException.class, () -> travel.command(player, new String[] {}));
    assertThrows(
        IllegalArgumentException.class, () -> travel.command(player, new String[] {"edit"}));
    assertEquals(0, charges.get());
  }

  @Test
  void disablingModuleRejectsPendingCompletion() throws Exception {
    chunk = new CompletableFuture<>();
    travel.begin(player, "1", "2");
    server.getScheduler().performOneTick();
    await(plugin.module("waystones", false));
    chunk.complete(world.getChunkAt(1, 0));
    server.getScheduler().performTicks(2);
    assertEquals(0, charges.get());
    assertEquals(.5, player.getX(), .01);
  }

  @Test
  void directedRoutesPersistAndFreeRoutesNeedNoEconomy() throws Exception {
    define(0, 0, "");
    server
        .getServicesManager()
        .unregister(LoveEconomy.class, server.getServicesManager().load(LoveEconomy.class));
    assertTrue(
        travel.all().stream()
            .filter(n -> n.id().equals("2"))
            .findFirst()
            .orElseThrow()
            .routes()
            .isEmpty());
    assertEquals(
        90,
        travel.all().stream()
            .filter(n -> n.id().equals("1"))
            .findFirst()
            .orElseThrow()
            .anchor()
            .yaw());
    travel.begin(player, "1", "2");
    server.getScheduler().performTicks(2);
    assertEquals(20.5, player.getX(), .01);
  }

  @Test
  void wizardCreatesDifferentStylesAndOrientationsAndEditorIsStaffOnly() throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField("extensions");
    f.setAccessible(true);
    var ui = ((Extensions) f.get(plugin)).ui;
    var form = new AtomicReference<dev.lovelace.loveessential.dialog.NativeDialogs.Form>();
    ui.presenter((p, value) -> form.set(value));
    player.setOp(true);
    await(store.nextSequence("waystone", 999));
    await(store.nextSequence("waystone", 999));
    player.teleport(new Location(world, 40.5, 64, .5));
    assertTrue(server.dispatchCommand(player, "le waystone create"));
    assertNotNull(form.get());
    assertTrue(
        ui.respond(
            player,
            form.get().token(),
            "create",
            Map.of(
                "name",
                "Восточный столб",
                "style",
                "Деревянный",
                "direction",
                "Восток",
                "model",
                "")));
    waitFor(() -> travel.all().size() == 3);
    var created = travel.all().stream().filter(n -> n.id().equals("3")).findFirst().orElseThrow();
    assertEquals("wood", created.style());
    assertEquals(270, created.anchor().yaw());
    var inventory = player.getOpenInventory().getTopInventory();
    assertEquals(27, inventory.getSize());
    assertTrue(dev.lovelace.loveessential.gui.GuiRules.violations(inventory, false).isEmpty());
    player.setOp(false);
    assertThrows(
        IllegalArgumentException.class, () -> travel.command(player, new String[] {"edit", "3"}));
  }

  @Test
  void failedItemsAdderModelLeavesNoPartialEntity() throws Exception {
    var at = Position.of(new Location(world, 40.5, 64, .5));
    world.getChunkAt(2, 0);
    await(
        store.save(
            "waystone",
            "3",
            0,
            new Waystone(
                "3", "Недоступная модель", at, at, "itemsadder", "missing:model", List.of()),
            "test"));
    await(travel.load());
    plugin.getConfig().set("waystones.render.enabled", true);
    server.getScheduler().performTicks(25);
    var key = new NamespacedKey(plugin, "waystone");
    assertTrue(
        world.getEntities().stream()
            .noneMatch(
                e ->
                    "3"
                        .equals(
                            e.getPersistentDataContainer()
                                .get(key, org.bukkit.persistence.PersistentDataType.STRING))));
    travel.close();
    assertTrue(
        world.getEntities().stream().noneMatch(e -> e.getPersistentDataContainer().has(key)));
  }

  @Test
  void unavailableWorldIsRejectedBeforePayment() throws Exception {
    var at = new Position(UUID.randomUUID(), 0, 64, 0, 0, 0);
    var current = await(store.get("waystone", "2", Waystone.class)).orElseThrow();
    await(
        store.save(
            "waystone",
            "2",
            current.revision(),
            new Waystone("2", "Другой мир", at, at, "stone", "", List.of()),
            "test"));
    await(travel.load());
    var error = assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    assertEquals("waystones.destination-unavailable", error.getMessage());
    assertEquals(0, charges.get());
  }

  @Test
  void realDamageCancelsBeforePaymentButCancelledDamageDoesNot() throws Exception {
    define(20, 2, "");
    travel.begin(player, "1", "2");
    var ignored =
        new org.bukkit.event.entity.EntityDamageEvent(
            player,
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL,
            org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build(),
            1);
    ignored.setCancelled(true);
    server.getPluginManager().callEvent(ignored);
    assertThrows(IllegalArgumentException.class, () -> travel.begin(player, "1", "2"));
    var hit =
        new org.bukkit.event.entity.EntityDamageEvent(
            player,
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL,
            org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build(),
            1);
    server.getPluginManager().callEvent(hit);
    server.getScheduler().performTicks(50);
    assertEquals(0, charges.get());
    assertEquals(.5, player.getX(), .01);
  }

  @Test
  void sequenceNeverReusesAReservedOrDeletedNumber() throws Exception {
    int first = await(store.nextSequence("test-waystone", 2));
    assertEquals(1, first);
    assertEquals(2, await(store.nextSequence("test-waystone", 2)));
    assertThrows(ExecutionException.class, () -> await(store.nextSequence("test-waystone", 2)));
  }
}
