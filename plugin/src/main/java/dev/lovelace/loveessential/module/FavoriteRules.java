package dev.lovelace.loveessential.module;

import java.util.ArrayList;
import java.util.List;

/** Pure rules for the favourite achievements shown on a profile; no server types, tested on their own. */
public final class FavoriteRules {
  private FavoriteRules() {}

  public enum Result {
    ADDED,
    REMOVED,
    FULL
  }

  public record Toggled(List<String> favorites, Result result) {}

  /** Saved form: ids separated by commas, in display order. Blank and duplicate ids are dropped. */
  public static List<String> parse(String saved, int max) {
    var out = new ArrayList<String>();
    if (saved == null) return out;
    for (String id : saved.split(",")) {
      String trimmed = id.strip();
      if (!trimmed.isEmpty() && !out.contains(trimmed) && out.size() < max) out.add(trimmed);
    }
    return out;
  }

  public static String serialize(List<String> favorites) {
    return String.join(",", favorites);
  }

  /** Adds the id, or removes it if it is already a favourite; a full list refuses a new one. */
  public static Toggled toggle(List<String> current, String id, int max) {
    var next = new ArrayList<>(current);
    if (next.remove(id)) return new Toggled(List.copyOf(next), Result.REMOVED);
    if (next.size() >= max) return new Toggled(List.copyOf(current), Result.FULL);
    next.add(id);
    return new Toggled(List.copyOf(next), Result.ADDED);
  }
}
