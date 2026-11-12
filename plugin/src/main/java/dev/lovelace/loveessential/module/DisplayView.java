package dev.lovelace.loveessential.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** What the display menu shows: a filter button and a separate sort button, like the other personalization menus. */
public final class DisplayView {
  private DisplayView() {}

  public enum Filter {
    ALL("all"),
    /** Only what other players currently see. */
    ON("on"),
    /** Only what is hidden. */
    OFF("off");

    private final String id;

    Filter(String id) {
      this.id = id;
    }

    /** Suffix of the {@code display.filter-<id>} text. */
    public String id() {
      return id;
    }
  }

  public enum Sort {
    /** Tag by tag: clan then mark, each on chat, tab and nametag. */
    ELEMENT("element"),
    /** Place by place: chat, tab and nametag, each for clan and mark. */
    SURFACE("surface");

    private final String id;

    Sort(String id) {
      this.id = id;
    }

    /** Suffix of the {@code display.sort-<id>} text. */
    public String id() {
      return id;
    }
  }

  public static <T> List<T> apply(
      Filter filter,
      Sort sort,
      List<T> entries,
      Predicate<T> shown,
      ToIntFunction<T> element,
      ToIntFunction<T> surface) {
    var out = new ArrayList<T>();
    for (T entry : entries) {
      boolean on = shown.test(entry);
      if (filter == Filter.ALL || filter == Filter.ON && on || filter == Filter.OFF && !on)
        out.add(entry);
    }
    Comparator<T> byElement =
        Comparator.comparingInt(element).thenComparingInt(surface);
    Comparator<T> bySurface =
        Comparator.comparingInt(surface).thenComparingInt(element);
    out.sort(sort == Sort.SURFACE ? bySurface : byElement);
    return out;
  }
}
