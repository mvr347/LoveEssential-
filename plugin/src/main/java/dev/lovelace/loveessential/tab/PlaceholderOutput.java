package dev.lovelace.loveessential.tab;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Replaces a resolved placeholder value with a configured text, for example "0-10" to "low".
 * Rules are checked in this order: exact value, numeric range, then {@code else}. A value that
 * matches nothing is shown unchanged. Pure.
 */
public final class PlaceholderOutput {
  public static final String ELSE = "else";

  private static final Pattern RANGE =
      Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*-\\s*(-?\\d+(?:\\.\\d+)?)$");

  private PlaceholderOutput() {}

  public record Range(double low, double high) {}

  /** Parsed form of one replacement table; {@code parse} rejects inverted ranges. */
  public record Table(Map<String, String> exact, Map<Range, String> ranges, String otherwise) {
    public Table {
      exact = Map.copyOf(exact);
      ranges = Map.copyOf(ranges);
    }
  }

  public static Table parse(Map<String, String> rules) {
    var exact = new LinkedHashMap<String, String>();
    var ranges = new LinkedHashMap<Range, String>();
    String otherwise = null;
    for (var rule : rules.entrySet()) {
      String key = rule.getKey().strip();
      String text = rule.getValue() == null ? "" : rule.getValue();
      if (key.equalsIgnoreCase(ELSE)) {
        otherwise = text;
        continue;
      }
      var matcher = RANGE.matcher(key);
      if (matcher.matches()) {
        double low = Double.parseDouble(matcher.group(1));
        double high = Double.parseDouble(matcher.group(2));
        if (!(low <= high)) throw new IllegalArgumentException("errors.config");
        ranges.put(new Range(low, high), text);
      } else {
        exact.put(key, text);
      }
    }
    return new Table(exact, ranges, otherwise);
  }

  public static String apply(Table table, String value) {
    String text = value == null ? "" : value;
    if (table.exact().containsKey(text.strip())) return table.exact().get(text.strip());
    Double number = number(text);
    if (number != null)
      for (var range : table.ranges().entrySet())
        if (number >= range.getKey().low() && number <= range.getKey().high())
          return range.getValue();
    return table.otherwise() != null ? table.otherwise() : text;
  }

  private static Double number(String text) {
    try {
      double v = Double.parseDouble(text.strip());
      return Double.isFinite(v) ? v : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
