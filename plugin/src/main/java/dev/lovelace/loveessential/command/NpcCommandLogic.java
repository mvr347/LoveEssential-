package dev.lovelace.loveessential.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The server-free part of the {@code /npc} commands: what a {@code list} filter means, which NPCs are near a point, and
 * whether the first word of {@code rename} is an NPC or part of the new name. Kept apart from Bukkit so it is unit tested.
 */
public final class NpcCommandLogic {
  public static final int PAGE_SIZE = 8;
  public static final double DEFAULT_NEAR_RADIUS = 32;
  public static final double MAX_NEAR_RADIUS = 512;

  private NpcCommandLogic() {}

  /** One NPC as the list sees it. {@code world} is the world id of its spawn. */
  public record Row(String id, int number, String name, boolean enabled, UUID world, double x, double y, double z) {
    double distanceSquared(UUID otherWorld, double ox, double oy, double oz) {
      if (!world.equals(otherWorld)) return Double.POSITIVE_INFINITY;
      double dx = x - ox, dy = y - oy, dz = z - oz;
      return dx * dx + dy * dy + dz * dz;
    }
  }

  public enum Kind {
    ALL,
    ENABLED,
    DISABLED,
    NEAR,
    WORLD
  }

  /** {@code worldName} is only set for {@link Kind#WORLD}; {@code page} counts from 1. */
  public record Query(Kind kind, double radius, String worldName, int page) {}

  private static final Set<String> ALL = Set.of("all", "все", "вс");
  private static final Set<String> ON = Set.of("on", "enabled", "вкл", "включённые", "включенные");
  private static final Set<String> OFF = Set.of("off", "disabled", "выкл", "выключенные");
  private static final Set<String> NEAR = Set.of("near", "рядом");
  private static final Set<String> WORLD = Set.of("world", "мир");

  /** Parses the words after {@code /npc list}: a filter and/or a page. Anything else is an input error. */
  public static Query parseList(String[] words) {
    Kind kind = Kind.ALL;
    double radius = DEFAULT_NEAR_RADIUS;
    String world = null;
    int page = 1;
    int i = 0;
    if (i < words.length && !isNumber(words[i])) {
      String word = words[i].toLowerCase(Locale.ROOT);
      i++;
      if (ALL.contains(word)) {
        kind = Kind.ALL;
      } else if (ON.contains(word)) {
        kind = Kind.ENABLED;
      } else if (OFF.contains(word)) {
        kind = Kind.DISABLED;
      } else if (NEAR.contains(word)) {
        kind = Kind.NEAR;
        // "near 50" is a radius; a page may follow it ("near 50 2")
        if (i < words.length && isNumber(words[i])) radius = radius(words[i++]);
      } else if (WORLD.contains(word)) {
        kind = Kind.WORLD;
        if (i >= words.length) throw new IllegalArgumentException("errors.input");
        world = words[i++];
      } else {
        throw new IllegalArgumentException("errors.input");
      }
    }
    if (i < words.length) {
      if (!isNumber(words[i])) throw new IllegalArgumentException("errors.input");
      page = Integer.parseInt(words[i++]);
      if (page < 1) throw new IllegalArgumentException("errors.input");
    }
    if (i < words.length) throw new IllegalArgumentException("errors.input");
    return new Query(kind, radius, world, page);
  }

  /** The radius of {@code /npc near [radius]}, clamped to a sane range. */
  public static double radius(String text) {
    if (!isNumber(text)) throw new IllegalArgumentException("errors.input");
    double value = Double.parseDouble(text);
    if (value <= 0) throw new IllegalArgumentException("errors.input");
    return Math.min(value, MAX_NEAR_RADIUS);
  }

  private static boolean isNumber(String text) {
    if (text == null || text.isEmpty() || text.length() > 9) return false;
    for (int i = 0; i < text.length(); i++) if (!Character.isDigit(text.charAt(i))) return false;
    return true;
  }

  /**
   * The rows a query selects, in the order the list shows them: by number, or nearest first for {@link Kind#NEAR}.
   * {@code worldId} resolves the name of a {@link Kind#WORLD} query (null when no such world), the position is the viewer's.
   */
  public static List<Row> select(List<Row> rows, Query query, UUID worldId, UUID viewerWorld, double vx, double vy, double vz) {
    List<Row> out = new ArrayList<>();
    for (Row row : rows) {
      boolean keep =
          switch (query.kind()) {
            case ALL -> true;
            case ENABLED -> row.enabled();
            case DISABLED -> !row.enabled();
            case WORLD -> worldId != null && row.world().equals(worldId);
            case NEAR -> row.distanceSquared(viewerWorld, vx, vy, vz) <= query.radius() * query.radius();
          };
      if (keep) out.add(row);
    }
    if (query.kind() == Kind.NEAR) {
      out.sort(Comparator.comparingDouble((Row r) -> r.distanceSquared(viewerWorld, vx, vy, vz)).thenComparingInt(Row::number));
    } else {
      out.sort(Comparator.comparingInt(Row::number));
    }
    return out;
  }

  /** Distance from a point to a row; {@code -1} when the NPC is in another world. */
  public static double distance(Row row, UUID world, double x, double y, double z) {
    double squared = row.distanceSquared(world, x, y, z);
    return Double.isInfinite(squared) ? -1 : Math.sqrt(squared);
  }

  public static int pages(int count) {
    return Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
  }

  public static <T> List<T> page(List<T> all, int page) {
    int from = Math.min(all.size(), (Math.max(1, page) - 1) * PAGE_SIZE);
    return all.subList(from, Math.min(all.size(), from + PAGE_SIZE));
  }

  /** The reference and the new name of {@code /npc rename [ref] <name...>}. {@code ref} is null when the selection is meant. */
  public record Rename(String ref, String name) {}

  /**
   * {@code words} are the words after {@code rename}. The first word is an NPC reference only when something follows it
   * and {@code isReference} recognises it, so a one-word name such as {@code Банкир} is never mistaken for an NPC.
   */
  public static Rename parseRename(String[] words, Predicate<String> isReference) {
    if (words.length == 0) throw new IllegalArgumentException("npc.rename-usage");
    if (words.length >= 2 && isReference.test(words[0])) {
      return new Rename(words[0], String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length)).strip());
    }
    return new Rename(null, String.join(" ", words).strip());
  }

  /** What {@code /npc new} asks for: the name (colour codes allowed), an optional skin and an optional entity type. */
  public record NewNpc(String name, String skin, String type) {}

  /**
   * Parses {@code /npc new <name...> [--skin <player>] [--type <TYPE>]}. The flags may stand anywhere; every other word is
   * part of the name. A flag without a value, or no name at all, is an error.
   */
  public static NewNpc parseNew(String[] words) {
    var name = new java.util.ArrayList<String>();
    String skin = null, type = null;
    for (int i = 0; i < words.length; i++) {
      String word = words[i];
      String flag = word.toLowerCase(Locale.ROOT);
      boolean isSkin = flag.equals("--skin") || flag.equals("--скин");
      boolean isType = flag.equals("--type") || flag.equals("--тип");
      if (isSkin || isType) {
        if (i + 1 >= words.length) throw new IllegalArgumentException("npc.new-usage");
        String value = words[++i];
        if (isSkin) skin = value;
        else type = value;
      } else {
        name.add(word);
      }
    }
    String joined = String.join(" ", name).strip();
    if (joined.isEmpty() || joined.length() > 256) throw new IllegalArgumentException("npc.new-usage");
    return new NewNpc(joined, skin, type);
  }
}
