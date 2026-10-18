package dev.lovelace.loveessential.npc;

import dev.lovelace.loveessential.api.*;
import java.util.*;
import java.util.function.Predicate;

/** Pure validation shared by API, imports and in-game editor. Error strings are lang keys. */
public final class NpcValidator {
  public static final Set<String> BOOLEAN_OPTIONS =
      Set.of(
          "look-at",
          "collidable",
          "gravity",
          "invulnerable",
          "glowing",
          "silent",
          "name-visible",
          "pause-on-interact",
          "require-auth",
          "avoid-water",
          "can-open-doors",
          "teleport-on-stuck",
          "enabled-hours",
          "personal",
          "baby",
          "line-of-sight");
  public static final Map<String, double[]> NUMERIC_OPTIONS =
      Map.ofEntries(
          Map.entry("activation-range", new double[] {4, 192}),
          Map.entry("interaction-range", new double[] {1, 16}),
          Map.entry("look-range", new double[] {1, 64}),
          Map.entry("speed", new double[] {.1, 5}),
          Map.entry("scale", new double[] {.1, 8}),
          Map.entry("cooldown-ms", new double[] {100, 3600000}),
          Map.entry("stuck-ticks", new double[] {20, 1200}),
          Map.entry("hour-from", new double[] {0, 23999}),
          Map.entry("hour-to", new double[] {0, 23999}),
          Map.entry("follow-distance", new double[] {1, 32}),
          Map.entry("interaction-volume", new double[] {0, 4}),
          Map.entry("interaction-pitch", new double[] {.5, 2}));
  private final Predicate<String> lang, condition, action;

  public NpcValidator(
      Predicate<String> lang, Predicate<String> condition, Predicate<String> action) {
    this.lang = lang;
    this.condition = condition;
    this.action = action;
  }

  public static boolean knownOption(String k) {
    return BOOLEAN_OPTIONS.contains(k)
        || NUMERIC_OPTIONS.containsKey(k)
        || Set.of(
                "skin",
                "pose",
                "movement",
                "stuck-policy",
                "main-hand",
                "off-hand",
                "helmet",
                "chestplate",
                "leggings",
                "boots",
                "follow-player",
                "description-key",
                "interaction-mode",
                "interaction-sound")
            .contains(k);
  }

