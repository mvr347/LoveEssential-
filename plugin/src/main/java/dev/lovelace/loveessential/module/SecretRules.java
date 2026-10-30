package dev.lovelace.loveessential.module;

import java.util.ArrayList;
import java.util.List;

/** Text and number helpers of the secrets book, kept free of Bukkit so they can be tested. */
public final class SecretRules {
  private SecretRules() {}

  /** Cells of the progress bar, as in every other bar of the plugin. */
  public static final int BAR = 16;

  /** How many of {@link #BAR} cells are filled; a bar with anything found is never empty. */
  public static int filled(int found, int total) {
    if (total <= 0 || found <= 0) return 0;
    int cells = (int) Math.round(BAR * (double) Math.min(found, total) / total);
    return Math.max(1, cells);
  }

  /** Breaks text into lines of at most {@code width} characters at spaces; long words stay whole. */
  public static List<String> wrap(String text, int width) {
    var lines = new ArrayList<String>();
    if (text == null || text.isBlank()) return lines;
    var line = new StringBuilder();
    for (String word : text.trim().split("\\s+")) {
      if (line.length() > 0 && line.length() + 1 + word.length() > width) {
        lines.add(line.toString());
        line.setLength(0);
      }
      if (line.length() > 0) line.append(' ');
      line.append(word);
    }
    if (line.length() > 0) lines.add(line.toString());
    return lines;
  }
}
