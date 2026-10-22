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

  /** Definition with every optional part at its default: no route, dialog or actions. */
  public static NpcDefinition create(
      String id, UUID owner, String nameKey, String entityType, Position spawn) {
    return new NpcDefinition(
        id,
        0,
        owner,
        nameKey,
        entityType,
        spawn,
        Map.of(),
        Condition.always(),
        Condition.always(),
        Route.empty(),
        Dialog.empty(),
        List.of(),
        true);
  }

  public NpcDefinition withSettings(Map<String, String> settings) {
    return new NpcDefinition(
        id,
        revision,
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

  /** Copy with one setting set; a null value removes it. */
  public NpcDefinition withSetting(String key, String value) {
    var copy = new HashMap<>(settings);
    if (value == null) copy.remove(key);
    else copy.put(key, value);
    return withSettings(copy);
  }

  public NpcDefinition withNameKey(String key) {
    return new NpcDefinition(
        id,
        revision,
        owner,
        key,
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

  public NpcDefinition withSpawn(Position position) {
    return new NpcDefinition(
        id,
        revision,
        owner,
        nameKey,
        entityType,
        position,
        settings,
        visibility,
        access,
        route,
        dialog,
        actions,
        enabled);
  }
}
