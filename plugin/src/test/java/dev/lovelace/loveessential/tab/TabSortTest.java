package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class TabSortTest {
  TabSort.Entry e(String name, int group, String score) {
    return new TabSort.Entry(
        UUID.nameUUIDFromBytes(name.getBytes()), name, group, Map.of("score", score));
  }

  @Test
  void sortingRespectsGroupsThenNumericValuesThenStableNames() {
    var a = e("Alice", 10, "9");
    var b = e("Bob", 10, "100");
    var admin = e("Owner", 0, "1");
    var list = new ArrayList<>(List.of(a, admin, b));
    list.sort(TabSort.comparator(List.of("group", "number-desc:score", "name")));
    assertEquals(List.of(admin, b, a), list);
  }

  @Test
  void badOrNonFinitePlaceholdersDoNotBreakSorting() {
    var a = e("Alice", 0, "NaN");
    var b = e("Bob", 0, "invalid");
    var c = e("Carl", 0, "Infinity");
    var list = new ArrayList<>(List.of(c, b, a));
    list.sort(TabSort.comparator(List.of("number:score")));
    assertEquals(List.of(a, b, c), list);
  }
}
