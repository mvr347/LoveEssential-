package dev.lovelace.loveessential.tab;

import java.util.*;

public final class TabSort {
  public record Entry(UUID id, String name, int group, Map<String, String> values) {
    public Entry {
      values = Map.copyOf(values);
    }
  }

  public static Comparator<Entry> comparator(List<String> keys) {
    Comparator<Entry> c = (a, b) -> 0;
    for (String key : keys) {
      Comparator<Entry> next;
      if (key.equals("group")) next = Comparator.comparingInt(Entry::group);
      else if (key.equals("name"))
        next = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
      else if (key.startsWith("number-desc:"))
        next =
            Comparator.<Entry>comparingDouble(e -> number(e.values().get(key.substring(12))))
                .reversed();
      else if (key.startsWith("number:"))
        next = Comparator.comparingDouble(e -> number(e.values().get(key.substring(7))));
      else
        next =
            Comparator.comparing(
                e -> e.values().getOrDefault(key, ""), String.CASE_INSENSITIVE_ORDER);
      c = c.thenComparing(next);
    }
    return c.thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id);
  }

  private static double number(String n) {
    try {
      double v = Double.parseDouble(n);
      return Double.isFinite(v) ? v : 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
