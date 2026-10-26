package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.revive.DownedOracle;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.api.revive.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The downed state: falling, the timer, reviving, price, restrictions and the events around it. */
class ReviveTest implements Listener {
  ServerMock server;
  LoveEssentialPlugin plugin;
  org.mockbukkit.mockbukkit.world.WorldMock world;
  PlayerMock victim, helper;
  final List<Object> seen = new ArrayList<>();
  int monitoredPvp;

  @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
  public void monitorPvp(EntityDamageByEntityEvent event) {
    monitoredPvp++;
  }

  int serverDeaths;

  @EventHandler
  public void actualDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
    serverDeaths++;
  }

  @EventHandler
  public void downed(DownedEvent e) {
    seen.add(e);
  }

  @EventHandler
  public void revived(ReviveEvent e) {
    seen.add(e);
  }

  @EventHandler
  public void died(DownedDeathEvent e) {
    seen.add(e);
  }

  @EventHandler
  public void stopped(StopRevivingEvent e) {
    seen.add(e);
  }

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    server.getPluginManager().registerEvents(this, MockBukkit.createMockPlugin("Observer"));
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    for (var module : List.of("scoreboard", "tab", "starter", "rtp"))
      await(plugin.module(module, false));
    await(plugin.module("revive", true));
    plugin.getConfig().set("revive.reliving-count", 2);
    victim = join("Victim");
    helper = join("Helper");
    // Each player loads visibility asynchronously, in both directions.
    waitFor(() -> helper.canSee(victim) && victim.canSee(helper));
    victim.setOp(false);
    helper.setOp(false);
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

  <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
    waitFor(future::isDone);
    return future.get();
  }

  PlayerMock join(String name) throws Exception {
    var p = new RecordingPlayer(server, name);
    server.addPlayer(p);
    var watcher = server.getOnlinePlayers().iterator().next();
    waitFor(() -> watcher.equals(p) || watcher.canSee(p));
    return p;
  }

  DownedOracle oracle() {
    return LoveCore.provider(DownedOracle.class, "loveessential").orElseThrow();
  }

  void hit(PlayerMock p, EntityDamageEvent.DamageCause cause, double amount) {
    var event =
        new EntityDamageEvent(p, cause, DamageSource.builder(DamageType.GENERIC).build(), amount);
    server.getPluginManager().callEvent(event);
    lastHit = event;
  }

  EntityDamageEvent lastHit;

  void fall() {
    // Real online players keep their chunk loaded; MockBukkit does not load it on teleport.
    var at = victim.getLocation();
    victim.getWorld().loadChunk(at.getBlockX() >> 4, at.getBlockZ() >> 4);
    hit(victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 100);
    assertTrue(oracle().downed(victim.getUniqueId()), "the lethal blow must put the player down");
  }

  org.bukkit.entity.Interaction hitbox() {
    return world.getEntitiesByClass(org.bukkit.entity.Interaction.class).stream()
        .filter(e -> !victim.canSee(e))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void emptyHandCanPickUpThroughTheBodyInteractionEntity() {
    fall();
    helper.teleport(victim.getLocation());
    var box = hitbox();
    assertEquals(1.6f, box.getInteractionWidth());
    assertEquals(.9f, box.getInteractionHeight());
    assertFalse(box.isPersistent());
    var click = new PlayerInteractEntityEvent(helper, box, org.bukkit.inventory.EquipmentSlot.HAND);
    server.getPluginManager().callEvent(click);
    assertTrue(click.isCancelled());
    assertTrue(helper.getPassengers().contains(victim));
  }

  @Test
  void sneakingBodyClickOpensSearchInsteadOfCarrying() {
    fall();
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    var click =
        new PlayerInteractEntityEvent(helper, hitbox(), org.bukkit.inventory.EquipmentSlot.HAND);
    server.getPluginManager().callEvent(click);
    assertEquals(54, helper.getOpenInventory().getTopInventory().getSize());
    assertFalse(helper.getPassengers().contains(victim));
  }

  @Test
  void bodyInteractionFollowsThePlayerAndIsRemovedOnRevive() {
    fall();
    var box = hitbox();
    victim.teleport(victim.getLocation().add(2, 0, 0));
    server.getScheduler().performTicks(4);
    assertEquals(victim.getLocation(), box.getLocation());
    server.dispatchCommand(server.getConsoleSender(), "revive revive Victim");
    assertFalse(box.isValid());
    assertTrue(world.getEntitiesByClass(org.bukkit.entity.Interaction.class).isEmpty());
  }

  static final class RecordingPlayer extends PlayerMock {
    org.bukkit.entity.Entity attacked;
    boolean lineOfSight = true;

    @Override
    public boolean hasLineOfSight(org.bukkit.entity.Entity target) {
      return lineOfSight;
    }

    @Override
    public void attack(org.bukkit.entity.Entity target) {
      attacked = target;
    }

    final java.util.Map<Location, org.bukkit.block.data.BlockData> changes =
        new java.util.HashMap<>();

    RecordingPlayer(ServerMock server, String name) {
      super(server, name);
    }

    @Override
    public void sendBlockChange(Location location, org.bukkit.block.data.BlockData data) {
      changes.put(location.clone(), data.clone());
    }
  }

  @Test
  void aWallPreventsPickingUpThroughTheExpandedBody() {
    fall();
    helper.teleport(victim.getLocation());
    ((RecordingPlayer) helper).lineOfSight = false;
    var click =
        new PlayerInteractEntityEvent(helper, hitbox(), org.bukkit.inventory.EquipmentSlot.HAND);
    server.getPluginManager().callEvent(click);
    assertFalse(helper.getPassengers().contains(victim));
  }

  @Test
  void vanishedPlayersDoNotLeaveAVisibleClickableBody() {
    fall();
    var box = hitbox();
    assertTrue(helper.canSee(box));
    helper.hidePlayer(plugin, victim);
    server.getScheduler().performTicks(4);
    assertFalse(helper.canSee(box));
    helper.showPlayer(plugin, victim);
    server.getScheduler().performTicks(4);
    assertTrue(helper.canSee(box));
  }

  @Test
  void attackingTheBodyUsesTheRealPlayerAttackInsteadOfAbsorbingTheHit() {
    fall();
    helper.teleport(victim.getLocation());
    var attack =
        new io.papermc.paper.event.player.PrePlayerAttackEntityEvent(helper, hitbox(), false);
    server.getPluginManager().callEvent(attack);
    assertSame(victim, ((RecordingPlayer) helper).attacked);
  }

  @Test
  void fallingEnablesLocalCrawlingAndDoesNotPlaceARealBlock() {
    var ceiling = victim.getLocation().getBlock().getRelative(0, 1, 0);
    fall();
    assertTrue(victim.isSwimming());
    assertEquals(Material.AIR, ceiling.getType());
    assertEquals(
        Material.BARRIER,
        ((RecordingPlayer) victim).changes.get(ceiling.getLocation()).getMaterial());
    var stopSwimming = new org.bukkit.event.entity.EntityToggleSwimEvent(victim, false);
    server.getPluginManager().callEvent(stopSwimming);
    assertTrue(stopSwimming.isCancelled());
  }

  @Test
  void movingAndRevivingRestoreTheRealClientBlocks() {
    var previous = victim.getLocation().getBlock().getRelative(0, 1, 0);
    fall();
    victim.teleport(victim.getLocation().add(1, 0, 0));
    server.getScheduler().performTicks(4);
    assertEquals(
        Material.AIR, ((RecordingPlayer) victim).changes.get(previous.getLocation()).getMaterial());
    var current = victim.getLocation().getBlock().getRelative(0, 1, 0);
    current.setType(Material.STONE);
    server.dispatchCommand(server.getConsoleSender(), "revive revive Victim");
    assertFalse(victim.isSwimming());
    assertFalse(victim.hasFixedPose());
    assertEquals(
        Material.STONE,
        ((RecordingPlayer) victim).changes.get(current.getLocation()).getMaterial());
  }

  @Test
  void pickingUpRestoresTheCeilingAndDroppingEnablesCrawlingAgain() {
    var ceiling = victim.getLocation().getBlock().getRelative(0, 1, 0);
    fall();
    helper.teleport(victim.getLocation());
    rightClick(helper, victim);
    assertEquals(
        Material.AIR, ((RecordingPlayer) victim).changes.get(ceiling.getLocation()).getMaterial());
    server
        .getPluginManager()
        .callEvent(new org.bukkit.event.player.PlayerToggleSneakEvent(helper, true));
    var current = victim.getLocation().getBlock().getRelative(0, 1, 0);
    assertEquals(
        Material.BARRIER,
        ((RecordingPlayer) victim).changes.get(current.getLocation()).getMaterial());
  }

  @Test
  void preciseEntityClickAndOrdinaryClickOnlyOpenOneSearchWindow() {
    fall();
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    var precise =
        new org.bukkit.event.player.PlayerInteractAtEntityEvent(
            helper,
            victim,
            new org.bukkit.util.Vector(0, .2, 0),
            org.bukkit.inventory.EquipmentSlot.HAND);
    server.getPluginManager().callEvent(precise);
    assertTrue(precise.isCancelled());
    var first = helper.getOpenInventory().getTopInventory();
    assertEquals(54, first.getSize());
    assertTrue(rightClick(helper, victim).isCancelled());
    assertSame(first, helper.getOpenInventory().getTopInventory());
  }

  @Test
  void cancelledEntityClicksAndOffHandClicksDoNotPickUp() {
    fall();
    helper.teleport(victim.getLocation());
    var cancelled =
        new PlayerInteractEntityEvent(helper, victim, org.bukkit.inventory.EquipmentSlot.HAND);
    cancelled.setCancelled(true);
    server.getPluginManager().callEvent(cancelled);
    var offHand =
        new PlayerInteractEntityEvent(helper, victim, org.bukkit.inventory.EquipmentSlot.OFF_HAND);
    server.getPluginManager().callEvent(offHand);
    assertFalse(helper.getPassengers().contains(victim));
    assertTrue(rightClick(helper, victim).isCancelled());
    assertTrue(helper.getPassengers().contains(victim));
  }

  @Test
  void disablingReviveRestoresTheClientBlockAndSwimmingState() throws Exception {
    var ceiling = victim.getLocation().getBlock().getRelative(0, 1, 0);
    fall();
    await(plugin.module("revive", false));
    server.getScheduler().performTicks(4);
    assertFalse(victim.isSwimming());
    assertEquals(
        Material.AIR, ((RecordingPlayer) victim).changes.get(ceiling.getLocation()).getMaterial());
  }

  @Test
  void aLethalPvpHitIsCancelledBeforeTheCombatTrackerMonitorCanSeeIt() {
    var event =
        new EntityDamageByEntityEvent(
            helper,
            victim,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withDirectEntity(helper)
                .withCausingEntity(helper)
                .build(),
            100);
    server.getPluginManager().callEvent(event);
    assertTrue(oracle().downed(victim.getUniqueId()));
    assertTrue(event.isCancelled());
    assertEquals(
        0, monitoredPvp, "LoveCore CombatTracker uses this same MONITOR/ignoreCancelled contract");
  }

  @Test
  void anExternalReviveMePluginDoesNotAutomaticallyDisableTheInternalModule() {
    MockBukkit.createMockPlugin("ReviveMe");
    fall();
    assertTrue(plugin.module("revive"), "provider coexistence is not guarded at runtime");
  }

  @Test
  void nativeCorpseHandlersSeeOnlyFinalDeathAndFallingDoesNotConsumeLoot() {
    plugin.getConfig().set("revive.death-delay", 1);
    plugin.getConfig().set("revive.keep-inventory", false);
    victim.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 7));
    fall();
    assertEquals(0, serverDeaths, "falling is not a Bukkit death for a corpse handler");
    assertEquals(7, victim.getInventory().getItem(0).getAmount());
    server.getScheduler().performTicks(40);
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertEquals(1, serverDeaths, "the timer must produce exactly one real death");
    server.getScheduler().performTicks(40);
    assertEquals(1, serverDeaths);
  }

  @Test
  void aLethalBlowDropsThePlayerInsteadOfKillingThem() {
    fall();
    assertTrue(lastHit.isCancelled());
    assertEquals(1.0, victim.getHealth(), 0.001);
    assertTrue(seen.stream().anyMatch(DownedEvent.class::isInstance));
    assertFalse(oracle().reviving(victim.getUniqueId()));
  }

  @Test
  void aNonLethalBlowLeavesThePlayerAlone() {
    hit(victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 3);
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertFalse(lastHit.isCancelled());
  }

  @Test
  void voidDamageStillKillsOutright() {
    hit(victim, EntityDamageEvent.DamageCause.VOID, 100);
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertFalse(lastHit.isCancelled());
  }

  @Test
  void aTotemInHandActsFirst() {
    victim.getInventory().setItemInMainHand(new ItemStack(Material.TOTEM_OF_UNDYING));
    hit(victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 100);
    assertFalse(oracle().downed(victim.getUniqueId()));
  }

  @Test
  void theModuleDoesNothingWhileItIsOff() throws Exception {
    await(plugin.module("revive", false));
    hit(victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 100);
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertFalse(lastHit.isCancelled());
  }

  @Test
  void aDownedPlayerIsInvulnerableOnlyForTheConfiguredWindow() {
    plugin.getConfig().set("revive.invulnerability-delay", 1);
    fall();
    hit(victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 100);
    assertTrue(lastHit.isCancelled(), "inside the window nothing hurts");
    server.getScheduler().performTicks(30);
    hit(victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 100);
    assertFalse(lastHit.isCancelled(), "after the window damage goes through");
  }

  @Test
  void aSneakingNeighbourRevivesAfterTheConfiguredTime() {
    fall();
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    server.getScheduler().performTicks(60);
    assertTrue(oracle().reviving(victim.getUniqueId()) || !oracle().downed(victim.getUniqueId()));
    server.getScheduler().performTicks(60);
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertEquals(4.0, victim.getHealth(), 0.001);
    var revived =
        seen.stream()
            .filter(ReviveEvent.class::isInstance)
            .map(ReviveEvent.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(helper, revived.getReviver());
  }

  @Test
  void standingUpOrWalkingAwayStopsTheRevive() {
    fall();
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    server.getScheduler().performTicks(20);
    helper.setSneaking(false);
    server.getScheduler().performTicks(20);
    assertTrue(oracle().downed(victim.getUniqueId()));
    assertTrue(seen.stream().anyMatch(StopRevivingEvent.class::isInstance));
    assertFalse(oracle().reviving(victim.getUniqueId()));
  }

  @Test
  void aHelperOutsideTheStartDistanceDoesNotStart() {
    fall();
    helper.teleport(victim.getLocation().add(3, 0, 0));
    helper.setSneaking(true);
    server.getScheduler().performTicks(60);
    assertFalse(oracle().reviving(victim.getUniqueId()));
  }

  @Test
  void theTimerKillsTheDownedPlayerWhenItRunsOut() {
    plugin.getConfig().set("revive.death-delay", 1);
    plugin.getConfig().set("revive.invulnerability-delay", 0);
    fall();
    server.getScheduler().performTicks(40);
    assertFalse(oracle().downed(victim.getUniqueId()));
    var death =
        seen.stream()
            .filter(DownedDeathEvent.class::isInstance)
            .map(DownedDeathEvent.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(DownedDeathEvent.Cause.TIMER, death.getCause());
  }

  @Test
  void theTimerCanReviveInsteadOfKilling() {
    plugin.getConfig().set("revive.death-delay", 1);
    plugin.getConfig().set("revive.death-count-action", "revive");
    fall();
    server.getScheduler().performTicks(40);
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertTrue(seen.stream().anyMatch(ReviveEvent.class::isInstance));
  }

  @Test
  void suicideIsRefusedTooEarlyAndWorksAfterTheDelay() {
    plugin.getConfig().set("revive.suicide-delay", 1);
    fall();
    server.dispatchCommand(victim, "revive suicide");
    assertTrue(oracle().downed(victim.getUniqueId()), "too early");
    server.getScheduler().performTicks(30);
    server.dispatchCommand(victim, "revive suicide");
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertEquals(
        DownedDeathEvent.Cause.SUICIDE,
        seen.stream()
            .filter(DownedDeathEvent.class::isInstance)
            .map(DownedDeathEvent.class::cast)
            .findFirst()
            .orElseThrow()
            .getCause());
  }

  @Test
  void aDownedPlayerCannotBreakBlocksOrRunCommands() {
    fall();
    var block = victim.getWorld().getBlockAt(0, 64, 0);
    var breaking = new BlockBreakEvent(block, victim);
    server.getPluginManager().callEvent(breaking);
    assertTrue(breaking.isCancelled());
    var command = new PlayerCommandPreprocessEvent(victim, "/home");
    server.getPluginManager().callEvent(command);
    assertTrue(command.isCancelled());
    var allowed = new PlayerCommandPreprocessEvent(victim, "/revive suicide");
    server.getPluginManager().callEvent(allowed);
    assertFalse(allowed.isCancelled());
  }

  @Test
  void adminCommandsReviveDownAndKill() throws Exception {
    var admin = join("Boss");
    admin.setOp(true);
    server.dispatchCommand(admin, "revive down Victim");
    assertTrue(oracle().downed(victim.getUniqueId()));
    server.dispatchCommand(admin, "revive revive Victim");
    assertFalse(oracle().downed(victim.getUniqueId()));
    server.dispatchCommand(admin, "revive down Victim");
    server.dispatchCommand(admin, "revive kill Victim");
    assertFalse(oracle().downed(victim.getUniqueId()));
    server.dispatchCommand(victim, "revive down Helper");
    assertFalse(oracle().downed(helper.getUniqueId()), "players cannot use admin subcommands");
  }

  @Test
  void leavingWithoutDisconnectKillRestoresHealthOnNextJoin() throws Exception {
    plugin.getConfig().set("revive.disconnect-kill", false);
    fall();
    server
        .getPluginManager()
        .callEvent(
            new org.bukkit.event.player.PlayerQuitEvent(
                victim,
                net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertEquals(1.0, victim.getHealth(), 0.001);
    server
        .getPluginManager()
        .callEvent(
            new org.bukkit.event.player.PlayerJoinEvent(
                victim, net.kyori.adventure.text.Component.empty()));
    assertEquals(4.0, victim.getHealth(), 0.001);
  }

  @Test
  void disconnectKillEndsTheDownedStateWithAQuitDeath() {
    fall();
    server
        .getPluginManager()
        .callEvent(
            new org.bukkit.event.player.PlayerQuitEvent(
                victim,
                net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertTrue(
        seen.stream()
            .anyMatch(
                e ->
                    e instanceof DownedDeathEvent d
                        && d.getCause() == DownedDeathEvent.Cause.QUIT));
  }

  void configurePrice(int levels) {
    plugin.getConfig().set("revive.price.type", "level");
    plugin.getConfig().set("revive.price.amount", levels);
  }

  @Test
  void thePriceIsTakenOnStartRefundedOnStopAndKeptOnSuccess() {
    configurePrice(2);
    fall();
    helper.setLevel(5);
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    server.getScheduler().performTicks(10);
    assertEquals(3, helper.getLevel(), "charged when the revive starts");
    helper.setSneaking(false);
    server.getScheduler().performTicks(10);
    assertEquals(5, helper.getLevel(), "refunded when the helper gives up");
    helper.setSneaking(true);
    server.getScheduler().performTicks(100);
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertEquals(3, helper.getLevel(), "kept once the revive finished");
  }

  @Test
  void aHelperWhoCannotPayDoesNotStart() {
    configurePrice(2);
    fall();
    helper.setLevel(1);
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    server.getScheduler().performTicks(40);
    assertFalse(oracle().reviving(victim.getUniqueId()));
    assertEquals(1, helper.getLevel());
  }

  @Test
  void configuredActionsRunWhenSomebodyIsRevived() {
    plugin
        .getConfig()
        .set(
            "revive.events.revive.downed",
            List.of(
                java.util.Map.of(
                    "type", "potion", "effect", "regeneration", "seconds", 5, "amplifier", 1)));
    fall();
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    server.getScheduler().performTicks(100);
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertNotNull(victim.getPotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION));
  }

  @Test
  void aSneakingPlayerCanOpenTheInventoryOfADownedOne() {
    fall();
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    var click =
        new org.bukkit.event.player.PlayerInteractEntityEvent(
            helper, victim, org.bukkit.inventory.EquipmentSlot.HAND);
    server.getPluginManager().callEvent(click);
    assertTrue(click.isCancelled());
    assertEquals(54, helper.getOpenInventory().getTopInventory().getSize());
    server.dispatchCommand(server.addPlayer("Boss"), "revive revive Victim");
  }

  @Test
  void aStandingPlayerCannotRobAHealthyOne() {
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    var click =
        new org.bukkit.event.player.PlayerInteractEntityEvent(
            helper, victim, org.bukkit.inventory.EquipmentSlot.HAND);
    server.getPluginManager().callEvent(click);
    assertFalse(click.isCancelled());
  }

  PlayerInteractEntityEvent rightClick(PlayerMock who, PlayerMock target) {
    var click = new PlayerInteractEntityEvent(who, target, org.bukkit.inventory.EquipmentSlot.HAND);
    server.getPluginManager().callEvent(click);
    return click;
  }

  EntityDamageByEntityEvent strike(PlayerMock attacker, PlayerMock target) {
    var hit =
        new EntityDamageByEntityEvent(
            attacker,
            target,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withDirectEntity(attacker)
                .withCausingEntity(attacker)
                .build(),
            1);
    server.getPluginManager().callEvent(hit);
    return hit;
  }

  @Test
  void rightClickPicksADownedPlayerUpAndSneakingPutsThemDown() {
    fall();
    helper.teleport(victim.getLocation());
    assertTrue(rightClick(helper, victim).isCancelled());
    assertTrue(helper.getPassengers().contains(victim));
    server
        .getPluginManager()
        .callEvent(new org.bukkit.event.player.PlayerToggleSneakEvent(helper, true));
    assertFalse(helper.getPassengers().contains(victim));
    assertTrue(oracle().downed(victim.getUniqueId()));
  }

  @Test
  void hittingADownedPlayerDoesNotPickThemUp() {
    fall();
    helper.teleport(victim.getLocation());
    strike(helper, victim);
    assertFalse(helper.getPassengers().contains(victim));
  }

  @Test
  void damageToTheCarriedPlayerDropsBoth() {
    plugin.getConfig().set("revive.invulnerability-delay", 0);
    fall();
    server.getScheduler().performTicks(4);
    helper.teleport(victim.getLocation());
    rightClick(helper, victim);
    assertTrue(helper.getPassengers().contains(victim));
    hit(victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 0.1);
    assertFalse(helper.getPassengers().contains(victim));
    assertTrue(oracle().downed(victim.getUniqueId()));
  }

  @Test
  void damageToTheCarrierDropsBoth() {
    fall();
    helper.teleport(victim.getLocation());
    rightClick(helper, victim);
    hit(helper, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 0.1);
    assertFalse(helper.getPassengers().contains(victim));
  }

  @Test
  void theDeathTimerStandsWhileSomebodyIsReviving() {
    plugin.getConfig().set("revive.death-delay", 3);
    plugin.getConfig().set("revive.reliving-count", 5);
    plugin.getConfig().set("revive.invulnerability-delay", 0);
    fall();
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    server.getScheduler().performTicks(90);
    assertTrue(oracle().downed(victim.getUniqueId()), "the timer must stand while the revive runs");
    assertTrue(oracle().reviving(victim.getUniqueId()));
    server.getScheduler().performTicks(60);
    assertFalse(oracle().downed(victim.getUniqueId()));
    assertTrue(seen.stream().anyMatch(ReviveEvent.class::isInstance));
  }

  @Test
  void theDeathTimerRunsWhenSwitchedOffForReviving() {
    plugin.getConfig().set("revive.revive-pause-timer", false);
    plugin.getConfig().set("revive.death-delay", 3);
    plugin.getConfig().set("revive.reliving-count", 5);
    plugin.getConfig().set("revive.invulnerability-delay", 0);
    fall();
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    server.getScheduler().performTicks(90);
    assertFalse(oracle().downed(victim.getUniqueId()));
  }

  @Test
  void aSearchWindowHasOnlyItemsAndACloseButton() {
    fall();
    helper.teleport(victim.getLocation());
    helper.setSneaking(true);
    rightClick(helper, victim);
    var top = helper.getOpenInventory().getTopInventory();
    assertEquals(54, top.getSize());
    assertEquals(
        org.bukkit.Material.GRAY_STAINED_GLASS_PANE, top.getItem(51).getType(), "no mode switch");
    assertEquals(
        org.bukkit.Material.GRAY_STAINED_GLASS_PANE, top.getItem(52).getType(), "no back button");
    assertEquals(
        org.bukkit.Material.GRAY_STAINED_GLASS_PANE,
        top.getItem(49).getType(),
        "no obsolete center close button");
    assertNotEquals(
        org.bukkit.Material.GRAY_STAINED_GLASS_PANE, top.getItem(53).getType(), "close button");
  }
}
