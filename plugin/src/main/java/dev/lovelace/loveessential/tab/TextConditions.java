package dev.lovelace.loveessential.tab;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Named text conditions for TAB, scoreboards and bossbars. Pure logic: the caller supplies the
 * placeholder resolver and the permission and engine lookups, so this class never touches Bukkit.
 *
 * <p>A check is one of {@code left OP right} (both sides are resolved first), {@code
 * permission:node} or {@code engine:id}. A definition combines its checks with {@code and} or
 * {@code or}; without checks {@code and} is true and {@code or} is false.
 */
public final class TextConditions {
  private TextConditions() {}

  public enum Type {
    AND,
    OR;

    public static Type parse(String value) {
      return switch (value == null ? "and" : value.strip().toLowerCase(Locale.ROOT)) {
        case "and" -> AND;
        case "or" -> OR;
        default -> throw new IllegalArgumentException("errors.config");
      };
    }
  }

  public enum Operator {
    EQUAL("=="),
    NOT_EQUAL("!="),
    GREATER_OR_EQUAL(">="),
    LESS_OR_EQUAL("<="),
    GREATER(">"),
    LESS("<"),
    CONTAINS("contains"),
    STARTS_WITH("starts-with"),
    ENDS_WITH("ends-with");

    final String token;

    Operator(String token) {
      this.token = token;
    }
  }

  public sealed interface Check permits Compare, Permission, Engine {}

  public record Compare(String left, Operator operator, String right) implements Check {}

  public record Permission(String node) implements Check {}

  public record Engine(String id) implements Check {}

  /** {@code yes} and {@code no} are MiniMessage texts used by the {@code <condition:name>} tag. */
  public record Definition(Type type, List<Check> checks, String yes, String no) {
    public Definition {
      Objects.requireNonNull(type);
      checks = List.copyOf(checks);
      yes = yes == null ? "" : yes;
      no = no == null ? "" : no;
    }
  }

  private static final Pattern COMPARE =
      Pattern.compile(
          "^(.*?)\\s+(==|!=|>=|<=|>|<|contains|starts-with|ends-with)\\s+(.*)$",
          Pattern.DOTALL);

  /** Parses one check; invalid syntax is a configuration error, never a silent "false". */
  public static Check parse(String text) {
    if (text == null) throw new IllegalArgumentException("errors.config");
    String value = text.strip();
    if (value.startsWith("permission:")) {
      String node = value.substring("permission:".length()).strip();
      if (node.isEmpty() || node.contains(" ")) throw new IllegalArgumentException("errors.config");
      return new Permission(node);
    }
    if (value.startsWith("engine:")) {
      String id = value.substring("engine:".length()).strip();
      if (!id.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("errors.config");
      return new Engine(id);
    }
    Matcher matcher = COMPARE.matcher(value);
    if (!matcher.matches()) throw new IllegalArgumentException("errors.config");
    String left = matcher.group(1).strip();
    String right = matcher.group(3).strip();
    if (left.isEmpty()) throw new IllegalArgumentException("errors.config");
    return new Compare(left, operator(matcher.group(2)), right);
  }

  private static Operator operator(String token) {
    for (Operator operator : Operator.values()) if (operator.token.equals(token)) return operator;
    throw new IllegalArgumentException("errors.config");
  }

  /**
   * Evaluates a definition. {@code resolve} expands placeholders in compare operands, {@code
   * permission} answers {@code permission:} checks and {@code engine} answers {@code engine:} checks.
   */
  public static boolean evaluate(
      Definition definition,
      Function<String, String> resolve,
      Predicate<String> permission,
      Predicate<String> engine) {
    if (definition.checks().isEmpty()) return definition.type() == Type.AND;
    for (Check check : definition.checks()) {
      boolean result = check(check, resolve, permission, engine);
      if (definition.type() == Type.AND && !result) return false;
      if (definition.type() == Type.OR && result) return true;
    }
    return definition.type() == Type.AND;
  }

  private static boolean check(
      Check check,
      Function<String, String> resolve,
      Predicate<String> permission,
      Predicate<String> engine) {
    return switch (check) {
      case Permission p -> permission.test(p.node());
      case Engine e -> engine.test(e.id());
      case Compare c -> compare(c, resolve);
    };
  }

  static boolean compare(Compare check, Function<String, String> resolve) {
    String left = resolve.apply(check.left()).strip();
    String right = resolve.apply(check.right()).strip();
    OptionalDouble a = number(left), b = number(right);
    return switch (check.operator()) {
      case EQUAL -> a.isPresent() && b.isPresent()
          ? a.getAsDouble() == b.getAsDouble()
          : left.equalsIgnoreCase(right);
      case NOT_EQUAL -> a.isPresent() && b.isPresent()
          ? a.getAsDouble() != b.getAsDouble()
          : !left.equalsIgnoreCase(right);
      case GREATER -> a.isPresent() && b.isPresent() && a.getAsDouble() > b.getAsDouble();
      case LESS -> a.isPresent() && b.isPresent() && a.getAsDouble() < b.getAsDouble();
      case GREATER_OR_EQUAL -> a.isPresent() && b.isPresent() && a.getAsDouble() >= b.getAsDouble();
      case LESS_OR_EQUAL -> a.isPresent() && b.isPresent() && a.getAsDouble() <= b.getAsDouble();
      case CONTAINS -> left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
      case STARTS_WITH -> left.toLowerCase(Locale.ROOT).startsWith(right.toLowerCase(Locale.ROOT));
      case ENDS_WITH -> left.toLowerCase(Locale.ROOT).endsWith(right.toLowerCase(Locale.ROOT));
    };
  }

  private static OptionalDouble number(String value) {
    try {
      double v = Double.parseDouble(value);
      return Double.isFinite(v) ? OptionalDouble.of(v) : OptionalDouble.empty();
    } catch (NumberFormatException e) {
      return OptionalDouble.empty();
    }
  }
}
