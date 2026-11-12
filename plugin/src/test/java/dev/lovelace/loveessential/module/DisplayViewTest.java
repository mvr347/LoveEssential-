package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DisplayViewTest {
  /** {element, surface}: two elements on three surfaces, six tiles. */
  private static List<int[]> tiles() {
    var out = new ArrayList<int[]>();
    for (int e = 0; e < 2; e++) for (int s = 0; s < 3; s++) out.add(new int[] {e, s});
    return out;
  }

  private static final Set<String> ON = Set.of("0:0", "1:1");

  private List<String> view(DisplayView.Filter filter, DisplayView.Sort sort) {
    return DisplayView.apply(filter, sort, tiles(), t -> ON.contains(t[0] + ":" + t[1]), t -> t[0], t -> t[1])
        .stream()
        .map(t -> t[0] + ":" + t[1])
        .toList();
  }

  @Test
  void elementOrderIsTheDefault() {
    assertEquals(
        List.of("0:0", "0:1", "0:2", "1:0", "1:1", "1:2"),
        view(DisplayView.Filter.ALL, DisplayView.Sort.ELEMENT));
  }

  @Test
  void surfaceOrderGroupsByPlace() {
    assertEquals(
        List.of("0:0", "1:0", "0:1", "1:1", "0:2", "1:2"),
        view(DisplayView.Filter.ALL, DisplayView.Sort.SURFACE));
  }

  @Test
  void onAndOffSplitTheTiles() {
    assertEquals(List.of("0:0", "1:1"), view(DisplayView.Filter.ON, DisplayView.Sort.ELEMENT));
    assertEquals(
        List.of("0:1", "0:2", "1:0", "1:2"), view(DisplayView.Filter.OFF, DisplayView.Sort.ELEMENT));
  }
}
