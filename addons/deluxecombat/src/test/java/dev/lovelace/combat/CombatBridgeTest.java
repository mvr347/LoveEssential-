package dev.lovelace.combat;

import static org.junit.jupiter.api.Assertions.*;
import dev.lovelace.lovecore.api.combat.CombatState;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CombatBridgeTest {
  static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
  static class State implements CombatState {
    Map<UUID, Instant> marks = new HashMap<>();
    public boolean inCombat(UUID id) { return marks.containsKey(id); }
    public Optional<Instant> combatEndsAt(UUID id) { return Optional.ofNullable(marks.get(id)); }
    public Optional<String> combatSource(UUID id) { return Optional.of("siege"); }
    public void mark(UUID id, Duration d, String s) { marks.put(id, NOW.plus(d)); }
    public void clear(UUID id) { marks.remove(id); }
  }
  @Test void externalUntagDoesNotLeaveAnInferredFifteenSecondTail() {
    var b = new CombatBridge(new State(), Clock.fixed(NOW, ZoneOffset.UTC), id -> {});
    UUID id = UUID.randomUUID();
    b.publish(Map.of(id, new CombatBridge.Entry(NOW.plusSeconds(15), "deluxecombat")));
    assertTrue(b.inCombat(id));
    b.publish(Map.of());
    assertFalse(b.inCombat(id));
    assertTrue(b.combatEndsAt(id).isEmpty());
  }
  @Test void independentSiegeSurvivesDeluxeCombatUntag() {
    var state = new State();
    var b = new CombatBridge(state, Clock.fixed(NOW, ZoneOffset.UTC), id -> {});
    UUID id = UUID.randomUUID();
    b.mark(id, Duration.ofSeconds(120), "siege");
    b.publish(Map.of(id, new CombatBridge.Entry(NOW.plusSeconds(15), "deluxecombat")));
    assertEquals("siege", b.combatSource(id).orElseThrow());
    b.publish(Map.of());
    assertTrue(b.inCombat(id));
    assertTrue(state.inCombat(id));
  }
  @Test void explicitClearAlsoClearsExternalProvider() {
    var state = new State();
    var cleared = new ArrayList<UUID>();
    var b = new CombatBridge(state, Clock.fixed(NOW, ZoneOffset.UTC), cleared::add);
    UUID id = UUID.randomUUID();
    b.mark(id, Duration.ofSeconds(50), "raid");
    b.publish(Map.of(id, new CombatBridge.Entry(NOW.plusSeconds(100), "deluxecombat")));
    b.clear(id);
    assertFalse(b.inCombat(id));
    assertFalse(state.inCombat(id));
    assertEquals(List.of(id), cleared);
  }
  @Test void expiresAndDiscardsUnchangedSnapshotsSafely() {
    var b = new CombatBridge(new State(), Clock.fixed(NOW, ZoneOffset.UTC), id -> {});
    UUID id = UUID.randomUUID();
    var snapshot = new HashMap<UUID, CombatBridge.Entry>();
    snapshot.put(id, new CombatBridge.Entry(NOW.minusSeconds(1), "deluxecombat"));
    b.publish(snapshot);
    snapshot.clear();
    assertFalse(b.inCombat(id));
    b.mark(id, Duration.ofSeconds(20), "custom");
    b.forget(id);
    assertFalse(b.inCombat(id));
    b.mark(id, Duration.ofSeconds(20), "custom");
    b.close();
    assertFalse(b.inCombat(id));
  }
  @Test void clearCannotBeUndoneByASnapshotAlreadyBeingCollected() {
    var b = new CombatBridge(new State(), Clock.fixed(NOW, ZoneOffset.UTC), id -> {});
    UUID id = UUID.randomUUID();
    var old = Map.of(id, new CombatBridge.Entry(NOW.plusSeconds(50), "deluxecombat"));
    b.publish(old);
    b.clear(id);
    b.publish(old);
    assertFalse(b.inCombat(id));
    b.publish(Map.of()); // API has acknowledged untag.
    b.publish(old); // A genuinely new fight may start afterwards.
    assertTrue(b.inCombat(id));
  }
  @Test void ignoresInvalidMarksAndStaleFallbackSources() {
    var b = new CombatBridge(new State(), Clock.fixed(NOW, ZoneOffset.UTC), id -> {});
    UUID id = UUID.randomUUID();
    b.mark(id, Duration.ZERO, "pvp");
    b.mark(id, Duration.ofSeconds(-1), "pvp");
    b.mark(null, Duration.ofSeconds(10), "pvp");
    assertFalse(b.inCombat(id));
    for (String source : List.of("pvp", "DeluxeCombat", "in_combat")) assertFalse(CombatBridge.inheritedSource(source));
    assertTrue(CombatBridge.inheritedSource("siege"));
  }
}
