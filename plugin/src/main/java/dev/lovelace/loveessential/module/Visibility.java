package dev.lovelace.loveessential.module;

import java.util.*;

/**
 * One arbiter for "viewer must not see target" decisions. Each source (vanish, the loading guard,
 * the per-world player list) owns its own reason; the player is hidden while any reason holds, and
 * shown again only when the last one is released. Without this, one source would undo another's
 * {@code hidePlayer} call. Main thread only.
 */
public final class Visibility {
  /** Applies a transition to Bukkit: {@code hide} or {@code show} for one viewer and target. */
  public interface Effect {
    void hide(UUID viewer, UUID target);

    void show(UUID viewer, UUID target);
  }

  private final Effect effect;
  private final Map<UUID, Map<UUID, Set<String>>> reasons = new HashMap<>();

  public Visibility(Effect effect) {
    this.effect = Objects.requireNonNull(effect);
  }

  /** Adds or removes one reason; Bukkit is called only when the hidden state actually changes. */
  public void set(String reason, UUID viewer, UUID target, boolean hidden) {
    Objects.requireNonNull(reason);
    if (viewer.equals(target)) return;
    boolean before = hidden(viewer, target);
    var byTarget = reasons.computeIfAbsent(viewer, k -> new HashMap<>());
    var set = byTarget.computeIfAbsent(target, k -> new TreeSet<>());
    if (hidden) set.add(reason);
    else set.remove(reason);
    if (set.isEmpty()) byTarget.remove(target);
    if (byTarget.isEmpty()) reasons.remove(viewer);
    boolean after = hidden(viewer, target);
    if (before != after) {
      if (after) effect.hide(viewer, target);
      else effect.show(viewer, target);
    }
  }

  public boolean hidden(UUID viewer, UUID target) {
    var byTarget = reasons.get(viewer);
    return byTarget != null && byTarget.containsKey(target);
  }

  /** Whether any viewer currently holds this reason against anyone. */
  public boolean holds(String reason) {
    return reasons.values().stream()
        .anyMatch(byTarget -> byTarget.values().stream().anyMatch(set -> set.contains(reason)));
  }

  /** Releases one reason everywhere, for example when its module is switched off. */
  public void release(String reason) {
    for (var viewer : List.copyOf(reasons.keySet()))
      for (var target : List.copyOf(reasons.getOrDefault(viewer, Map.of()).keySet()))
        set(reason, viewer, target, false);
  }

  /** Drops every pair that mentions a player who left; no Bukkit calls are needed for them. */
  public void forget(UUID player) {
    reasons.remove(player);
    for (var byTarget : reasons.values()) byTarget.remove(player);
    reasons.values().removeIf(Map::isEmpty);
  }
}
