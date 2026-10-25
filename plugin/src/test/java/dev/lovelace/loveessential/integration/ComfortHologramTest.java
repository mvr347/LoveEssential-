package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.api.HologramService.*;
import dev.lovelace.loveessential.module.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ComfortHologramTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  Holograms holograms;
  SqliteStore store;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    await(plugin.module("tab", false));
    await(plugin.module("scoreboard", false));
    await(plugin.module("starter", false));
    holograms = (Holograms) Bukkit.getServicesManager().load(HologramService.class);
    var field = LoveEssentialPlugin.class.getDeclaredField("store");
    field.setAccessible(true);
    store = (SqliteStore) field.get(plugin);
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
    assertTrue(test.getAsBoolean());
  }

  <T> T await(CompletableFuture<T> f) throws Exception {
    waitFor(f::isDone);
    return f.get(1, TimeUnit.SECONDS);
  }

  PlayerMock admin() {
    var p = server.addPlayer("Builder");
    p.setOp(true);
    return p;
  }

  void command(PlayerMock p, String command) {
    assertTrue(server.dispatchCommand(p, command));
  }

  int named(PlayerMock player, String text) {
    var inventory = player.getOpenInventory().getTopInventory();
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      var item = inventory.getItem(slot);
      if (item != null
          && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(item.getItemMeta().displayName())
              .contains(text)) return slot;
    }
    fail("Missing button " + text);
    return -1;
  }

  void click(PlayerMock p, int slot, ClickType type) {
    var event =
        new InventoryClickEvent(
            p.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            slot,
            type,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
    server.getScheduler().performOneTick();
  }

  Hologram definition(String id, List<Line> lines) {
    return new Hologram(
        id,
        Position.of(server.getWorld("world").getSpawnLocation().add(0, 2, 0)),
        List.of(new Page(lines, null)),
        0,
        true,
        .3,
        48);
  }

  @Test
  void laySurvivesServerPoseResetAndSecondCommandRestoresStanding() throws Exception {
    var world =
        new org.mockbukkit.mockbukkit.world.WorldMock() {
          @Override
          public org.mockbukkit.mockbukkit.block.BlockMock createBlock(
              org.mockbukkit.mockbukkit.world.Coordinate coordinate) {
            return new org.mockbukkit.mockbukkit.block.BlockMock(
                coordinate.y < 64 ? Material.STONE : Material.AIR,
                new Location(this, coordinate.x, coordinate.y, coordinate.z)) {
              @Override
              public boolean isPassable() {
                return getType().isAir();
              }

              @Override
              public org.bukkit.util.VoxelShape getCollisionShape() {
                var boxes =
                    getType().isAir()
                        ? List.<org.bukkit.util.BoundingBox>of()
                        : List.of(new org.bukkit.util.BoundingBox(0, 0, 0, 1, 1, 1));
                return new org.bukkit.util.VoxelShape() {
                  public Collection<org.bukkit.util.BoundingBox> getBoundingBoxes() {
                    return boxes;
                  }

                  public boolean overlaps(org.bukkit.util.BoundingBox box) {
                    return boxes.stream().anyMatch(b -> b.overlaps(box));
                  }
                };
              }
            };
          }
        };
    world.setName("lay-test");
    server.addWorld(world);
    var p = server.addPlayer("Lounger");
    p.teleport(new Location(world, .5, 64, .5));
    command(p, "lay");
    assertEquals(org.bukkit.entity.Pose.SLEEPING, p.getPose());
    p.setPose(org.bukkit.entity.Pose.STANDING, false); // A server/client update must not end /lay.
    server.getScheduler().performTicks(5);
    assertEquals(org.bukkit.entity.Pose.SLEEPING, p.getPose());
    assertTrue(p.hasFixedPose());
    command(p, "лежать");
    server.getScheduler().performTicks(3);
    assertEquals(org.bukkit.entity.Pose.STANDING, p.getPose());
    assertFalse(p.hasFixedPose());
  }

  @Test
  void hatSwapsBothItemsAndRejectsStackWithoutLoss() {
    var p = admin();
    var inv = p.getInventory();
    inv.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
    inv.setItemInMainHand(new ItemStack(Material.CARVED_PUMPKIN));
    command(p, "le hat");
    assertEquals(Material.CARVED_PUMPKIN, inv.getHelmet().getType());
    assertEquals(Material.DIAMOND_HELMET, inv.getItemInMainHand().getType());
    inv.setItemInMainHand(new ItemStack(Material.STONE, 32));
    command(p, "hat");
    assertEquals(32, inv.getItemInMainHand().getAmount());
    assertEquals(Material.CARVED_PUMPKIN, inv.getHelmet().getType());
  }

  @Test
  void cursedHelmetCannotBeReplacedInSurvival() {
    var p = admin();
    var helmet = new ItemStack(Material.IRON_HELMET);
    helmet.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.BINDING_CURSE, 1);
    p.getInventory().setHelmet(helmet);
    p.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
    command(p, "hat");
    assertEquals(Material.IRON_HELMET, p.getInventory().getHelmet().getType());
    assertEquals(Material.STONE, p.getInventory().getItemInMainHand().getType());
  }

  @Test
  void homeAndSpawnArePublicButSetspawnRequiresAdminAndWorldDenialExplainsReason()
      throws Exception {
    var p = server.addPlayer("Visitor");
    p.addAttachment(plugin, "loveessential.home", false);
    p.addAttachment(plugin, "loveessential.spawn", false);
    while (p.nextComponentMessage() != null) {}
    command(p, "home");
    assertTrue(p.nextMessage().contains("У вас ещё нет дома"));
    command(p, "sethome");
    await(store.profile(p.getUniqueId(), "test.barrier", "true"));
    assertTrue(
        await(store.get("place", "home:" + p.getUniqueId() + ":default", Gameplay.Place.class))
            .isPresent());
    while (p.nextComponentMessage() != null) {}
    command(p, "spawn");
    assertFalse(p.nextMessage().contains("Недостаточно прав"));
    command(p, "setspawn");
    assertNull(p.nextMessage(), "a refusal for missing rights prints nothing");
    assertTrue(await(store.get("place", "spawn:default", Gameplay.Place.class)).isEmpty());
    plugin.getConfig().set("home.access.denied-worlds", List.of("world"));
    command(p, "home");
    String denied = p.nextMessage();
    assertTrue(denied.contains("мире") && denied.contains("world"), denied);
    assertFalse(denied.toLowerCase(Locale.ROOT).contains("недостаточно прав"));
  }

  @Test
  void sethomeSavesSinglePointWithPlainConfirmation() throws Exception {
    var p = admin();
    await(store.profile(p.getUniqueId(), "test.initial", "true"));
    server.getScheduler().performTicks(3);
    while (p.nextComponentMessage() != null) {}
    command(p, "le sethome");
    await(store.profile(p.getUniqueId(), "test.barrier", "true"));
    assertTrue(
        await(store.get("place", "home:" + p.getUniqueId() + ":default", Gameplay.Place.class))
            .isPresent());
    server.getScheduler().performTicks(3);
    var messages = new ArrayList<String>();
    Component next;
    while ((next = p.nextComponentMessage()) != null)
      messages.add(
          net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(next));
    assertEquals(1, messages.size());
    assertTrue(messages.getFirst().contains("Точка дома установлена"));
  }

  private List<String> commands(Component c) {
    var out = new ArrayList<String>();
    if (c.clickEvent() != null && c.clickEvent().payload() instanceof ClickEvent.Payload.Text text)
      out.add(text.value());
    c.children().forEach(child -> out.addAll(commands(child)));
    return out;
  }

  @Test
  void publicTpaHomesAndBackCommandsAreRemoved() {
    for (String command : List.of("homes", "tpa", "tpahere", "tpaccept", "tpdeny", "back"))
      assertNull(plugin.getCommand(command), command);
  }

  @Test
  void rideIsPublicAndCarrierCanEjectWithCommandOrHit() throws Exception {
    var rider =
        new PlayerMock(server, "Rider") {
          @Override
          public boolean hasLineOfSight(org.bukkit.entity.Entity target) {
            return true;
          }
        };
    server.addPlayer(rider);
    rider.setOp(true);
    var target = server.addPlayer("Passenger");
    rider.teleport(target.getLocation().clone().add(1, 0, 0));
    await(store.profile(rider.getUniqueId(), "test.barrier", "true"));
    await(store.profile(target.getUniqueId(), "test.barrier", "true"));
    server.getScheduler().performTicks(3);
    command(rider, "ride Passenger");
    assertTrue(target.getPassengers().contains(rider));
    command(target, "сбросить");
    assertFalse(target.getPassengers().contains(rider));
    command(rider, "ride Passenger");
    assertTrue(target.getPassengers().contains(rider));
    var hit =
        new org.bukkit.event.entity.EntityDamageByEntityEvent(
            target,
            rider,
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK)
                .withCausingEntity(target)
                .build(),
            1);
    server.getPluginManager().callEvent(hit);
    assertTrue(hit.isCancelled());
    assertFalse(target.getPassengers().contains(rider));
  }

  @Test
  void createsWithGeneratedIdAndLocalizesTextThenEditsLinesThroughGui() throws Exception {
    var p = admin();
    command(p, "le hologram create <gold>Первая строка");
    waitFor(() -> holograms.all().size() == 1);
    var old = holograms.all().iterator().next();
    String id = old.value().id();
    assertTrue(id.startsWith("holo_"));
    assertEquals(p.getY() + 2, old.value().position().y(), .001);
    var line = old.value().pages().getFirst().lines().getFirst();
    assertTrue(
        java.nio.file.Files.readString(
                plugin.getDataFolder().toPath().resolve("modules/hologram/hologram.yml"))
            .contains("Первая строка"));
    command(p, "hologram addline " + id + " Вторая строка");
    waitFor(() -> holograms.get(id).orElseThrow().revision() > old.revision());
    command(p, "hologram edit " + id);
    var before = holograms.get(id).orElseThrow();
    click(p, named(p, "Строка 2"), ClickType.SHIFT_LEFT);
    waitFor(() -> holograms.get(id).orElseThrow().revision() > before.revision());
    var edited = holograms.get(id).orElseThrow();
    assertEquals(line, edited.value().pages().getFirst().lines().get(1));
    command(p, "hologram edit " + id);
    // Page management lives in its own menu, not in the editor's footer.
    click(p, named(p, "Страницы"), ClickType.LEFT);
    click(p, named(p, "Добавить страницу"), ClickType.LEFT);
    waitFor(() -> holograms.get(id).orElseThrow().value().pages().size() == 2);
    command(p, "hologram edit " + id);
    var withPage = holograms.get(id).orElseThrow();
    click(p, 3, ClickType.LEFT);
    waitFor(() -> holograms.get(id).orElseThrow().revision() > withPage.revision());
    assertFalse(holograms.get(id).orElseThrow().value().shadow());
  }

  @Test
  void apiRejectsStaleChangesAndDefinitionsSurviveReload() throws Exception {
    var value = definition(holograms.nextId(), List.of(new Line("common.done", "")));
    var first = await(holograms.save(value, 0));
    var stale = holograms.save(value, 0);
    waitFor(stale::isDone);
    assertTrue(stale.isCompletedExceptionally());
    await(holograms.load());
    assertEquals(first, holograms.get(value.id()).orElseThrow());
    await(holograms.delete(value.id(), first.revision()));
    assertTrue(holograms.get(value.id()).isEmpty());
    var recreated = await(holograms.save(value, 0));
    assertTrue(recreated.revision() > first.revision());
    var oldDelete = holograms.delete(value.id(), first.revision());
    waitFor(oldDelete::isDone);
    assertTrue(oldDelete.isCompletedExceptionally());
  }

  @Test
  void migratesLegacyHologramOnceAndDoesNotResurrectDeletedObject() throws Exception {
    var pos = Position.of(server.getWorld("world").getSpawnLocation());
    await(
        store.save(
            "place",
            "hologram:legacy",
            0,
            new Gameplay.Place(pos, Map.of(), Collections.nCopies(33, "common.done")),
            "test"));
    await(holograms.load());
    var saved = holograms.get("legacy").orElseThrow();
    assertEquals(pos.y() + 2, saved.value().position().y());
    assertEquals(2, saved.value().pages().size());
    assertEquals(33, saved.value().pages().stream().mapToInt(page -> page.lines().size()).sum());
    assertTrue(await(store.get("place", "hologram:legacy", Gameplay.Place.class)).isEmpty());
    await(holograms.delete("legacy", saved.revision()));
    await(holograms.load());
    assertTrue(holograms.get("legacy").isEmpty());
  }

  @Test
  void tracePreferenceIsPersistentAndCommandsHaveCompletion() throws Exception {
    var p = admin();
    await(plugin.module("traces", true));
    command(p, "le traces off");
    await(store.profile(p.getUniqueId(), "test.barrier", "true"));
    assertEquals(
        "false", await(store.profile(p.getUniqueId(), "loveessential.traces")).orElseThrow());
    assertTrue(server.getCommandMap().tabComplete(p, "le hologram ").contains("edit"));
    assertTrue(
        server
            .getCommandMap()
            .tabComplete(p, "le ")
            .containsAll(List.of("hat", "heal", "feed", "fly", "repair")));
    var visitor = server.addPlayer("Guest");
    visitor.setFoodLevel(1);
    command(visitor, "feed");
    assertEquals(1, visitor.getFoodLevel());
  }

  private org.bukkit.event.entity.EntityDamageEvent damage(
      PlayerMock p, org.bukkit.event.entity.EntityDamageEvent.DamageCause cause) {
    var event =
        new org.bukkit.event.entity.EntityDamageEvent(
            p,
            cause,
            org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(),
            5);
    server.getPluginManager().callEvent(event);
    return event;
  }

  @Test
  void godModeStopsDamageAndHungerExceptTheKillCauses() throws Exception {
    var p = admin();
    await(plugin.module("utilities", true));
    command(p, "god");
    assertTrue(damage(p, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK).isCancelled());
    assertFalse(damage(p, org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID).isCancelled());
    assertFalse(damage(p, org.bukkit.event.entity.EntityDamageEvent.DamageCause.KILL).isCancelled());
    p.setFoodLevel(20);
    var hunger = new org.bukkit.event.entity.FoodLevelChangeEvent(p, 10);
    server.getPluginManager().callEvent(hunger);
    assertTrue(hunger.isCancelled());
    command(p, "god off");
    assertFalse(damage(p, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK).isCancelled());
    command(p, "god вкл");
    assertTrue(damage(p, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK).isCancelled());
    command(p, "god");
    assertFalse(damage(p, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK).isCancelled());
  }

  @Test
  void godModeNeedsItsOwnPermission() throws Exception {
    await(plugin.module("utilities", true));
    var visitor = server.addPlayer("Mortal");
    command(visitor, "god");
    assertNull(visitor.nextMessage());
    command(visitor, "vanish");
    assertNull(visitor.nextMessage());
    assertFalse(damage(visitor, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK).isCancelled());
  }
}
