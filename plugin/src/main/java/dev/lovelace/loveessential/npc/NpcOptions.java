package dev.lovelace.loveessential.npc;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;

/** Shared editor/completion choices. Values are protocol identifiers, labels live in lang.yml. */
public final class NpcOptions {
  public static final Set<String> TEXT =
      Set.of(
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
          "interaction-sound");
  public static final Set<String> EQUIPMENT =
      Set.of("main-hand", "off-hand", "helmet", "chestplate", "leggings", "boots");
  private static final Map<String, String> DEFAULTS =
      Map.ofEntries(
          Map.entry("look-at", "true"),
          Map.entry("gravity", "true"),
          Map.entry("invulnerable", "true"),
          Map.entry("silent", "true"),
          Map.entry("name-visible", "true"),
          Map.entry("require-auth", "true"),
          Map.entry("pause-on-interact", "true"),
          Map.entry("avoid-water", "true"),
          Map.entry("activation-range", "48"),
          Map.entry("interaction-range", "4"),
          Map.entry("look-range", "8"),
          Map.entry("speed", "1"),
          Map.entry("scale", "1"),
          Map.entry("cooldown-ms", "500"),
          Map.entry("stuck-ticks", "100"),
          Map.entry("hour-from", "0"),
          Map.entry("hour-to", "23999"),
          Map.entry("follow-distance", "3"),
          Map.entry("interaction-volume", "1"),
          Map.entry("interaction-pitch", "1"),
          Map.entry("interaction-mode", "BOTH"),
          Map.entry("movement", "route"),
          Map.entry("pose", "STANDING"),
          Map.entry("stuck-policy", "PAUSE"));

  private NpcOptions() {}

  public static String defaultValue(String key) {
    if (DEFAULTS.containsKey(key)) return DEFAULTS.get(key);
    if (NpcValidator.BOOLEAN_OPTIONS.contains(key)) return "false";
    if (EQUIPMENT.contains(key)) return "AIR";
    return "";
  }

  public static List<String> fields() {
    var out = new TreeSet<String>(TEXT);
    out.addAll(NpcValidator.BOOLEAN_OPTIONS);
    out.addAll(NpcValidator.NUMERIC_OPTIONS.keySet());
    out.addAll(List.of("id", "name", "type", "visibility", "access", "actions", "enabled"));
    return List.copyOf(out);
  }

  public static List<String> choices(String field, ConfigurationSection config) {
    if (NpcValidator.BOOLEAN_OPTIONS.contains(field) || field.equals("enabled"))
      return List.of("true", "false");
    if (EQUIPMENT.contains(field))
      return java.util.stream.Stream.concat(
              java.util.stream.Stream.of("AIR"),
              Arrays.stream(Material.values()).filter(Material::isItem).map(Enum::name))
          .distinct()
          .sorted()
          .toList();
    return switch (field) {
      case "interaction-sound" ->
          java.util.stream.Stream.concat(
                  java.util.stream.Stream.of("NONE"),
                  Arrays.stream(Sound.values()).map(Sound::name))
              .toList();
      case "type" -> config.getStringList("npc.allowed-types");
      case "pose" -> Arrays.stream(org.bukkit.entity.Pose.values()).map(Enum::name).toList();
      case "movement" -> List.of("route", "follow", "stationary");
      case "interaction-mode" -> List.of("RIGHT_CLICK", "LEFT_CLICK", "BOTH");
      case "stuck-policy" -> List.of("PAUSE", "SKIP", "RETURN_HOME");
      default -> List.of();
    };
  }
}
