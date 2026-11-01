package dev.lovelace.loveessential.calamity;

import dev.lovelace.loveessential.calamity.CalamityService.Disease;
import dev.lovelace.loveessential.calamity.CalamityService.Type;
import java.util.*;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/** A parsed snapshot of the {@code calamities} configuration, so event handlers do not parse lists. */
final class CalamitySettings {
  /** How a disease spreads. */
  enum Mode {
    INDIVIDUAL,
    WORLD,
    SERVER;

    static Mode of(String value) {
      try {
        return valueOf(value.toUpperCase(Locale.ROOT));
      } catch (RuntimeException e) {
        return INDIVIDUAL;
      }
    }
  }

  private final ConfigurationSection c;
  final Set<Material> meat;
  final Map<Type, Set<Material>> crops = new EnumMap<>(Type.class);
  final Map<Disease, Set<Type>> carriers = new EnumMap<>(Disease.class);
  final Map<Disease, Mode> modes = new EnumMap<>(Disease.class);
  final Set<Material> doubleFoods;
  final Set<Material> freshFoods;
  final List<EntityType> invaders;
  final List<String> deniedWorlds;
  final List<String> allowedWorlds;

  CalamitySettings(ConfigurationSection root) {
    this.c = root.getConfigurationSection("calamities");
    if (c == null) throw new IllegalArgumentException("errors.config");
    meat = materials(c.getStringList("meat-items"));
    for (Type t : Type.values()) crops.put(t, materials(c.getStringList("types." + t.key + ".crop-list")));
    for (Disease d : Disease.values()) {
      var set = EnumSet.noneOf(Type.class);
      for (String name : c.getStringList("diseases." + d.key + ".carriers")) {
        var type = Type.of(name);
        if (type != null) set.add(type);
      }
      carriers.put(d, set);
      modes.put(d, Mode.of(c.getString("diseases." + d.key + ".mode", "individual")));
    }
    doubleFoods = materials(c.getStringList("diseases.hunger.double-foods"));
    freshFoods = materials(c.getStringList("diseases.scurvy.fresh-foods"));
    var mobs = new ArrayList<EntityType>();
    for (String name : c.getStringList("invasion.mobs"))
      try {
        mobs.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
      } catch (RuntimeException ignored) {
        // Validation reports unknown names; a stale value never breaks a running wave.
      }
    invaders = List.copyOf(mobs);
    deniedWorlds = c.getStringList("access.denied-worlds");
    allowedWorlds = c.getStringList("access.allowed-worlds");
  }

  double d(String path, double fallback) {
    return c.getDouble(path, fallback);
  }

  int i(String path, int fallback) {
    return c.getInt(path, fallback);
  }

  boolean b(String path, boolean fallback) {
    return c.getBoolean(path, fallback);
  }

  boolean typeEnabled(Type t) {
    return c.getBoolean("types." + t.key + ".enabled", true);
  }

  /** Penalty of one kind for a calamity type, in percent. */
  double base(Type t, String key) {
    return c.getDouble("types." + t.key + "." + key, 0);
  }

  long durationTicks(Type t) {
    return Math.max(1, Math.round(c.getDouble("types." + t.key + ".duration-minutes",
        c.getDouble("types." + t.key + ".duration-days", 3) * 20) * 1200));
  }

  private final Map<String, Boolean> worlds = new java.util.concurrent.ConcurrentHashMap<>();

  /** Cached per snapshot: asked on every exhaustion event, and the patterns compile to regexes. */
  boolean worldAllowed(String world) {
    return worlds.computeIfAbsent(
        world,
        name -> !matches(deniedWorlds, name) && (allowedWorlds.isEmpty() || matches(allowedWorlds, name)));
  }

  static boolean matches(List<String> patterns, String value) {
    for (String pattern : patterns) {
      var parts = pattern.split("\\*", -1);
      var regex = new StringBuilder();
      for (int i = 0; i < parts.length; i++) {
        if (i > 0) regex.append(".*");
        regex.append(Pattern.quote(parts[i]));
      }
      if (Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(value).matches())
        return true;
    }
    return false;
  }

