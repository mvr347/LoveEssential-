package dev.lovelace.loveessential.tab;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text syntax of the original TAB plugin, converted to what {@link Placeholders} renders: legacy
 * {@code &}-colors become MiniMessage tags and {@code %...%} tokens become {@code <papi:'...'>}.
 */
final class TabSyntax {
  /**
   * A PlaceholderAPI token or a TAB built-in. The first word has no spaces; anything after the first
   * underscore may hold colons, dots and single spaces ({@code %server_time_hh:mm:ss a%}). A lone
   * percent sign in prose ("100% hp and 50%") does not match because a token starts with a letter.
   */
  static final Pattern TOKEN =
      Pattern.compile("%([A-Za-z][A-Za-z0-9]*(?:_[^%<>\\s'\"]+(?: [^%<>\\s'\"]+)*)?)%");

  /** Names TAB resolves itself; PlaceholderAPI has no placeholder with these exact names. */
  static final Set<String> BUILTINS =
      Set.of("player", "displayname", "ping", "health", "world", "online", "group", "time", "date");

  private static final String COLORS = "0123456789abcdef";
  private static final String[] COLOR_TAGS = {
    "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
    "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
  };

  private TabSyntax() {}

  static boolean builtin(String name) {
    return name != null && BUILTINS.contains(name.toLowerCase(Locale.ROOT));
  }

  /** {@code %name%} tokens as {@code <papi:'name'>}; text outside tokens is untouched. */
  static String tokens(String template) {
    return TOKEN.matcher(template).replaceAll(m -> Matcher.quoteReplacement("<papi:'" + m.group(1) + "'>"));
  }

  /**
   * Legacy colors to MiniMessage: {@code &c}, {@code §c}, {@code &#rrggbb}. A color code resets the
   * decorations before it, the way legacy formatting does. Codes inside a tag ({@code <click:open_url:...&c=1>})
   * stay as written, and only lowercase codes count, so "R&D" is not read as a color.
   */
  static String legacy(String template) {
    if (template.indexOf('&') < 0 && template.indexOf('§') < 0) return template;
    var out = new StringBuilder(template.length() + 16);
    boolean inTag = false;
    for (int i = 0; i < template.length(); i++) {
      char c = template.charAt(i);
      if (c == '<') inTag = true;
      else if (c == '>') inTag = false;
      if (!inTag && (c == '&' || c == '§') && i + 1 < template.length()) {
        if (template.charAt(i + 1) == '#' && hex(template, i + 2)) {
          out.append("<reset><#").append(template, i + 2, i + 8).append('>');
          i += 7;
          continue;
        }
        char code = template.charAt(i + 1);
        int color = COLORS.indexOf(code);
        if (color >= 0) {
          out.append("<reset><").append(COLOR_TAGS[color]).append('>');
          i++;
          continue;
        }
        String tag = decoration(code);
        if (tag != null) {
          out.append('<').append(tag).append('>');
          i++;
          continue;
        }
      }
      out.append(c);
    }
    return out.toString();
  }

  private static boolean hex(String text, int from) {
    if (from + 6 > text.length()) return false;
    for (int i = from; i < from + 6; i++)
      if (Character.digit(text.charAt(i), 16) < 0) return false;
    return true;
  }

  private static String decoration(char code) {
    return switch (code) {
      case 'k' -> "obfuscated";
      case 'l' -> "bold";
      case 'm' -> "strikethrough";
      case 'n' -> "underlined";
      case 'o' -> "italic";
      case 'r' -> "reset";
      default -> null;
    };
  }
}
