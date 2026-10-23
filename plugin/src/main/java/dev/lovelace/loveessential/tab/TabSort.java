package dev.lovelace.loveessential.tab;

import java.util.*;

/**
 * Player-list ordering. Criteria are applied in the configured order, and the player name breaks
 * remaining ties. Supported criteria:
 *
 * <ul>
 *   <li>{@code group} - lower group priority first;
 *   <li>{@code name} or {@code a-z} - name alphabetically, {@code z-a} - reverse;
 *   <li>{@code number:%p%} / {@code number-desc:%p%} - numeric value of a placeholder;
 *   <li>{@code permissions:p1,p2,...} - players holding the earlier permission come first;
 *   <li>{@code placeholder:%p%:v1,v2,...} - players whose value is listed earlier come first;
 *   <li>{@code case-sensitive} - makes every text comparison case-sensitive (default: off).
 *   <li>any other word is a placeholder text, compared case-insensitively.
 * </ul>
 */
public final class TabSort {
  public static final String CASE_SENSITIVE = "case-sensitive";

  private static final String NUMBER = "number:",
      NUMBER_DESC = "number-desc:",
      PERMISSIONS = "permissions:",
      PLACEHOLDER = "placeholder:";

  public record Entry(
      UUID id, String name, int group, Map<String, String> values, Set<String> permissions) {
    public Entry {
      values = Map.copyOf(values);
      permissions = Set.copyOf(permissions);
    }

    public Entry(UUID id, String name, int group, Map<String, String> values) {
      this(id, name, group, values, Set.of());
    }
  }

  private TabSort() {}

  /**
   * The placeholder whose value a criterion needs, or {@code null} when it needs none. The caller
   * resolves these once per player and stores them under the returned key in {@link Entry#values}.
   */
  public static String placeholderOf(String key) {
    if (key.equals("group") || key.equals("name") || key.equals("a-z") || key.equals("z-a"))
      return null;
    if (key.equals(CASE_SENSITIVE) || key.startsWith(PERMISSIONS)) return null;
    if (key.startsWith(NUMBER_DESC)) return key.substring(NUMBER_DESC.length());
    if (key.startsWith(NUMBER)) return key.substring(NUMBER.length());
    if (key.startsWith(PLACEHOLDER)) return placeholderToken(key);
    return key;
  }

  /** Permission nodes listed by a {@code permissions:} criterion, in priority order. */
  public static List<String> permissionsOf(String key) {
    if (!key.startsWith(PERMISSIONS)) return List.of();
    return splitList(key.substring(PERMISSIONS.length()));
  }

  private static String placeholderToken(String key) {
    String spec = key.substring(PLACEHOLDER.length());
    int separator = spec.lastIndexOf(':');
    return separator <= 0 ? spec : spec.substring(0, separator);
  }

  private static List<String> placeholderValues(String key) {
    String spec = key.substring(PLACEHOLDER.length());
    int separator = spec.lastIndexOf(':');
    return separator <= 0 ? List.of() : splitList(spec.substring(separator + 1));
  }

  private static List<String> splitList(String value) {
    return Arrays.stream(value.split(",", -1)).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  public static Comparator<Entry> comparator(List<String> keys) {
    boolean sensitive = keys.contains(CASE_SENSITIVE);
    Comparator<String> text = sensitive ? Comparator.naturalOrder() : String.CASE_INSENSITIVE_ORDER;
    Comparator<Entry> c = (a, b) -> 0;
    for (String key : keys) {
      Comparator<Entry> next;
      if (key.equals(CASE_SENSITIVE)) continue;
      if (key.equals("group")) next = Comparator.comparingInt(Entry::group);
      else if (key.equals("name") || key.equals("a-z"))
        next = Comparator.comparing(Entry::name, text);
      else if (key.equals("z-a")) next = Comparator.comparing(Entry::name, text.reversed());
      else if (key.startsWith(PERMISSIONS)) {
        var order = permissionsOf(key);
        next = Comparator.comparingInt(e -> firstIndex(order, e.permissions()::contains));
      } else if (key.startsWith(PLACEHOLDER)) {
        var token = placeholderToken(key);
        var order = placeholderValues(key);
        next =
            Comparator.comparingInt(
                e -> {
                  String value = e.values().getOrDefault(token, "").strip();
                  for (int i = 0; i < order.size(); i++)
                    if (text.compare(order.get(i), value) == 0) return i;
                  return order.size();
                });
      } else if (key.startsWith(NUMBER_DESC))
        next =
            Comparator.<Entry>comparingDouble(e -> number(e.values().get(placeholderOf(key))))
                .reversed();
      else if (key.startsWith(NUMBER))
        next = Comparator.comparingDouble(e -> number(e.values().get(placeholderOf(key))));
      else next = Comparator.comparing(e -> e.values().getOrDefault(key, ""), text);
      c = c.thenComparing(next);
    }
    return c.thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id);
  }

  private static int firstIndex(List<String> order, java.util.function.Predicate<String> has) {
    for (int i = 0; i < order.size(); i++) if (has.test(order.get(i))) return i;
    return order.size();
  }

  private static double number(String n) {
    if (n == null) return 0;
    try {
      double v = Double.parseDouble(n);
      return Double.isFinite(v) ? v : 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
