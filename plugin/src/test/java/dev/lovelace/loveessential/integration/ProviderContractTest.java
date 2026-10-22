package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.api.AfkChangeEvent;
import dev.lovelace.loveessential.api.LoveEssentialReadyEvent;
import dev.lovelace.loveessential.api.NpcInteractEvent;
import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.npc.NpcClick;
import dev.lovelace.lovecore.api.npc.NpcClickEvent;
import dev.lovelace.lovecore.api.npc.NpcProvider;
import dev.lovelace.lovecore.api.npc.NpcSpec;
import dev.lovelace.lovecore.api.presence.PresenceOracle;
import dev.lovelace.lovecore.api.presence.SkinTexture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

/** The LoveCore contracts LoveEssential implements: NPC provider, presence and the ready event. */
class ProviderContractTest implements Listener {
  ServerMock server;
  LoveEssentialPlugin plugin;
  final List<Object> seen = new ArrayList<>();

  @EventHandler
  public void ready(LoveEssentialReadyEvent e) {
    seen.add(e);
  }

  @EventHandler
  public void afk(AfkChangeEvent e) {
    seen.add(e);
  }

  @EventHandler
  public void click(NpcClickEvent e) {
    seen.add(e);
  }

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    server.getPluginManager().registerEvents(this, MockBukkit.createMockPlugin("Observer"));
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    var board = plugin.module("scoreboard", false);
    waitFor(board::isDone);
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

  NpcProvider provider() {
    return LoveCore.provider(NpcProvider.class, "loveessential").orElseThrow();
  }

  Location here() {
    return new Location(server.getWorld("world"), 10, 70, 10);
  }

  @Test
  void theReadyEventIsFiredOnceTheServiceIsUsable() {
    assertTrue(seen.stream().anyMatch(LoveEssentialReadyEvent.class::isInstance));
    var event = (LoveEssentialReadyEvent) seen.stream().filter(LoveEssentialReadyEvent.class::isInstance).findFirst().get();
    assertNotNull(event.getService());
  }

  @Test
  void bothContractsAreFoundByNameAndOnlyByTheirOwnName() {
    assertEquals("loveessential", provider().providerId());
    assertTrue(LoveCore.provider(NpcProvider.class, "citizens").isEmpty());
    assertEquals("loveessential", LoveCore.provider(PresenceOracle.class, "loveessential").orElseThrow().providerId());
    assertTrue(LoveCore.provider(PresenceOracle.class, "cmi").isEmpty());
  }

  @Test
  void aCreatedNpcIsStoredWithItsNameSkinAndTagsAndIsFoundByKey() throws Exception {
    var spec =
        NpcSpec.at(Component.text("Smith"), here())
            .withType(EntityType.VILLAGER)
            .withSkinName("Notch")
            .withLookClose(true)
            .withTags(Map.of("owner", "loveshop", "kind", "stall"));
    var handle = provider().create(spec);
    assertTrue(handle.key().startsWith("ext-"));
    assertEquals("Smith", handle.name());
    assertEquals("loveshop", handle.tag("owner").orElseThrow(), "reads see the write at once");
    var api = server.getServicesManager().load(dev.lovelace.loveessential.api.LoveEssentialService.class);
    waitFor(() -> api.npc(handle.key()).isPresent());
    var stored = api.npc(handle.key()).orElseThrow();
    assertEquals("VILLAGER", stored.entityType());
    assertEquals("Notch", stored.settings().get("skin"));
    assertEquals("stall", stored.settings().get("tag.kind"));
    assertEquals("true", stored.settings().get("look-at"));
    assertTrue(provider().byKey(handle.key()).isPresent());
    assertTrue(provider().byKey("missing").isEmpty());
  }

  @Test
  void quickConsecutiveChangesAreAllSavedWithoutRevisionConflicts() throws Exception {
    var handle = provider().create(NpcSpec.at(Component.text("A"), here()).withType(EntityType.VILLAGER));
    handle.setName(Component.text("B"));
    handle.tag("one", "1");
    handle.tag("two", "2");
    handle.lookClose(true);
    var api = server.getServicesManager().load(dev.lovelace.loveessential.api.LoveEssentialService.class);
    waitFor(
        () ->
            api.npc(handle.key())
                .map(n -> "2".equals(n.settings().get("tag.two")) && "true".equals(n.settings().get("look-at")))
                .orElse(false));
    var stored = api.npc(handle.key()).orElseThrow();
    assertEquals("1", stored.settings().get("tag.one"));
    assertEquals("@inline:B", stored.nameKey());
    handle.tag("one", null);
    waitFor(() -> api.npc(handle.key()).map(n -> !n.settings().containsKey("tag.one")).orElse(false));
  }

