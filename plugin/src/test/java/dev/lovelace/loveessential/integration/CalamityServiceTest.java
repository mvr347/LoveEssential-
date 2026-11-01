package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.Extensions;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.calamity.CalamityService;
import dev.lovelace.loveessential.calamity.CalamityService.Disease;
import dev.lovelace.loveessential.calamity.CalamityService.Phase;
import dev.lovelace.loveessential.calamity.CalamityService.Type;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The calamity flow, its effects on events, diseases, the command rules and the saved state. */
class CalamityServiceTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  CalamityService service;
  final java.util.concurrent.atomic.AtomicLong wall = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    var scoreboard = plugin.module("scoreboard", false);
    waitFor(scoreboard::isDone);
    // Full strength with one player, and no newcomer exemption, so the numbers are exact.
    plugin.getConfig().set("calamities.online-full-strength", 1);
    plugin.getConfig().set("calamities.newcomer-minutes", 0);
    var field = LoveEssentialPlugin.class.getDeclaredField("extensions");
    field.setAccessible(true);
    service = ((Extensions) field.get(plugin)).calamities;
    var clockField = CalamityService.class.getDeclaredField("nowMillis");
    clockField.setAccessible(true);
    clockField.set(service, (java.util.function.LongSupplier) wall::get);
    server.getScheduler().performTicks(40);
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

  PlayerMock admin() {
    var p = server.addPlayer("Admin");
    p.addAttachment(plugin, "loveessential.calamities.admin", true);
    return p;
  }

  /** Runs the clock until the calamity is in its flat, full-strength middle. */
  void toPeak() {
    wall.addAndGet(35_000);
    server.getScheduler().performTicks(20);
  }

  @Test
  void nothingIsPenalizedWithoutACalamity() {
    admin();
    var block = server.getWorld("world").getBlockAt(0, 64, 0);
    block.setType(Material.WHEAT);
    int cancelled = 0;
    for (int i = 0; i < 500; i++) {
      var event = new BlockGrowEvent(block, block.getState());
      server.getPluginManager().callEvent(event);
      if (event.isCancelled()) cancelled++;
    }
    assertEquals(0, cancelled);
    assertEquals(Phase.IDLE, service.phase());
  }

  @Test
  void bugsCancelAboutThirtyFivePercentOfCropGrowthAtThePeak() {
    var admin = admin();
    service.command(admin, new String[] {"start", "bugs", "normal", "1"});
    assertEquals(Phase.ACTIVE, service.phase());
    toPeak();
    assertEquals(1.0, service.strength(), 1e-9);
    var block = server.getWorld("world").getBlockAt(0, 64, 0);
    block.setType(Material.WHEAT);
    int cancelled = 0, total = 4000;
    for (int i = 0; i < total; i++) {
      var event = new BlockGrowEvent(block, block.getState());
      server.getPluginManager().callEvent(event);
      if (event.isCancelled()) cancelled++;
    }
    assertEquals(0.35, cancelled / (double) total, 0.03);
    // Trees and flowers are not on the crop list.
    block.setType(Material.OAK_SAPLING);
    var sapling = new BlockGrowEvent(block, block.getState());
    server.getPluginManager().callEvent(sapling);
    assertFalse(sapling.isCancelled());
  }

  @Test
  void aCalamityEndsOnItsOwnAndLeavesACooldown() {
    var admin = admin();
    service.command(admin, new String[] {"start", "plague", "normal", "1"});
    wall.addAndGet(65_000);
    server.getScheduler().performTicks(20);
    assertEquals(Phase.IDLE, service.phase());
    assertEquals(0, service.strength());
  }

  @Test
  void realTimeContinuesWithoutPlayersAndServerTicksDoNotAccelerateIt() {
    var admin = admin();
    service.command(admin, new String[] {"start", "plague", "normal", "1"});
    server.getScheduler().performTicks(3000);
    assertEquals(Phase.ACTIVE, service.phase(), "server ticks cannot accelerate the real clock");
    admin.disconnect();
    wall.addAndGet(65_000);
    server.getScheduler().performTicks(20);
    assertEquals(Phase.IDLE, service.phase(), "real time continues without players");
  }

  @Test
  void onlyAdminsMayStartOrStopAndTheRefusalIsSilent() {
    var player = server.addPlayer("Alice");
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.command(player, new String[] {"start", "bugs"}));
    assertEquals("errors.permission", error.getMessage());
    assertEquals(Phase.IDLE, service.phase());
    assertDoesNotThrow(() -> service.command(player, new String[0]));
  }

  @Test
  void aSecondStartIsRefusedWhileOneRuns() {
    var admin = admin();
    service.command(admin, new String[] {"start", "bugs", "normal", "5"});
    service.command(admin, new String[] {"start", "plague", "normal", "5"});
    assertEquals(Type.BUGS, service.current());
    service.command(admin, new String[] {"stop"});
    assertEquals(Phase.IDLE, service.phase());
  }

  @Test
  void badArgumentsReportUsage() {
    var admin = admin();
    for (String[] args :
        new String[][] {
          {"start"}, {"start", "nope"}, {"start", "bugs", "nope"}, {"threat", "bugs", "500"}, {"zzz"}
        }) {
      var error = assertThrows(IllegalArgumentException.class, () -> service.command(admin, args));
      assertEquals("calamity.usage", error.getMessage());
    }
  }

  PlayerMock sick(String name, Disease disease) throws Exception {
    var p = server.addPlayer(name);
    var admin = admin();
    // The player's record arrives asynchronously; infecting before that is ignored, so retry.
    waitFor(
        () -> {
          service.command(admin, new String[] {"disease", disease.key, p.getName()});
          return service.sick(p, disease);
        });
    return p;
  }

  @Test
  void scurvyStopsNaturalRegenerationButNotPotions() throws Exception {
    var p = sick("Alice", Disease.SCURVY);
    var natural = new EntityRegainHealthEvent(p, 1, EntityRegainHealthEvent.RegainReason.SATIATED);
    server.getPluginManager().callEvent(natural);
    assertTrue(natural.isCancelled());
    var peaceful = new EntityRegainHealthEvent(p, 1, EntityRegainHealthEvent.RegainReason.REGEN);
    server.getPluginManager().callEvent(peaceful);
    assertTrue(peaceful.isCancelled());
    var potion = new EntityRegainHealthEvent(p, 1, EntityRegainHealthEvent.RegainReason.MAGIC);
    server.getPluginManager().callEvent(potion);
    assertFalse(potion.isCancelled());
    var healthy = server.addPlayer("Bob");
    var other = new EntityRegainHealthEvent(healthy, 1, EntityRegainHealthEvent.RegainReason.SATIATED);
    server.getPluginManager().callEvent(other);
    assertFalse(other.isCancelled());
  }

  @Test
  void freshFoodCuresScurvyAfterTheConfiguredMeals() throws Exception {
    var p = sick("Alice", Disease.SCURVY);
    for (int i = 0; i < 3; i++) {
      server.getPluginManager().callEvent(new PlayerItemConsumeEvent(p, new ItemStack(Material.APPLE)));
      assertTrue(service.sick(p, Disease.SCURVY), "still sick after " + (i + 1) + " meals");
    }
    server.getPluginManager().callEvent(new PlayerItemConsumeEvent(p, new ItemStack(Material.APPLE)));
    assertFalse(service.sick(p, Disease.SCURVY));
  }

  @Test
  void hungerMakesExhaustionOneAndAHalfTimesFaster() throws Exception {
    var p = sick("Alice", Disease.HUNGER);
    var event = new EntityExhaustionEvent(p, EntityExhaustionEvent.ExhaustionReason.SPRINT, 0.2f);
    server.getPluginManager().callEvent(event);
    assertEquals(0.3f, event.getExhaustion(), 1e-6);
    var healthy = server.addPlayer("Bob");
    var other = new EntityExhaustionEvent(healthy, EntityExhaustionEvent.ExhaustionReason.SPRINT, 0.2f);
    server.getPluginManager().callEvent(other);
    assertEquals(0.2f, other.getExhaustion(), 1e-6);
  }

  @Test
  void differentMealsCureHunger() throws Exception {
    var p = sick("Alice", Disease.HUNGER);
    for (Material m : new Material[] {Material.BREAD, Material.COOKED_BEEF, Material.BAKED_POTATO})
      server.getPluginManager().callEvent(new PlayerItemConsumeEvent(p, new ItemStack(m)));
    assertFalse(service.sick(p, Disease.HUNGER));
  }

  @Test
  void worldModeMakesEveryoneSickWhileACarrierRuns() throws Exception {
    plugin.getConfig().set("calamities.diseases.hunger.mode", "world");
    var admin = admin();
    var bob = server.addPlayer("Bob");
    server.getScheduler().performTicks(40);
    assertFalse(service.sick(bob, Disease.HUNGER));
    service.command(admin, new String[] {"start", "infertile", "normal", "5"});
    assertTrue(service.sick(bob, Disease.HUNGER));
    service.command(admin, new String[] {"stop"});
    assertFalse(service.sick(bob, Disease.HUNGER));
  }

  @Test
  void theStateIsSavedSoARestartKeepsTheCalamity() throws Exception {
    var admin = admin();
    service.command(admin, new String[] {"start", "undead", "heavy", "30"});
    server.getScheduler().performTicks(1300);
    var field = LoveEssentialPlugin.class.getDeclaredField("store");
    field.setAccessible(true);
    var store = (dev.lovelace.loveessential.storage.SqliteStore) field.get(plugin);
    var stored = store.profile(new java.util.UUID(0L, 0xCA1A0001L), "calamity.world").join();
    assertTrue(stored.isPresent());
    assertTrue(stored.get().contains("\"type\":\"undead\""), stored.get());
    assertTrue(stored.get().contains("\"phase\":\"ACTIVE\""), stored.get());
  }

  @Test
  void aBrokenConfigurationIsRejectedWithAConfigError() {
    var config = new org.bukkit.configuration.file.YamlConfiguration();
    config.set("calamities.cap-percent", 400);
    var error = assertThrows(IllegalArgumentException.class, () -> CalamityService.validate(config));
    assertEquals("errors.config", error.getMessage());
    var modes = new org.bukkit.configuration.file.YamlConfiguration();
    modes.set("calamities.diseases.hunger.mode", "everywhere");
    assertThrows(IllegalArgumentException.class, () -> CalamityService.validate(modes));
    var materials = new org.bukkit.configuration.file.YamlConfiguration();
    materials.set("calamities.meat-items", java.util.List.of("NOT_A_MATERIAL"));
    assertThrows(IllegalArgumentException.class, () -> CalamityService.validate(materials));
    var fine = new org.bukkit.configuration.file.YamlConfiguration();
    assertDoesNotThrow(() -> CalamityService.validate(fine));
  }
}
