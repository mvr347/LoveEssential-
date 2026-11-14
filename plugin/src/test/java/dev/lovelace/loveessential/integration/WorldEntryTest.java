package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.world.Worlds;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;

class WorldEntryTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  Extensions ext;
  WorldMock main, nether, end, arena;
  final Map<UUID, NativeDialogs.Form> forms = new HashMap<>();

  @BeforeEach void setup() throws Exception {
    server = MockBukkit.mock();
    main = server.addSimpleWorld("world");
    nether = new WorldMock(new WorldCreator("custom_hell").environment(World.Environment.NETHER));
    end = new WorldMock(new WorldCreator("custom_sky").environment(World.Environment.THE_END));
    arena = new WorldMock(new WorldCreator("arena").environment(World.Environment.NORMAL));
    server.addWorld(nether); server.addWorld(end); server.addWorld(arena);
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    await(plugin.module("scoreboard", false));
    var f = LoveEssentialPlugin.class.getDeclaredField("extensions");
    f.setAccessible(true); ext = (Extensions) f.get(plugin);
    ext.ui.presenter((player, form) -> forms.put(player.getUniqueId(), form));
  }
  @AfterEach void close() { MockBukkit.unmock(); }
  void waitFor(BooleanSupplier ready) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
      server.getScheduler().performOneTick(); Thread.sleep(5);
    }
    assertTrue(ready.getAsBoolean(), "asynchronous operation did not complete");
  }
  void await(CompletableFuture<?> future) throws Exception {
    waitFor(future::isDone); future.get(1, TimeUnit.SECONDS);
  }
  void settingsIdle() throws Exception {
    var field=LoveEssentialPlugin.class.getDeclaredField("reloading"); field.setAccessible(true);
    var reloading=(java.util.concurrent.atomic.AtomicBoolean)field.get(plugin);
    waitFor(() -> !reloading.get());
  }
  PlayerMock admin() { var p=server.addPlayer("Admin"); p.setOp(true); return p; }
  java.io.File configFile() { return new java.io.File(plugin.getDataFolder(),"modules/worlds/worlds.yml"); }
  PlayerTeleportEvent move(PlayerMock player, World target, PlayerTeleportEvent.TeleportCause cause) {
    var event=new PlayerTeleportEvent(player,player.getLocation(),target.getSpawnLocation(),cause);
    server.getPluginManager().callEvent(event); return event;
  }

  @Test void gameAndConsoleCommandsPersistAcrossReloadAndCoverCustomDimensionNames() throws Exception {
    var p=admin();
    assertTrue(server.dispatchCommand(p,"le worlds disable nether"));
    waitFor(() -> !ext.worlds.entry.allowed(nether));
    assertFalse(YamlConfiguration.loadConfiguration(configFile()).getBoolean("entry.nether.enabled"));
    settingsIdle();
    await(plugin.reloadSettings());
    assertFalse(ext.worlds.entry.allowed(nether));
    assertTrue(ext.worlds.entry.allowed(end));
    assertTrue(server.dispatchCommand(server.getConsoleSender(),"le worlds enable nether"));
    waitFor(() -> ext.worlds.entry.allowed(nether));
    assertFalse(move(p,nether,PlayerTeleportEvent.TeleportCause.COMMAND).isCancelled());
  }

  @Test void closingDimensionBlocksEveryTeleportCauseWithoutAnOperatorBypass() throws Exception {
    var p=admin();
    ext.worlds.command(p,new String[]{"disable","end"});
    waitFor(() -> !ext.worlds.entry.allowed(end));
    for (var cause : List.of(PlayerTeleportEvent.TeleportCause.COMMAND,
        PlayerTeleportEvent.TeleportCause.PLUGIN, PlayerTeleportEvent.TeleportCause.END_PORTAL,
        PlayerTeleportEvent.TeleportCause.ENDER_PEARL, PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT))
      assertTrue(move(p,end,cause).isCancelled(),cause.name());
    assertFalse(move(p,arena,PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
    assertThrows(IllegalArgumentException.class,() -> ext.worlds.teleport(p,end.getName()));
  }

  @Test void portalSwitchLeavesDimensionAccessibleByCommandsAndDoesNotAffectOtherPortals() throws Exception {
    var p=admin();
    ext.worlds.command(p,new String[]{"portals","nether","off"});
    waitFor(() -> !plugin.getConfig().getBoolean("worlds.entry.nether.portals"));
    var portal=new PlayerPortalEvent(p,p.getLocation(),nether.getSpawnLocation(),PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
    server.getPluginManager().callEvent(portal);
    assertTrue(portal.isCancelled());
    assertFalse(move(p,nether,PlayerTeleportEvent.TeleportCause.COMMAND).isCancelled());
    var other=new PlayerPortalEvent(p,p.getLocation(),end.getSpawnLocation(),PlayerTeleportEvent.TeleportCause.END_PORTAL);
    server.getPluginManager().callEvent(other);
    assertFalse(other.isCancelled());
  }

  @Test void closingNamedWorldEvacuatesPlayersWithoutUnloadingOrChangingInventory() throws Exception {
    var p=admin();
    assertTrue(p.teleport(arena.getSpawnLocation()));
    p.setGameMode(GameMode.ADVENTURE);
    p.getInventory().setItem(0,new ItemStack(Material.DIAMOND,3));
    ext.worlds.command(p,new String[]{"disable",arena.getName()});
    waitFor(() -> p.getWorld().equals(main));
    assertEquals(GameMode.ADVENTURE,p.getGameMode());
    assertEquals(3,p.getInventory().getItem(0).getAmount());
    assertSame(arena,Bukkit.getWorld("arena"));
    assertTrue(move(p,arena,PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
    assertFalse(move(p,nether,PlayerTeleportEvent.TeleportCause.PLUGIN).isCancelled());
  }

  @Test void manualConfigReloadEvacuatesAndInitialSpawnAndRespawnCannotReturnToClosedDimension() throws Exception {
    var p=admin(); assertTrue(p.teleport(end.getSpawnLocation()));
    var yaml=YamlConfiguration.loadConfiguration(configFile());
    yaml.set("entry.end.enabled",false); yaml.save(configFile());
    settingsIdle();
    await(plugin.reloadSettings());
    assertEquals(main,p.getWorld());
    var connection=(PlayerConfigurationConnection)java.lang.reflect.Proxy.newProxyInstance(
        PlayerConfigurationConnection.class.getClassLoader(),new Class<?>[]{PlayerConfigurationConnection.class},
        (proxy,method,args) -> { throw new UnsupportedOperationException(method.getName()); });
    var spawn=new AsyncPlayerSpawnLocationEvent(connection,end.getSpawnLocation(),false);
    CompletableFuture.runAsync(() -> server.getPluginManager().callEvent(spawn)).get(5,TimeUnit.SECONDS);
    assertEquals(main,spawn.getSpawnLocation().getWorld());
    spawn.getSpawnLocation().setX(9999);
    var nextSpawn=new AsyncPlayerSpawnLocationEvent(connection,end.getSpawnLocation(),false);
    CompletableFuture.runAsync(() -> server.getPluginManager().callEvent(nextSpawn)).get(5,TimeUnit.SECONDS);
    assertNotEquals(9999,nextSpawn.getSpawnLocation().getX(),"spawn snapshots must not expose mutable locations");
    var respawn=new PlayerRespawnEvent(p,end.getSpawnLocation(),false);
    server.getPluginManager().callEvent(respawn);
    assertEquals(main,respawn.getRespawnLocation().getWorld());
    var normal=new PlayerRespawnEvent(p,arena.getSpawnLocation(),false);
    server.getPluginManager().callEvent(normal);
    assertEquals(arena,normal.getRespawnLocation().getWorld());
  }

  @Test void unsafeFallbackAndClosingEveryWorldAreRejectedBeforeWritingConfig() throws Exception {
    var before=java.nio.file.Files.readString(configFile().toPath());
    var invalid=plugin.editSetting("worlds","entry.fallback-world","","missing");
    assertTrue(invalid.isCompletedExceptionally());
    var all=plugin.editSetting("worlds","entry.disabled-worlds",List.of(),List.of("world","custom_hell","custom_sky","arena"));
    assertTrue(all.isCompletedExceptionally());
    assertEquals(before,java.nio.file.Files.readString(configFile().toPath()));
    assertTrue(ext.worlds.entry.allowed(main));
  }

  @Test void mobsCannotPortalIntoClosedWorld() throws Exception {
    var p=admin(); ext.worlds.command(p,new String[]{"disable","nether"});
    waitFor(() -> !ext.worlds.entry.allowed(nether));
    var zombie=main.spawnEntity(main.getSpawnLocation(),EntityType.ZOMBIE);
    var event=new EntityPortalEvent(zombie,main.getSpawnLocation(),nether.getSpawnLocation());
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
  }

  @Test void accessEditorSavesAllSwitchesTogetherAndSurvivesReload() throws Exception {
    var p=admin(); ext.worlds.command(p,new String[]{"settings"});
    var form=forms.get(p.getUniqueId()); assertNotNull(form);
    assertTrue(ext.ui.respond(p,form.token(),"save",Map.of("nether","false","end","false",
        "nether_portals","false","end_portals","true","fallback","world","disabled","arena")));
    waitFor(() -> !ext.worlds.entry.allowed(end));
    settingsIdle();
    await(plugin.reloadSettings());
    assertFalse(ext.worlds.entry.allowed(nether)); assertFalse(ext.worlds.entry.allowed(end));
    assertFalse(ext.worlds.entry.allowed(arena)); assertTrue(ext.worlds.entry.allowed(main));
    var yaml=YamlConfiguration.loadConfiguration(configFile());
    assertEquals("world",yaml.getString("entry.fallback-world"));
    assertEquals(List.of("arena"),yaml.getStringList("entry.disabled-worlds"));
  }

  @Test void visitorsCannotChangeSettingsAndTabCompletionShowsControls() throws Exception {
    var visitor=server.addPlayer("Visitor");
    assertThrows(IllegalArgumentException.class,() -> ext.worlds.command(visitor,new String[]{"disable","end"}));
    assertTrue(ext.worlds.entry.allowed(end));
    assertTrue(plugin.getCommand("worlds").tabComplete(admin(),"worlds",new String[]{""}).contains("disable"));
    assertEquals(List.of("end"),plugin.getCommand("worlds").tabComplete(server.getConsoleSender(),"worlds",new String[]{"disable","e"}));
  }


  @Test void staleAccessEditorCannotOverwriteAConcurrentSetting() throws Exception {
    var p=admin(); ext.worlds.command(p,new String[]{"settings"});
    var form=forms.get(p.getUniqueId());
    await(plugin.editSetting("worlds","entry.nether.portals",true,false));
    assertTrue(ext.ui.respond(p,form.token(),"save",Map.of("nether","false","end","false",
        "nether_portals","true","end_portals","true","fallback","world","disabled","")));
    settingsIdle();
    assertTrue(ext.worlds.entry.allowed(nether));
    assertTrue(ext.worlds.entry.allowed(end));
    assertFalse(plugin.getConfig().getBoolean("worlds.entry.nether.portals"));
  }

  @Test void invalidManualReloadDoesNotReplaceTheActivePolicy() throws Exception {
    var yaml=YamlConfiguration.loadConfiguration(configFile());
    yaml.set("entry.fallback-world","missing"); yaml.save(configFile());
    var reload=plugin.reloadSettings(); waitFor(reload::isDone);
    assertTrue(reload.isCompletedExceptionally());
    assertEquals("",plugin.getConfig().getString("worlds.entry.fallback-world"));
    assertTrue(ext.worlds.entry.allowed(nether));
  }

  @Test void disabledWorldsAreNotAutoLoadedByWorldMetadata() throws Exception {
    var p=admin(); ext.worlds.command(p,new String[]{"disable","nether"});
    waitFor(() -> !ext.worlds.entry.allowed(nether));
    await(ext.worlds.save(new Worlds.Definition("never_load","NETHER","NORMAL",1,true,true,"",null,Worlds.Options.defaults()),0,"test"));
    await(ext.worlds.load());
    assertNull(Bukkit.getWorld("never_load"));
  }
}