  public static boolean validOption(String k, String v) {
    if (!knownOption(k)) return false;
    if (BOOLEAN_OPTIONS.contains(k)) return v.equals("true") || v.equals("false");
    if (NUMERIC_OPTIONS.containsKey(k)) {
      try {
        double n = Double.parseDouble(v);
        var range = NUMERIC_OPTIONS.get(k);
        return Double.isFinite(n) && n >= range[0] && n <= range[1];
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return switch (k) {
      case "movement" -> Set.of("route", "follow", "stationary").contains(v);
      case "interaction-mode" -> Set.of("RIGHT_CLICK", "LEFT_CLICK", "BOTH").contains(v);
      case "interaction-sound" -> {
        if (v.isEmpty()) yield true;
        try {
          org.bukkit.Sound.valueOf(v.toUpperCase(Locale.ROOT));
          yield true;
        } catch (IllegalArgumentException e) {
          yield false;
        }
      }
      case "stuck-policy" -> Set.of("PAUSE", "SKIP", "RETURN_HOME").contains(v);
      case "skin" -> {
        boolean valid = v.isEmpty() || v.matches("[a-zA-Z0-9_]{1,16}");
        if (!valid)
          try {
            UUID.fromString(v);
            valid = true;
          } catch (IllegalArgumentException ignored) {
          }
        yield valid;
      }
      case "pose" -> {
        try {
          org.bukkit.entity.Pose.valueOf(v);
          yield true;
        } catch (IllegalArgumentException e) {
          yield false;
        }
      }
      case "main-hand", "off-hand", "helmet", "chestplate", "leggings", "boots" ->
          org.bukkit.Material.matchMaterial(v) != null;
      default -> v.length() <= 1024;
    };
  }

  public List<String> validate(NpcDefinition n, int maxPoints) {
    var errors = new ArrayList<String>();
    if (n == null) {
      errors.add("validation.npc");
      return errors;
    }
    if (n.id() == null || !n.id().matches("[a-z0-9_-]{1,48}")) errors.add("validation.id");
    if (n.owner() == null || n.spawn() == null || n.revision() < 0) errors.add("validation.npc");
    if (n.nameKey() == null || !lang.test(n.nameKey())) errors.add("validation.lang");
    n.settings()
        .forEach(
            (k, v) -> {
              if (!validOption(k, v)) errors.add("validation.option");
            });
    check(n.visibility(), errors, 0);
    check(n.access(), errors, 0);
    actions(n.actions(), errors);
    if (n.route().points().size() > maxPoints) errors.add("validation.points");
    for (var p : n.route().points()) {
      if (!p.position().world().equals(n.spawn().world()))
        errors.add("validation.world-transition");
      check(p.condition(), errors, 0);
      actions(p.actions(), errors);
    }
    var d = n.dialog();
    if (!d.start().isEmpty() && !d.nodes().containsKey(d.start())) errors.add("validation.dialog");
    if (d.nodes().size() > 128) errors.add("validation.dialog");
    for (var e : d.nodes().entrySet()) {
      if (!e.getKey().matches("[a-z0-9_-]{1,48}") || !lang.test(e.getValue().textKey()))
        errors.add("validation.lang");
      var ids = new HashSet<String>();
      for (var a : e.getValue().answers()) {
        if (!ids.add(a.id())
            || !a.id().matches("[a-z0-9_-]{1,48}")
            || !lang.test(a.textKey())
            || (!a.next().isEmpty() && !d.nodes().containsKey(a.next())))
          errors.add("validation.dialog");
        check(a.condition(), errors, 0);
        actions(a.actions(), errors);
      }
    }
    if (!d.start().isEmpty()) {
      var visited = new HashSet<String>();
      var queue = new ArrayDeque<String>();
      queue.add(d.start());
      while (!queue.isEmpty()) {
        String id = queue.remove();
        if (!visited.add(id)) continue;
        var node = d.nodes().get(id);
        if (node != null) for (var a : node.answers()) if (!a.next().isEmpty()) queue.add(a.next());
      }
      if (!visited.containsAll(d.nodes().keySet())) errors.add("validation.unreachable");
    }
    return errors.stream().distinct().toList();
  }

  private void check(Condition c, List<String> e, int depth) {
    if (c == null || depth > 24) {
      e.add("validation.condition");
      return;
    }
    if (!condition.test(c.type())
        || (c.type().equals("not") && c.children().size() != 1)
        || c.children().size() > 64) e.add("validation.condition");
    try {
      boolean valid =
          switch (c.type()) {
            case "permission", "world", "clan" -> !c.args().getOrDefault("value", "").isBlank();
            case "reputation" -> inRange(c.args().get("min"), 0, 100);
            case "balance" -> inRange(c.args().get("amount"), 1, Long.MAX_VALUE);
            case "item" ->
                org.bukkit.Material.matchMaterial(c.args().getOrDefault("material", "")) != null
                    && inRange(c.args().getOrDefault("amount", "1"), 1, 2304);
            case "time" ->
                inRange(c.args().get("from"), 0, 23999) && inRange(c.args().get("to"), 0, 23999);
            case "weather" ->
                Set.of("rain", "thunder", "clear").contains(c.args().getOrDefault("value", ""));
            case "flag" ->
                c.args().getOrDefault("key", "").matches("[a-z0-9_-]+[.:][a-zA-Z0-9_.:-]+");
            default -> true;
          };
      if (!valid) e.add("validation.condition");
    } catch (RuntimeException ex) {
      e.add("validation.condition");
    }
    for (var child : c.children()) check(child, e, depth + 1);
  }

  private static boolean inRange(String value, double min, double max) {
    try {
      long n = Long.parseLong(value);
      return n >= min && n <= max;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void actions(List<Action> actions, List<String> errors) {
    if (actions.size() > 32) errors.add("validation.action");
    for (var a : actions) {
      if (!action.test(a.type())) errors.add("validation.action");
      if (Set.of("message", "actionbar").contains(a.type())
          && !lang.test(a.args().getOrDefault("key", ""))) errors.add("validation.lang");
      if (Set.of("charge", "give").contains(a.type())
          && !inRange(a.args().get("amount"), 1, Long.MAX_VALUE)) errors.add("validation.action");
    }
  }
}
