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
    List<Route> routes,
    double size) {
  /** Largest per-stone multiplier; the render profile scale multiplies it again. */
  public static final double MAX_SIZE = 5;

  public static final double MIN_SIZE = .25;

  public record Route(String target, long cost, int warmup, int cooldown, String permission) {
    public Route {
      if (target == null
          || (!target.matches("[1-9][0-9]{0,9}") || Long.parseLong(target) > Integer.MAX_VALUE)
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

  /** Stones saved before sizes existed have no size and keep their old look. */
  public Waystone(
      String id,
      String name,
      Position anchor,
      Position arrival,
      String style,
      String model,
      List<Route> routes) {
    this(id, name, anchor, arrival, style, model, routes, 1);
  }

  /** Multiplier applied to the whole model; 1 for legacy records that stored none (0). */
  public double factor() {
    return size > 0 ? size : 1;
  }

  public Waystone withArrival(Position value) {
    return new Waystone(id, name, anchor, value, style, model, routes, size);
  }

  public Waystone withAnchor(Position value) {
    return new Waystone(id, name, value, arrival, style, model, routes, size);
  }

  public Waystone withRoutes(List<Route> value) {
    return new Waystone(id, name, anchor, arrival, style, model, value, size);
  }

  public Waystone withAppearance(
      String newName, String newStyle, String newModel, double newSize, Position newArrival) {
    return new Waystone(id, newName, anchor, newArrival, newStyle, newModel, routes, newSize);
  }

  public Waystone {
    if (size != 0 && !(size >= MIN_SIZE && size <= MAX_SIZE))
      throw new IllegalArgumentException("waystones.size-invalid");
    if (id == null
        || (!id.matches("[1-9][0-9]{0,9}") || Long.parseLong(id) > Integer.MAX_VALUE)
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
