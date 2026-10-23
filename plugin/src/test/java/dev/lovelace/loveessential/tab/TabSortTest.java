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

  @Test
  void permissionsRankPlayersByTheFirstListedPermissionTheyHold() {
    var a = new TabSort.Entry(UUID.randomUUID(), "Anna", 0, Map.of(), Set.of("vip.gold"));
    var b = new TabSort.Entry(UUID.randomUUID(), "Boris", 0, Map.of(), Set.of("vip.silver", "vip.gold"));
    var c = new TabSort.Entry(UUID.randomUUID(), "Carl", 0, Map.of(), Set.of());
    var list = new ArrayList<>(List.of(c, b, a));
    list.sort(TabSort.comparator(List.of("permissions:vip.silver,vip.gold")));
    assertEquals(List.of("Boris", "Anna", "Carl"), list.stream().map(TabSort.Entry::name).toList());
  }

  @Test
  void placeholderValuesRankInTheListedOrderAndUnlistedLast() {
    var a = new TabSort.Entry(UUID.randomUUID(), "A", 0, Map.of("%world%", "nether"));
    var b = new TabSort.Entry(UUID.randomUUID(), "B", 0, Map.of("%world%", "world"));
    var c = new TabSort.Entry(UUID.randomUUID(), "C", 0, Map.of("%world%", "end"));
    var list = new ArrayList<>(List.of(a, c, b));
    list.sort(TabSort.comparator(List.of("placeholder:%world%:world,nether")));
    assertEquals(List.of("B", "A", "C"), list.stream().map(TabSort.Entry::name).toList());
    assertEquals("%world%", TabSort.placeholderOf("placeholder:%world%:world,nether"));
    assertEquals(List.of("world", "nether"), TabSort.permissionsOf("permissions:world,nether").subList(0, 2));
  }

  @Test
  void zAToAReversesNamesAndCaseSensitivityIsOptIn() {
    var lower = new TabSort.Entry(UUID.randomUUID(), "bob", 0, Map.of());
    var upper = new TabSort.Entry(UUID.randomUUID(), "Alice", 0, Map.of());
    var list = new ArrayList<>(List.of(lower, upper));
    list.sort(TabSort.comparator(List.of("z-a")));
    assertEquals(List.of("bob", "Alice"), list.stream().map(TabSort.Entry::name).toList());
    // Case-insensitive by default: "a" before "B" in plain a-z order.
    list.sort(TabSort.comparator(List.of("a-z")));
    assertEquals(List.of("Alice", "bob"), list.stream().map(TabSort.Entry::name).toList());
    var names = new ArrayList<>(List.of(new TabSort.Entry(UUID.randomUUID(), "b", 0, Map.of("t", "x")), new TabSort.Entry(UUID.randomUUID(), "a", 0, Map.of("t", "B"))));
    names.sort(TabSort.comparator(List.of("t", "case-sensitive")));
    assertEquals("a", names.getFirst().name());
  }

  @Test
  void numericAndCriteriaKeysStillResolveTheirPlaceholder() {
    assertEquals("%ping%", TabSort.placeholderOf("number-desc:%ping%"));
    assertNull(TabSort.placeholderOf("group"));
    assertNull(TabSort.placeholderOf("z-a"));
    assertEquals("%score%", TabSort.placeholderOf("%score%"));
  }
}
