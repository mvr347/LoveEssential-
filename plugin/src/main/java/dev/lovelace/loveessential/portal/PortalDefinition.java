package dev.lovelace.loveessential.portal;

import dev.lovelace.loveessential.api.Action;
import dev.lovelace.loveessential.api.Position;
import java.util.*;

public record PortalDefinition(
    String id,
    String nameKey,
    Position first,
    Position second,
    Position destination,
    boolean enabled,
    String permission,
    List<String> gamemodes,
    long cooldownSeconds,
    int warmupTicks,
    boolean blockCombat,
    boolean allowVehicles,
    String particle,
    String color,
    boolean fill,
    double spacing,
    List<Action> actions) {
  public PortalDefinition {
    gamemodes = List.copyOf(gamemodes);
    actions = List.copyOf(actions);
  }

  public boolean contains(Position p) {
    return first.world().equals(p.world())
        && p.x() >= Math.min(first.x(), second.x())
        && p.x() < Math.max(first.x(), second.x()) + 1
        && p.y() >= Math.min(first.y(), second.y())
        && p.y() < Math.max(first.y(), second.y()) + 1
        && p.z() >= Math.min(first.z(), second.z())
        && p.z() < Math.max(first.z(), second.z()) + 1;
  }

  public double volume() {
    return (Math.abs(first.x() - second.x()) + 1)
        * (Math.abs(first.y() - second.y()) + 1)
        * (Math.abs(first.z() - second.z()) + 1);
  }
}
