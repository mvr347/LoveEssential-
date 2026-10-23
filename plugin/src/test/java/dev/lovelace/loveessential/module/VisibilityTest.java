package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class VisibilityTest {
  final List<String> calls = new ArrayList<>();
  final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  final Visibility visibility =
      new Visibility(
          new Visibility.Effect() {
            @Override
            public void hide(UUID viewer, UUID target) {
              calls.add("hide");
            }

            @Override
            public void show(UUID viewer, UUID target) {
              calls.add("show");
            }
          });

  @Test
  void bukkitIsCalledOnlyWhenTheHiddenStateChanges() {
    visibility.set("vanish", a, b, true);
    visibility.set("vanish", a, b, true);
    assertEquals(List.of("hide"), calls);
    visibility.set("vanish", a, b, false);
    assertEquals(List.of("hide", "show"), calls);
  }

  @Test
  void aPlayerStaysHiddenWhileAnyOtherSourceStillHoldsIt() {
    // The per-world list and vanish must not undo each other.
    visibility.set("world-list", a, b, true);
    visibility.set("vanish", a, b, true);
    visibility.set("vanish", a, b, false);
    assertTrue(visibility.hidden(a, b));
    assertEquals(List.of("hide"), calls);
    visibility.set("world-list", a, b, false);
    assertFalse(visibility.hidden(a, b));
    assertEquals(List.of("hide", "show"), calls);
  }

  @Test
  void releaseAndForgetClearOnlyWhatTheyOwn() {
    UUID c = UUID.randomUUID();
    visibility.set("world-list", a, b, true);
    visibility.set("vanish", a, c, true);
    visibility.release("world-list");
    assertFalse(visibility.hidden(a, b));
    assertTrue(visibility.hidden(a, c));
    assertFalse(visibility.holds("world-list"));
    assertTrue(visibility.holds("vanish"));
    visibility.forget(c);
    assertFalse(visibility.hidden(a, c));
  }

  @Test
  void aPlayerNeverHidesFromThemselves() {
    visibility.set("vanish", a, a, true);
    assertFalse(visibility.hidden(a, a));
    assertTrue(calls.isEmpty());
  }
}