  @Test
  void removingDeletesTheNpc() throws Exception {
    var handle = provider().create(NpcSpec.at(Component.text("Gone"), here()).withType(EntityType.VILLAGER));
    var api = server.getServicesManager().load(dev.lovelace.loveessential.api.LoveEssentialService.class);
    waitFor(() -> api.npc(handle.key()).isPresent());
    assertTrue(provider().remove(handle.key()));
    waitFor(() -> api.npc(handle.key()).isEmpty());
    assertFalse(provider().remove(handle.key()));
  }

  @Test
  void clicksAreRelayedAsTheSharedEventAndItsCancellationReaches() throws Exception {
    var handle = provider().create(NpcSpec.at(Component.text("Clicked"), here()).withType(EntityType.VILLAGER));
    var api = server.getServicesManager().load(dev.lovelace.loveessential.api.LoveEssentialService.class);
    waitFor(() -> api.npc(handle.key()).isPresent());
    var player = server.addPlayer("Clicker");
    var event = new NpcInteractEvent(player, api.npc(handle.key()).orElseThrow(), NpcInteractEvent.Click.LEFT, null);
    server.getPluginManager().callEvent(event);
    var relayed =
        seen.stream().filter(NpcClickEvent.class::isInstance).map(NpcClickEvent.class::cast).findFirst().orElseThrow();
    assertEquals(NpcClick.LEFT, relayed.click());
    assertEquals(handle.key(), relayed.npc().key());
    assertEquals("loveessential", relayed.npc().providerId());
    assertFalse(event.isCancelled());
  }

  @Test
  void presenceReportsVanishAfkAndTheOwnNickname() throws Exception {
    var oracle = LoveCore.provider(PresenceOracle.class, "loveessential").orElseThrow();
    var player = server.addPlayer("Presence");
    waitFor(() -> true);
    assertFalse(oracle.vanished(player.getUniqueId()));
    assertFalse(oracle.afk(player.getUniqueId()));
    assertTrue(oracle.lastActivity(player.getUniqueId()).isPresent());
    assertTrue(oracle.displayName(player.getUniqueId()).isEmpty(), "no nickname, no override");
    assertFalse(oracle.vanished(java.util.UUID.randomUUID()));
    assertTrue(oracle.lastActivity(java.util.UUID.randomUUID()).isEmpty());
  }

  @Test
  void goingAfkAndBackFiresOneEventEach() throws Exception {
    var player = server.addPlayer("Idle");
    server.getScheduler().performTicks(25);
    server.dispatchCommand(player, "afk");
    server.getScheduler().performTicks(25);
    server.dispatchCommand(player, "afk");
    server.getScheduler().performTicks(25);
    var events = seen.stream().filter(AfkChangeEvent.class::isInstance).map(AfkChangeEvent.class::cast).toList();
    assertEquals(List.of(true, false), events.stream().map(AfkChangeEvent::isAfk).toList());
  }

  @Test
  void despawnAndSpawnReportTheWorldStateAndHidingTwiceIsHarmless() throws Exception {
    var handle = provider().create(NpcSpec.at(Component.text("Shy"), here()).withType(EntityType.VILLAGER));
    var api = server.getServicesManager().load(dev.lovelace.loveessential.api.LoveEssentialService.class);
    waitFor(() -> api.npc(handle.key()).isPresent());
    assertFalse(handle.despawn(), "nothing was in the world, so despawn reports false");
    assertFalse(handle.despawn(), "hiding a hidden NPC again is harmless");
    assertFalse(handle.spawn(), "no player is near, so the NPC is not in the world yet");
    assertFalse(handle.spawned());
  }

  @Test
  void skinNameReadsTheNameSkinSettingAndIsEmptyForTextures() throws Exception {
    var named = provider().create(NpcSpec.at(Component.text("Named"), here()).withType(EntityType.VILLAGER).withSkinName("Notch"));
    var plain = provider().create(NpcSpec.at(Component.text("Plain"), here()).withType(EntityType.VILLAGER));
    assertEquals(java.util.Optional.of("Notch"), named.skinName());
    assertTrue(plain.skinName().isEmpty());
    named.setSkin(new SkinTexture("texture-value", "signature"));
    waitFor(() -> named.skinName().isEmpty());
    assertTrue(named.skinName().isEmpty(), "a texture skin has no nickname");
    named.setSkin("Dinnerbone");
    waitFor(() -> "Dinnerbone".equals(named.skinName().orElse(null)));
    assertEquals(java.util.Optional.of("Dinnerbone"), named.skinName());
  }
}
