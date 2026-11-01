package dev.lovelace.loveessential.portal;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/** Typed particle payloads for the running Paper API, including the particles added in 26.2. */
final class PortalParticles {
  private static final Set<Class<?>> TYPES =
      Set.of(
          Void.class,
          Color.class,
          Float.class,
          Integer.class,
          Particle.DustOptions.class,
          Particle.DustTransition.class,
          Particle.Spell.class,
          Particle.Trail.class,
          Particle.Geyser.class,
          Particle.GeyserBase.class,
          Vibration.class,
          BlockData.class,
          ItemStack.class);
  static final List<Particle> ALL = Arrays.stream(Particle.values()).toList();

  private PortalParticles() {}

  static boolean supported(Particle particle) {
    return TYPES.contains(particle.getDataType());
  }

  static Particle parse(String name) {
    if (name == null) throw new IllegalArgumentException("portal-ui.invalid");
    try {
      Particle particle = Particle.valueOf(name.toUpperCase(Locale.ROOT));
      if (supported(particle)) return particle;
    } catch (IllegalArgumentException ignored) {
    }
    throw new IllegalArgumentException("portal-ui.invalid");
  }

  static String group(Particle p) {
    String name = p.name();
    if (p.getDataType() == BlockData.class || p.getDataType() == ItemStack.class) return "blocks";
    if (name.contains("WATER")
        || name.contains("BUBBLE")
        || name.contains("SPLASH")
        || name.contains("DOLPHIN")
        || name.contains("SQUID")
        || name.contains("GEYSER")) return "water";
    if (name.contains("SMOKE")
        || name.contains("CLOUD")
        || name.contains("GAS")
        || name.contains("ASH")
        || name.contains("GUST")) return "air";
    if (name.contains("FIRE") || name.contains("FLAME") || name.contains("LAVA")) return "fire";
    if (name.contains("LEAVES")
        || name.contains("SPORE")
        || name.contains("HONEY")
        || name.contains("NECTAR")
        || name.contains("SNOW")
        || name.contains("FIREFLY")
        || name.contains("COMPOST")
        || name.contains("MYCELIUM")) return "nature";
    if (name.contains("CRIT")
        || name.contains("HIT")
        || name.contains("DAMAGE")
        || name.contains("ATTACK")
        || name.contains("EXPLOSION")) return "combat";
    return "magic";
  }

  static boolean colored(Particle p) {
    return Set.of(
            Color.class,
            Particle.DustOptions.class,
            Particle.DustTransition.class,
            Particle.Spell.class,
            Particle.Trail.class)
        .contains(p.getDataType());
  }

  static int limit(Particle particle, int budget) {
    return particle.name().contains("EMITTER")
            || particle.name().contains("GEYSER")
            || particle.name().contains("EXPLOSION")
            || particle == Particle.ELDER_GUARDIAN
            || particle.getDataType() == Particle.Trail.class
            || particle.getDataType() == Vibration.class
        ? Math.min(8, Math.max(0, budget))
        : Math.max(0, budget);
  }

  static Object data(Particle particle, String hex, Location target) {
    Class<?> type = particle.getDataType();
    Color color = Color.fromRGB(Integer.parseInt(hex, 16));
    if (type == Void.class) return null;
    if (type == Color.class) return color;
    if (type == Particle.DustOptions.class) return new Particle.DustOptions(color, .8f);
    if (type == Particle.DustTransition.class)
      return new Particle.DustTransition(color, Color.WHITE, .8f);
    if (type == Particle.Spell.class) return new Particle.Spell(color, 1f);
    if (type == Particle.Trail.class) return new Particle.Trail(target.clone(), color, 20);
    if (type == Particle.Geyser.class) return new Particle.Geyser(1);
    if (type == Particle.GeyserBase.class) return new Particle.GeyserBase(1, 1f);
    if (type == Vibration.class)
      return new Vibration(
          target.clone(),
          new Vibration.Destination.BlockDestination(target.clone().add(0, 1, 0)),
          20);
    if (type == BlockData.class) return Material.AMETHYST_BLOCK.createBlockData();
    if (type == ItemStack.class) return new ItemStack(Material.AMETHYST_SHARD);
    if (type == Float.class) return 1f;
    if (type == Integer.class) return 0;
    throw new IllegalArgumentException("portal-ui.invalid");
  }
}
