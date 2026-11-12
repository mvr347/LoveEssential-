package dev.lovelace.loveessential.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What the join/quit menu shows: a filter button and a separate sort button, as in the clan lists (owner request
 * 2026-11-12). No server types, so it is tested on its own.
 */
public final class JoinQuitView {
  private JoinQuitView() {}

  public enum Filter {
    ALL("all"),
    /** Only what the player can pick right now. */
    AVAILABLE("available"),
    /** Only what is still locked (for example behind an achievement). */
    LOCKED("locked");

    private final String id;

    Filter(String id) {
      this.id = id;
    }

    /** Suffix of the {@code joinquit.filter-<id>} text. */
    public String id() {
      return id;
    }
  }

  public enum Sort {
    /** Catalogue order. */
    CATALOG("catalog"),
    NAME("name");

    private final String id;

    Sort(String id) {
      this.id = id;
    }

    /** Suffix of the {@code joinquit.sort-<id>} text. */
    public String id() {
      return id;
    }
  }

  public static <T> List<T> apply(
      Filter filter, Sort sort, List<T> catalogue, Predicate<T> open, Function<T, String> name) {
    var out = new ArrayList<T>();
    for (T style : catalogue) {
      boolean allowed = open.test(style);
      switch (filter) {
        case AVAILABLE -> {
          if (allowed) out.add(style);
        }
        case LOCKED -> {
          if (!allowed) out.add(style);
        }
        default -> out.add(style);
      }
    }
    if (sort == Sort.NAME)
      out.sort(Comparator.comparing(s -> name.apply(s).toLowerCase(java.util.Locale.ROOT)));
    return out;
  }
}
