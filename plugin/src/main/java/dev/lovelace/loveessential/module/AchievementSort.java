package dev.lovelace.loveessential.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Sort order of the achievements menu: its own header button, separate from the section filter. */
public final class AchievementSort {
  private AchievementSort() {}

  public enum Sort {
    /** Catalogue order. */
    DEFAULT("default"),
    /** Rarest first. */
    RARITY("rarity"),
    NAME("name"),
    /** The ones the player owns first. */
    OWNED("owned");

    private final String id;

    Sort(String id) {
      this.id = id;
    }

    /** Suffix of the {@code achievements.sort-<id>} text. */
    public String id() {
      return id;
    }
  }

  /** Stable: entries that compare equal keep the catalogue order. {@code rarity} grows with rarity. */
  public static <T> List<T> apply(
      Sort sort,
      List<T> entries,
      Predicate<T> owned,
      ToIntFunction<T> rarity,
      Function<T, String> name) {
    var out = new ArrayList<>(entries);
    switch (sort) {
      case RARITY -> out.sort(Comparator.comparingInt((T e) -> -rarity.applyAsInt(e)));
      case NAME ->
          out.sort(Comparator.comparing(e -> name.apply(e).toLowerCase(java.util.Locale.ROOT)));
      case OWNED -> out.sort(Comparator.comparingInt((T e) -> owned.test(e) ? 0 : 1));
      default -> {}
    }
    return out;
  }
}
