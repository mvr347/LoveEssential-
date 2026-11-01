package dev.lovelace.loveessential.portal;

import dev.lovelace.loveessential.api.*;
import java.util.List;

/** Short-lived main-thread edit; the stored value remains an immutable PortalDefinition. */
final class PortalEdit {
  private final PortalDefinition original;
  String name, particle, color;
  Position first, second, destination;
  boolean fill;
  double spacing;
  List<Action> actions;

  PortalEdit(PortalDefinition d) {
    original = d;
    name = d.nameKey();
    particle = d.particle();
    color = d.color();
    first = d.first();
    second = d.second();
    destination = d.destination();
    fill = d.fill();
    spacing = d.spacing();
    actions = d.actions();
  }

  PortalDefinition build() {
    return new PortalDefinition(
        original.id(),
        name,
        first,
        second,
        destination,
        original.enabled(),
        original.permission(),
        original.gamemodes(),
        original.cooldownSeconds(),
        original.warmupTicks(),
        original.blockCombat(),
        original.allowVehicles(),
        particle,
        color,
        fill,
        spacing,
        actions);
  }
}
