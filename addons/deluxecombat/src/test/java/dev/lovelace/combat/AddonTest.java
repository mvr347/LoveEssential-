package dev.lovelace.combat;

import static org.junit.jupiter.api.Assertions.*;
import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.auth.AuthOracle;
import dev.lovelace.lovecore.api.combat.CombatState;
import dev.lovelace.lovecore.api.revive.DownedOracle;
import dev.lovelace.loveessential.api.revive.DownedEvent;
import java.time.*;
import java.util.*;
import nl.marido.deluxecombat.api.DeluxeCombatAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AddonTest {
  ServerMock server;
  Plugin dc;
  CombatState upstream;
  DeluxeCombatAddon addon;
  FakeAPI api;
  PlayerMock victim, attacker;
  final Set<UUID> downed = new HashSet<>();
  static class FakeAPI extends DeluxeCombatAPI {
    Map<UUID, Long> times = new HashMap<>();
    int tags;
    @Override public boolean hasPvPEnabled(Player p) { return true; }
    @Override public boolean isInCombat(Player p) { return times.containsKey(p.getUniqueId()); }
    @Override public long getRemainingCombatTimeMillis(Player p) { return times.getOrDefault(p.getUniqueId(), 0L); }
    @Override public void tag(Player p, Object a, int s) { tags++; times.put(p.getUniqueId(), s * 1000L); }
    @Override public void untag(Player p) { times.remove(p.getUniqueId()); }
  }
  @BeforeEach void setup() {
    server = MockBukkit.mock();
    dc = MockBukkit.createMockPlugin("DeluxeCombat");
    Plugin core = MockBukkit.createMockPlugin("LoveCore");
    MockBukkit.createMockPlugin("LoveEssential");
    upstream = new CombatBridgeTest.State();
    server.getServicesManager().register(CombatState.class, upstream, core, ServicePriority.Normal);
    server.getServicesManager().register(DownedOracle.class, new DownedOracle() {
      public String providerId() { return "loveessential"; }
      public boolean downed(UUID id) { return downed.contains(id); }
      public boolean reviving(UUID id) { return false; }
    }, core, ServicePriority.Normal);
    victim = server.addPlayer("Victim"); attacker = server.addPlayer("Attacker");
    api = new FakeAPI();
    addon = new DeluxeCombatAddon(() -> api);
    addon.register();
    server.getScheduler().performOneTick();
  }
  @AfterEach void cleanup() { addon.unregister(); MockBukkit.unmock(); }
  CombatState state() { return LoveCore.service(CombatState.class).orElseThrow(); }
  DownedEvent fall() {
    var damage = new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 100);
    var event = new DownedEvent(victim, damage);
    server.getPluginManager().callEvent(event);
    return event;
  }
  public static class DuelsPlugin extends org.bukkit.plugin.java.JavaPlugin {
    final DuelManager manager = new DuelManager();
    public DuelManager getDuelManager() { return manager; }
  }
  public static class DuelManager {
    public DuelManager getMatchManager() { return this; }
    public boolean isInMatch(UUID id) { return true; }
  }
  @Test void duelsVetoDowningAndCannotBePunishedByAStaleDeluxeCombatTag() {
    MockBukkit.loadWith(DuelsPlugin.class, new PluginDescriptionFile("LoveDuels", "test", DuelsPlugin.class.getName()));
    assertTrue(fall().isCancelled());
    api.tag(victim, attacker, 30);
    server.getScheduler().performOneTick();
    assertFalse(api.isInCombat(victim));
    var logout = new nl.marido.deluxecombat.events.CombatlogEvent(victim, attacker, victim.getLocation());
    server.getPluginManager().callEvent(logout);
    assertTrue(logout.isCancelled());
  }
  @Test void timerDeathRestoresOriginalAttackerForDeathListeners() {
    fall(); downed.add(victim.getUniqueId());
    server.getScheduler().performTicks(2);
    server.getPluginManager().callEvent(new dev.lovelace.loveessential.api.revive.DownedDeathEvent(victim,
        dev.lovelace.loveessential.api.revive.DownedDeathEvent.Cause.TIMER));
    victim.setKiller(null);
    var death = new PlayerDeathEvent(victim, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), new ArrayList<>(), 0, (net.kyori.adventure.text.Component) null, false);
    server.getPluginManager().callEvent(death);
    assertEquals(attacker, victim.getKiller());
  }
  @Test void suicideDoesNotManufactureAKiller() {
    fall(); downed.add(victim.getUniqueId());
    server.getScheduler().performTicks(2);
    server.getPluginManager().callEvent(new dev.lovelace.loveessential.api.revive.DownedDeathEvent(victim,
        dev.lovelace.loveessential.api.revive.DownedDeathEvent.Cause.SUICIDE));
    victim.setKiller(null);
    server.getPluginManager().callEvent(new PlayerDeathEvent(victim, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), new ArrayList<>(), 0, (net.kyori.adventure.text.Component) null, false));
    assertNull(victim.getKiller());
  }
  @Test void doesNotShortenAnExistingLongerDeluxeCombatTagOnDowning() {
    api.tag(victim, attacker, 120);
    fall(); downed.add(victim.getUniqueId());
    server.getScheduler().performTicks(2);
    assertEquals(120000L, api.times.get(victim.getUniqueId()));
  }
  @Test void ordinaryAndDownedCombatLogoutIsNotSilentlyForgiven() {
    downed.add(victim.getUniqueId());
    var e = new nl.marido.deluxecombat.events.CombatlogEvent(victim, attacker, victim.getLocation());
    server.getPluginManager().callEvent(e);
    assertFalse(e.isCancelled());
    server.getServicesManager().register(AuthOracle.class, id -> false, dc, ServicePriority.Normal);
    var protectedLogout = new nl.marido.deluxecombat.events.CombatlogEvent(victim, attacker, victim.getLocation());
    server.getPluginManager().callEvent(protectedLogout);
    assertTrue(protectedLogout.isCancelled());
  }
  @Test void publishesDeluxeCombatAndRemovesOnlyItsStateOnUntag() {
    assertNotSame(upstream, state());
    api.tag(victim, attacker, 40);
    server.getScheduler().performOneTick();
    assertTrue(state().inCombat(victim.getUniqueId()));
    assertEquals("deluxecombat", state().combatSource(victim.getUniqueId()).orElseThrow());
    api.untag(victim);
    server.getScheduler().performOneTick();
    assertFalse(state().inCombat(victim.getUniqueId()));
  }
  @Test void tagsBothPlayersAfterConfirmedDowningEvenWhenDamageWasCancelled() {
    var e = fall();
    assertFalse(e.isCancelled());
    e.getCause().setCancelled(true);
    downed.add(victim.getUniqueId());
    server.getScheduler().performTicks(2);
    assertEquals(2, api.tags);
    assertTrue(state().inCombat(victim.getUniqueId()));
    assertTrue(state().inCombat(attacker.getUniqueId()));
  }
  @Test void vetoedOrUnconfirmedDowningDoesNotTag() {
    fall().setCancelled(true);
    server.getScheduler().performTicks(2);
    assertEquals(0, api.tags);
    fall();
    server.getScheduler().performTicks(2);
    assertEquals(0, api.tags);
  }
  @Test void unusedReviveMeDoesNotOverrideLoveEssentialOrRequireItsApi() {
    MockBukkit.createMockPlugin("ReviveMe");
    addon.unregister(); addon.register();
    server.getScheduler().performOneTick();
    assertNotSame(upstream, state());
    assertFalse(fall().isCancelled());
  }
  @Test void prefersExternalReviveMeOnlyWhenExplicitlyConfigured() throws Exception {
    var settings = DeluxeCombatAddon.class.getDeclaredField("config");
    settings.setAccessible(true);
    ((org.bukkit.configuration.file.YamlConfiguration) settings.get(addon))
        .set("revive.prefer-reviveme-if-installed", true);
    MockBukkit.createMockPlugin("ReviveMe");
    assertTrue(fall().isCancelled());
    server.getScheduler().performTicks(2);
    assertEquals(0, api.tags);
  }
  @Test void deniesDamageForUnauthenticatedPlayersAndRemovesTheirTag() {
    server.getServicesManager().register(AuthOracle.class, id -> !id.equals(victim.getUniqueId()), dc, ServicePriority.Normal);
    var e = new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5);
    server.getPluginManager().callEvent(e);
    assertTrue(e.isCancelled());
    api.tag(victim, attacker, 30);
    server.getScheduler().performOneTick();
    assertFalse(api.isInCombat(victim));
    assertFalse(state().inCombat(victim.getUniqueId()));
  }
  @Test void unloadRestoresOriginalServiceAndStaleDeferredFallCannotTag() {
    var original = upstream;
    fall();
    downed.add(victim.getUniqueId());
    addon.unregister();
    assertSame(original, state());
    addon.register();
    server.getScheduler().performTicks(3);
    assertEquals(0, api.tags);
  }
  @Test void clearFromAsyncConsumerIsAppliedOnTheNextServerTick() throws Exception {
    api.tag(victim, attacker, 30);
    server.getScheduler().performOneTick();
    var bridge = state();
    Thread.ofVirtual().start(() -> bridge.clear(victim.getUniqueId())).join();
    assertFalse(bridge.inCombat(victim.getUniqueId()));
    server.getScheduler().performOneTick();
    assertFalse(api.isInCombat(victim));
  }
}
