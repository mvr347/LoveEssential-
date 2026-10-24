package dev.lovelace.loveessential.scoreboard;

import java.text.Collator;
import java.util.*;

/**
 * Filter and sort of the line catalog, in the way of the LoveClans list screens: one button steps the
 * options forward (left click) and one backward (right click). Free of Bukkit so it can be tested.
 */
public final class CatalogView {
  /** Order of the catalog list. */
  public enum Sort {
    NAME,
    AVAILABLE,
    CATEGORY,
    ENABLED
  }

  /** One catalog line as the list sees it. */
  public record Row(
      String id, String sortName, String category, int categoryIndex, int activeIndex, boolean available) {}

  private CatalogView() {}

  /** Next (or previous) index on a ring of {@code size} options. */
  public static int step(int current, int size, boolean forward) {
    if (size <= 0) return 0;
    int next = (current + (forward ? 1 : -1)) % size;
    return next < 0 ? next + size : next;
  }

  public static <E extends Enum<E>> E step(E current, E[] options, boolean forward) {
    return options[step(current.ordinal(), options.length, forward)];
  }

  /**
   * The ids to list: {@code filter} is a category id, blank for all. Enabled lines keep the order of the
   * board when the sort is {@link Sort#ENABLED}; names sort the Russian way.
   */
  public static List<String> apply(List<Row> rows, String filter, Sort sort) {
    Collator collator = Collator.getInstance(Locale.forLanguageTag("ru"));
    Comparator<Row> byName = Comparator.comparing(Row::sortName, collator).thenComparing(Row::id);
    var shown = new ArrayList<Row>();
    for (Row row : rows) if (filter == null || filter.isBlank() || filter.equals(row.category())) shown.add(row);
    Comparator<Row> order =
        switch (sort) {
          case NAME -> byName;
          case AVAILABLE ->
              Comparator.comparing((Row r) -> !r.available() && r.activeIndex() < 0).thenComparing(byName);
          case CATEGORY -> Comparator.comparingInt(Row::categoryIndex).thenComparing(byName);
          case ENABLED ->
              Comparator.comparing((Row r) -> r.activeIndex() < 0)
                  .thenComparingInt(r -> r.activeIndex() < 0 ? 0 : r.activeIndex())
                  .thenComparing((Row r) -> !r.available())
                  .thenComparing(byName);
        };
    shown.sort(order);
    return shown.stream().map(Row::id).toList();
  }
}
