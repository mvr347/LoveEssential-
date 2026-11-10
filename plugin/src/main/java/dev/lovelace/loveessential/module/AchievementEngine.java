package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.module.AchievementCatalog.Trigger;
import java.util.*;

/**
 * The rules of the achievements, free of the server: given what a player has done so far, which achievements come
 * true after one more fact. All state of one player is {@link Progress}; the engine only changes it.
 */
public final class AchievementEngine {
  private final AchievementCatalog catalog;

  public AchievementEngine(AchievementCatalog catalog) {
    this.catalog = catalog;
  }

  /** Everything the engine knows about one player; mutable, touched on the main thread only. */
  public static final class Progress {
    /** Achievement id to the time it was unlocked (epoch milliseconds). */
    public final Map<String, Long> unlocked = new LinkedHashMap<>();

    public final Map<String, Double> counters = new HashMap<>();
    public final Map<String, Set<String>> sets = new HashMap<>();

    public double counter(String key) {
      return counters.getOrDefault(key, 0.0);
    }
  }

  /** Adds {@code delta} to a counter; returns the achievements that became true, in unlock order. */
  public List<String> add(Progress progress, String key, double delta, long now) {
    if (!(delta > 0) || !Double.isFinite(delta)) return List.of();
    progress.counters.merge(key, delta, Double::sum);
    return settle(progress, now);
  }

  /** Sets a counter to an exact value (a rating, a balance). */
  public List<String> set(Progress progress, String key, double value, long now) {
    if (!Double.isFinite(value)) return List.of();
    progress.counters.put(key, value);
    return settle(progress, now);
  }

  /** Adds a value to a set; a repeated value changes nothing. */
  public List<String> collect(Progress progress, String key, String value, long now) {
    if (value == null || value.isBlank()) return List.of();
    if (!progress.sets.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(value)) return List.of();
    return settle(progress, now);
  }

  /** Unlocks one achievement by id (the plugin that owns the condition decided); unknown or owned gives nothing. */
  public List<String> unlock(Progress progress, String id, long now) {
    if (!catalog.contains(id) || progress.unlocked.containsKey(id)) return List.of();
    var result = new ArrayList<String>();
    progress.unlocked.put(id, now);
    result.add(id);
    result.addAll(settle(progress, now));
    return result;
  }

  /** Re-checks every achievement; used after loading old data and after the catalogue changed. */
  public List<String> settle(Progress progress, long now) {
    var result = new ArrayList<String>();
    boolean changed;
    int rounds = 0;
    do {
      changed = false;
      for (var def : catalog.all()) {
        if (progress.unlocked.containsKey(def.id())) continue;
        if (met(progress, def.trigger())) {
          progress.unlocked.put(def.id(), now);
          result.add(def.id());
          changed = true;
        }
      }
      // A meta achievement can depend on another meta one, so repeat until nothing new comes true.
    } while (changed && ++rounds < 16);
    return result;
  }

  private boolean met(Progress progress, Trigger trigger) {
    return switch (trigger) {
      case Trigger.Manual manual -> false;
      case Trigger.Counter c -> progress.counter(c.key()) >= c.atLeast();
      case Trigger.Collected c ->
          progress.sets.getOrDefault(c.key(), Set.of()).containsAll(c.values());
      case Trigger.AllOf all -> progress.unlocked.keySet().containsAll(all.ids());
      case Trigger.UnlockedCount count -> countedUnlocks(progress) >= count.atLeast();
    };
  }

  private long countedUnlocks(Progress progress) {
    return progress.unlocked.keySet().stream()
        .filter(id -> catalog.find(id).map(d -> isCounted(d.trigger())).orElse(false))
        .count();
  }

  private static boolean isCounted(Trigger trigger) {
    return !(trigger instanceof Trigger.AllOf) && !(trigger instanceof Trigger.UnlockedCount);
  }
}
