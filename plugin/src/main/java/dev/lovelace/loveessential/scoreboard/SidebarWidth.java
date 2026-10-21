package dev.lovelace.loveessential.scoreboard;

import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.TextDecoration;

/** Vanilla font metrics used by LoveTweaks, applied without stripping component styles. */
public final class SidebarWidth {
  private SidebarWidth() {}

  public static int width(Component value) {
    return width(value, false);
  }

  private static int width(Component value, boolean inherited) {
    var state = value.decoration(TextDecoration.BOLD);
    boolean bold =
        state == TextDecoration.State.TRUE || state == TextDecoration.State.NOT_SET && inherited;
    int result = 0;
    if (value instanceof TextComponent text)
      for (int code : text.content().codePoints().toArray())
        result += glyph(code) + (bold && code != ' ' && code != 160 ? 1 : 0);
    for (var child : value.children()) result += width(child, bold);
    return result;
  }

  private static int glyph(int c) {
    if (c == ' ' || c == 160) return 4;
    if ("!.,:;i|¦".indexOf(c) >= 0) return 2;
    if ("'`l".indexOf(c) >= 0) return 3;
    if ("I[]tії".indexOf(c) >= 0) return 4;
    if ("fkк(){}<>".indexOf(c) >= 0) return 5;
    if ("@~жшщыюЖМШЩЮ".indexOf(c) >= 0) return 7;
    if (c >= 0x2000 && c <= 0x2bff) return 10;
    if (c >= 0xe000 && c <= 0xf8ff) return 9;
    return 6;
  }

  public static Component center(Component value, int target) {
    if (width(value) == 0) return value;
    int spaces = Math.max(3, (target - width(value) + 7) / 8);
    var pad = Component.text("\u00a0".repeat(spaces)).decoration(TextDecoration.BOLD, false);
    return pad.append(value).append(pad);
  }
}
