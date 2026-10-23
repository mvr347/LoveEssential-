package dev.lovelace.loveessential.scoreboard;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;

/** Assembles the lines of a personal board. Free of Bukkit. */
final class PersonalBoard {
  private PersonalBoard() {}

  /**
   * First line (top, or the separator when top is blank), the chosen lines two per row with a
   * separator after every pair, and the bottom line. The first and last lines are centered on the
   * widest line of the board. A lone trailing line still gets its own separator.
   */
  static List<Component> assemble(
      Component title, Component top, Component separator, Component bottom, List<Component> rows) {
    int width = SidebarWidth.width(title);
    for (Component row : rows) width = Math.max(width, SidebarWidth.width(row));
    var lines = new ArrayList<Component>();
    lines.add(SidebarWidth.width(top) == 0 ? separator : SidebarWidth.center(top, width));
    for (int i = 0; i < rows.size(); i += 2) {
      lines.add(rows.get(i));
      if (i + 1 < rows.size()) lines.add(rows.get(i + 1));
      lines.add(separator);
    }
    lines.add(SidebarWidth.center(bottom, width));
    return List.copyOf(lines);
  }
}
