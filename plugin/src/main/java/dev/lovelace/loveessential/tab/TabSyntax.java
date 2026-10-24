package dev.lovelace.loveessential.tab;

import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text syntax of the original TAB plugin, converted to what {@link Placeholders} renders: legacy
 * {@code &}-colors become MiniMessage tags and {@code %...%} tokens become {@code <papi:'...'>}.
 */
public final class TabSyntax {
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

  /**
   * Fills the arguments of {@code <head:...>} before the rest of the template is converted.
   * MiniMessage does not expand a tag inside the arguments of another one, so
   * {@code <head:name:%player%>} would reach it as {@code <head:name:<papi:'player'>>}. Here
   * {@code %token%}, {@code <player>} and {@code <real_name>} are replaced by their text; the
   * value never keeps a colon, bracket, quote or space, so a player cannot add tags through it.
   * A leading {@code name:}, {@code player:}, {@code uuid:} or {@code texture:} is removed, since
   * MiniMessage reads the first argument itself. A head whose value comes out empty is dropped.
   *
   * @param value text for a token name without percent signs ({@code player}, {@code luckperms_prefix}),
   *     or for {@code player} / {@code real_name}; null counts as empty
   */
  public static String heads(String template, UnaryOperator<String> value) {
    int open = template.indexOf("<head:");
    if (open < 0) return template;
    var out = new StringBuilder(template.length());
    int from = 0;
    while (open >= 0) {
      int close = tagEnd(template, open);
      if (close < 0) break;
      out.append(template, from, open);
      String args = template.substring(open + "<head:".length(), close);
      // MiniMessage takes the value first and tells name, UUID and texture apart by its shape; a
      // leading "name:" / "player:" / "uuid:" / "texture:" would be read as the player called "name".
      String filled = fill(args.replaceFirst("(?i)^(name|player|uuid|texture):", ""), value);
      if (!filled.isEmpty()) out.append("<head:").append(filled).append('>');
      from = close + 1;
      open = template.indexOf("<head:", from);
    }
    return out.append(template, from, template.length()).toString();
  }

  /** Index of the {@code >} closing the tag opened at {@code open}; nested tags are skipped. */
  private static int tagEnd(String text, int open) {
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '<') depth++;
      else if (c == '>' && --depth == 0) return i;
    }
    return -1;
  }

  private static String fill(String args, UnaryOperator<String> value) {
    var out = new StringBuilder();
    Matcher m = Pattern.compile("%([^%<>\\s]+)%|<(player|real_name)>").matcher(args);
    int from = 0;
    boolean replaced = false, empty = false;
    while (m.find()) {
      out.append(args, from, m.start());
      String name = m.group(1) != null ? m.group(1) : m.group(2);
      String text = value.apply(name);
      text = text == null ? "" : text.replaceAll("[:<>'\"\\s]", "");
      if (text.isEmpty()) empty = true;
      out.append(text);
      replaced = true;
      from = m.end();
    }
    out.append(args, from, args.length());
    return replaced && empty ? "" : out.toString();
  }

  public static boolean builtin(String name) {
    return name != null && BUILTINS.contains(name.toLowerCase(Locale.ROOT));
  }

  /** {@code %name%} tokens as {@code <papi:'name'>}; text outside tokens is untouched. */
  public static String tokens(String template) {
    return TOKEN.matcher(template).replaceAll(m -> Matcher.quoteReplacement("<papi:'" + m.group(1) + "'>"));
  }

  /**
   * Legacy colors to MiniMessage: {@code &c}, {@code §c}, {@code &#rrggbb}. A color code resets the
   * decorations before it, the way legacy formatting does. Codes inside a tag ({@code <click:open_url:...&c=1>})
   * stay as written. An uppercase code glued to the end of a word ("R&D", "A&B") is not a color; at
   * the start, after a space, a symbol or another code both cases count ({@code &F} too), and a
   * lowercase code counts anywhere ("Hello&cWorld").
   */
  public static String legacy(String template) {
    if (template.indexOf('&') < 0 && template.indexOf('§') < 0) return template;
    var out = new StringBuilder(template.length() + 16);
    boolean inTag = false;
    // True right after a code, so "&7&l" reads as two codes even though '7' is a digit.
    boolean afterCode = true;
    for (int i = 0; i < template.length(); i++) {
      char c = template.charAt(i);
      if (c == '<') inTag = true;
      else if (c == '>') inTag = false;
      boolean boundary = afterCode || i == 0 || !Character.isLetterOrDigit(template.charAt(i - 1));
      afterCode = false;
      boolean marker = c == '&' || c == '§';
      if (!inTag
          && marker
          && i + 1 < template.length()
          && (boundary || !Character.isUpperCase(template.charAt(i + 1)))) {
        if (template.charAt(i + 1) == '#' && hex(template, i + 2)) {
          out.append("<reset><#").append(template, i + 2, i + 8).append('>');
          i += 7;
          afterCode = true;
          continue;
        }
        char code = Character.toLowerCase(template.charAt(i + 1));
        int color = COLORS.indexOf(code);
        if (color >= 0) {
          out.append("<reset><").append(COLOR_TAGS[color]).append('>');
          i++;
          afterCode = true;
          continue;
        }
        String tag = decoration(code);
        if (tag != null) {
          out.append('<').append(tag).append('>');
          i++;
          afterCode = true;
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
