package dev.lovelace.loveessential.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Reads the NPCs of {@code plugins/Citizens/saves.yml} without Citizens being installed. Only what
 * LoveEssential can show is taken: name, entity type, place, skin, look-close and hologram lines.
 */
public final class CitizensImport {
  public record Entry(
      int id,
      String name,
      String type,
      String world,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      String skinName,
      String skinValue,
      String skinSignature,
      boolean lookClose,
      List<String> hologram) {}

  public record Result(List<Entry> entries, List<String> skipped) {}

  private CitizensImport() {}

  /** Every NPC under {@code npc.<id>}; records without a number or a place are listed in {@code skipped}. */
  public static Result parse(ConfigurationSection saves) {
    var entries = new ArrayList<Entry>();
    var skipped = new ArrayList<String>();
    var all = saves.getConfigurationSection("npc");
    if (all == null) return new Result(entries, skipped);
    for (String key : all.getKeys(false)) {
      var npc = all.getConfigurationSection(key);
      int id;
      try {
        id = Integer.parseInt(key.trim());
      } catch (NumberFormatException e) {
        skipped.add(key + ": not a number");
        continue;
      }
      if (npc == null) {
        skipped.add(key + ": empty");
        continue;
      }
      var traits = npc.getConfigurationSection("traits");
      var at = traits == null ? null : traits.getConfigurationSection("location");
      String world = at == null ? "" : at.getString("bworld", at.getString("world", ""));
      if (at == null || world.isBlank()) {
        skipped.add(key + ": no location");
        continue;
      }
      var skin = traits.getConfigurationSection("skintrait");
      var look = traits.get("lookclose");
      boolean lookClose =
          look instanceof Boolean b
              ? b
              : look instanceof ConfigurationSection s && s.getBoolean("enabled", false);
      entries.add(
          new Entry(
              id,
              npc.getString("name", "NPC " + id),
              traits.getString("type", "PLAYER").toUpperCase(Locale.ROOT),
              world,
              at.getDouble("x"),
              at.getDouble("y"),
              at.getDouble("z"),
              (float) at.getDouble("yaw"),
              (float) at.getDouble("pitch"),
              skin == null ? "" : skin.getString("skinName", ""),
              skin == null ? "" : skin.getString("textureRaw", ""),
              skin == null ? "" : skin.getString("signature", ""),
              lookClose,
              hologram(traits.getConfigurationSection("hologramtrait"))));
    }
    return new Result(entries, skipped);
  }

  private static List<String> hologram(ConfigurationSection trait) {
    var lines = new ArrayList<String>();
    if (trait == null) return lines;
    for (Object line : trait.getList("lines", List.of()))
      if (line instanceof Map<?, ?> map && map.get("text") != null) lines.add(map.get("text").toString());
      else if (line instanceof String text) lines.add(text);
    return lines;
  }
}
