package dev.lovelace.loveessential.travel;

import dev.lovelace.loveessential.api.Position;
import java.util.*;

/** Immutable SQLite payload. Route prices belong to the origin and may be asymmetric. */
public record Waystone(
    String id,
    String name,
    Position anchor,
    Position arrival,
    String style,
    String model,
    List<Route> routes) {
  public record Route(String target, long cost, int warmup, int cooldown, String permission) {
    public Route {
      if (target == null
          || !target.matches("[1-9][0-9]{0,2}")
          || cost < 0
          || cost > 1_000_000_000L
          || warmup < 0
          || warmup > 300
          || cooldown < 0
          || cooldown > 86400
          || permission == null
          || permission.length() > 128) throw new IllegalArgumentException("waystones.invalid");
    }
  }

  public Waystone {
    if (id == null
        || !id.matches("[1-9][0-9]{0,2}")
        || name == null
        || name.isBlank()
        || name.length() > 64
        || anchor == null
        || arrival == null
        || !Set.of("wood", "stone", "rune", "itemsadder").contains(style)
        || model == null
        || model.length() > 128
        || routes == null
        || routes.size() > 32) throw new IllegalArgumentException("waystones.invalid");
    routes = List.copyOf(routes);
    if (routes.stream().map(Route::target).distinct().count() != routes.size())
      throw new IllegalArgumentException("waystones.invalid");
  }
}
