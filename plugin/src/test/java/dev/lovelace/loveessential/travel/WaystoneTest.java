package dev.lovelace.loveessential.travel;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.api.Position;
import java.util.*;
import org.junit.jupiter.api.Test;

class WaystoneTest {
  @Test
  void validatesDirectedRoutesAndImmutableCopies() {
    var pos = new Position(UUID.randomUUID(), 0, 64, 0, 90, 0);
    var routes = new ArrayList<Waystone.Route>();
    routes.add(new Waystone.Route("2", 50, 3, 30, ""));
    var n = new Waystone("1", "Спавн", pos, pos, "wood", "", routes);
    routes.clear();
    assertEquals(1, n.routes().size());
    assertThrows(UnsupportedOperationException.class, () -> n.routes().clear());
    assertThrows(IllegalArgumentException.class, () -> new Waystone.Route("2", -1, 0, 0, ""));
    assertThrows(IllegalArgumentException.class, () -> new Waystone.Route("2", 0, 301, 0, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Waystone("1", "", pos, pos, "stone", "", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Waystone(
                "1",
                "A",
                pos,
                pos,
                "stone",
                "",
                List.of(n.routes().getFirst(), n.routes().getFirst())));
  }
}
