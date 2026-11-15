package dev.lovelace.loveessential.world;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** Server-wide entry restrictions; independent of world metadata and folder layout. */
public record WorldAccessPolicy(
    boolean nether, boolean end, boolean netherPortals, boolean endPortals,
    Set<String> disabledWorlds, String fallbackWorld) {
  public WorldAccessPolicy {
    disabledWorlds = Set.copyOf(disabledWorlds);
  }

  public static WorldAccessPolicy read(ConfigurationSection config) {
    String root = "worlds.entry.";
    for (String section : List.of("worlds.entry", "worlds.entry.nether", "worlds.entry.end"))
      if (config.contains(section) && !config.isConfigurationSection(section))
        throw new IllegalArgumentException("worlds.invalid-access");
    for (String key : List.of("nether.enabled", "end.enabled", "nether.portals", "end.portals"))
      if (config.contains(root + key) && !(config.get(root + key) instanceof Boolean))
        throw new IllegalArgumentException("worlds.invalid-access");
    Object raw = config.get(root + "disabled-worlds");
    if (raw != null && !(raw instanceof List<?>))
      throw new IllegalArgumentException("worlds.invalid-access");
    Set<String> disabled = new LinkedHashSet<>();
    if (raw instanceof List<?> list)
      for (Object item : list) {
        if (!(item instanceof String name)) throw new IllegalArgumentException("worlds.invalid-access");
        Worlds.name(name);
        disabled.add(name.toLowerCase(Locale.ROOT));
      }
    Object rawFallback = config.get(root + "fallback-world");
    if (rawFallback != null && !(rawFallback instanceof String))
      throw new IllegalArgumentException("worlds.invalid-access");
    String fallback = config.getString(root + "fallback-world", "").strip();
    if (!fallback.isEmpty()) {
      Worlds.name(fallback);
      if (disabled.contains(fallback.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("worlds.invalid-fallback");
    }
    return new WorldAccessPolicy(config.getBoolean(root + "nether.enabled", true),
        config.getBoolean(root + "end.enabled", true),
        config.getBoolean(root + "nether.portals", true),
        config.getBoolean(root + "end.portals", true), disabled, fallback);
  }

  public boolean allows(String name, String environment) {
    return !disabledWorlds.contains(name.toLowerCase(Locale.ROOT))
        && (!environment.equals("NETHER") || nether)
        && (!environment.equals("THE_END") || end);
  }
}
