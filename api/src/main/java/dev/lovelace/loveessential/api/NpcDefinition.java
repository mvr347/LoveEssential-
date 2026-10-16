package dev.lovelace.loveessential.api;

import java.util.*;

public record NpcDefinition(
    String id,
    long revision,
    UUID owner,
    String nameKey,
    String entityType,
    Position spawn,
    Map<String, String> settings,
    Condition visibility,
    Condition access,
    Route route,
    Dialog dialog,
    List<Action> actions,
    boolean enabled) {
  public NpcDefinition {
    settings = Map.copyOf(settings);
    actions = List.copyOf(actions);
  }

  public NpcDefinition revision(long r) {
    return new NpcDefinition(
        id,
        r,
        owner,
        nameKey,
        entityType,
        spawn,
        settings,
        visibility,
        access,
        route,
        dialog,
        actions,
        enabled);
  }

  public String option(String key, String fallback) {
    return settings.getOrDefault(key, fallback);
  }

  public boolean flag(String key, boolean fallback) {
    return Boolean.parseBoolean(option(key, Boolean.toString(fallback)));
  }

  public double number(String key, double fallback) {
    return Double.parseDouble(option(key, Double.toString(fallback)));
  }
}
