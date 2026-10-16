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
}