  static Set<Material> materials(List<String> names) {
    var out = EnumSet.noneOf(Material.class);
    for (String name : names) {
      Material m = Material.matchMaterial(name);
      if (m != null) out.add(m);
    }
    return Set.copyOf(out);
  }

  /** Throws {@code errors.config} for values that cannot work; mirrors the other modules' checks. */
  static void validate(ConfigurationSection root) {
    var c = root.getConfigurationSection("calamities");
    if (c == null) return;
    range(c, "cap-percent", 0, 100);
    range(c, "online-full-strength", 1, 1000);
    range(c, "newcomer-minutes", 0, 525600);
    range(c, "cooldown-days", 0, 3650);
    range(c, "omen-days", 0, 365);
    range(c, "omen-minutes", 0, 525600);
    range(c, "base-daily-chance", 0, 1);
    range(c, "threat-weight", 0, 10);
    range(c, "threat-decay-percent", 0, 100);
    range(c, "heavy-after-quiet-days", 0, 3650);
    range(c, "protection.radius", 1, 16);
    range(c, "protection.share", 0, 1);
    range(c, "protection.cache-seconds", 1, 3600);
    range(c, "harvest-minimum", 0, 64);
    for (Type t : Type.values()) {
      range(c, "types." + t.key + ".duration-days", 0.05, 365);
      range(c, "types." + t.key + ".duration-minutes", 1, 525600);
      for (String key : List.of("growth", "crops", "breeding", "meat", "natural-spawn"))
        range(c, "types." + t.key + "." + key, 0, 1000);
    }
    range(c, "invasion.wave-interval-seconds", 10, 3600);
    range(c, "invasion.wave-size", 1, 32);
    range(c, "invasion.per-player-cap", 1, 64);
    range(c, "invasion.global-cap", 1, 1000);
    range(c, "invasion.min-distance", 4, 64);
    range(c, "invasion.max-distance", 5, 96);
    if (c.getDouble("invasion.min-distance", 20) >= c.getDouble("invasion.max-distance", 30))
      throw new IllegalArgumentException("errors.config");
    range(c, "invasion.kills-per-step", 1, 100000);
    range(c, "invasion.step-percent", 0, 100);
    range(c, "invasion.credit-per-player-hour", 1, 100000);
    for (String name : c.getStringList("invasion.mobs"))
      try {
        var type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        if (!type.isAlive() || !type.isSpawnable()) throw new IllegalArgumentException();
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("errors.config", e);
      }
    for (Disease d : Disease.values()) {
      String mode = c.getString("diseases." + d.key + ".mode", "individual");
      if (!Set.of("individual", "world", "server").contains(mode.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("errors.config");
      range(c, "diseases." + d.key + ".infect-chance-per-day", 0, 100);
      range(c, "diseases." + d.key + ".immune-days", 0, 365);
      for (String name : c.getStringList("diseases." + d.key + ".carriers"))
        if (Type.of(name) == null) throw new IllegalArgumentException("errors.config");
    }
    range(c, "diseases.hunger.exhaustion-multiplier", 1, 5);
    range(c, "diseases.hunger.monotony-foods", 2, 20);
    range(c, "diseases.hunger.bonus-food", 0, 20);
    range(c, "diseases.hunger.cure-different-foods", 2, 10);
    range(c, "diseases.scurvy.no-fresh-days", 1, 365);
    range(c, "diseases.scurvy.cure-count", 1, 64);
    range(c, "diseases.scurvy.weakness-after-days", 0, 365);
    for (String list :
        List.of(
            "meat-items",
            "diseases.hunger.double-foods",
            "diseases.scurvy.fresh-foods",
            "types.bugs.crop-list",
            "types.infertile.crop-list"))
      for (String name : c.getStringList(list))
        if (Material.matchMaterial(name) == null) throw new IllegalArgumentException("errors.config");
  }

  private static void range(ConfigurationSection c, String path, double min, double max) {
    if (!c.contains(path)) return;
    if (!(c.get(path) instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() < min
        || n.doubleValue() > max) throw new IllegalArgumentException("errors.config");
  }
}
