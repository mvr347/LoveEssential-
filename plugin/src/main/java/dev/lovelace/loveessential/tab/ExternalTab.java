package dev.lovelace.loveessential.tab;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Decides whether another plugin already owns the player list, name tags and sidebar. In {@code auto}
 * mode LoveEssential takes these over only when none of the listed plugins is enabled; {@code on}
 * always takes over, {@code off} never does. Free of Bukkit so it can be tested.
 */
public final class ExternalTab {
  /** Plugins that draw the player list, name tags or a sidebar themselves. */
  public static final List<String> DEFAULTS =
      List.of(
          "TAB",
          "CMI",
          "TabList",
          "TabPlus",
          "AnimatedTab",
          "NametagEdit",
          "TitleManager",
          "SimpleTabList",
          "FeatherBoard",
          "AnimatedScoreboard");

  private ExternalTab() {}

  public enum Mode {
    AUTO,
    ON,
    OFF;

    public static Mode parse(String value) {
      if (value == null) return AUTO;
      return switch (value.strip().toLowerCase(Locale.ROOT)) {
        case "on", "true", "always" -> ON;
        case "off", "false", "never" -> OFF;
        default -> AUTO;
      };
    }
  }

  /** The first listed plugin that is enabled, or null. */
  public static String found(List<String> names, Predicate<String> enabled) {
    for (String name : names) if (enabled.test(name)) return name;
    return null;
  }

  /** Whether LoveEssential should stay passive: the mode and the plugins found decide. */
  public static boolean deferred(Mode mode, List<String> names, Predicate<String> enabled) {
    return switch (mode) {
      case ON -> false;
      case OFF -> true;
      case AUTO -> found(names, enabled) != null;
    };
  }
}
