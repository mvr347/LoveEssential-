package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.integration.LoveBridge;
import java.util.*;
import org.bukkit.entity.Player;

/** Shared rules apply to player actions and presentation, never to the staff editor itself. */
public final class ModulePolicy {
  public static final Set<String> STATES =
      Set.of(
          "normal",
          "combat",
          "afk",
          "vanished",
          "flying",
          "gliding",
          "swimming",
          "sneaking",
          "sleeping",
          "vehicle",
          "ground",
          "airborne",
          "authenticated",
          "unauthenticated");

  public static final class Denied extends IllegalArgumentException {
    private final Map<String, String> variables;

    public Denied(String key, Map<String, String> variables) {
      super(key);
      this.variables = Map.copyOf(variables);
    }

    public Map<String, String> variables() {
      return variables;
    }
  }

  private static final com.github.benmanes.caffeine.cache.LoadingCache<
          String, java.util.regex.Pattern>
      WORLD_PATTERNS =
          com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
              .maximumSize(512)
              .build(
                  pattern ->
                      java.util.regex.Pattern.compile(
                          java.util.Arrays.stream(pattern.split("\\*", -1))
                              .map(java.util.regex.Pattern::quote)
                              .collect(java.util.stream.Collectors.joining(".*"))));

  private final LoveEssentialPlugin plugin;
  private final LoveBridge love;
  private final Gameplay gameplay;
  private final dev.lovelace.loveessential.lang.Lang lang;

  public ModulePolicy(
      LoveEssentialPlugin plugin,
      LoveBridge love,
      Gameplay gameplay,
      dev.lovelace.loveessential.lang.Lang lang) {
    this.plugin = plugin;
    this.love = love;
    this.gameplay = gameplay;
    this.lang = lang;
  }

  public boolean allowed(String module, Player player) {
    return denial(module, player) == null;
  }

  public boolean environmentAllowed(String module, Player player) {
    return denial(module, player, false) == null;
  }

  public void require(String module, Player player) {
    var reason = denial(module, player);
    if (reason != null) throw reason;
  }

  public Denied denial(String module, Player p) {
    return denial(module, p, true);
  }

  /** World lists of a module apply to where a teleport lands, not only to where it starts. */
  public void requireDestination(String module, String world) {
    var c = plugin.getConfig();
    String path = dev.lovelace.loveessential.config.ModuleConfigs.prefix(module) + ".access.";
    var denied = c.getStringList(path + "denied-worlds");
    var allowed = c.getStringList(path + "allowed-worlds");
    if (matches(denied, world) || !allowed.isEmpty() && !matches(allowed, world))
      throw new Denied("module-access.world", Map.of("world", world));
  }

  private Denied denial(String module, Player p, boolean evaluateStates) {
    if (!plugin.module(module)) return new Denied("errors.module-disabled", Map.of());
    var c = plugin.getConfig();
    String path = dev.lovelace.loveessential.config.ModuleConfigs.prefix(module) + ".access.";
    String world = p.getWorld().getName();
    var denied = c.getStringList(path + "denied-worlds");
    var allowed = c.getStringList(path + "allowed-worlds");
    if (matches(denied, world) || !allowed.isEmpty() && !matches(allowed, world))
      return new Denied("module-access.world", Map.of("world", world));
    String mode = p.getGameMode().name();
    var denyModes = c.getStringList(path + "denied-gamemodes");
    var allowModes = c.getStringList(path + "allowed-gamemodes");
    if (matches(denyModes, mode) || !allowModes.isEmpty() && !matches(allowModes, mode))
      return new Denied(
          "module-access.gamemode",
          Map.of("mode", label("gamemodes", mode.toLowerCase(Locale.ROOT))));
    if (!evaluateStates) return null;
    var blocked = c.getStringList(path + "denied-states");
    var required = c.getStringList(path + "required-states");
    var any = c.getStringList(path + "allowed-states");
    if (blocked.isEmpty() && required.isEmpty() && any.isEmpty()) return null;
    var states = states(p);
    var deniedStates = blocked.stream().filter(states::contains).toList();
    if (!deniedStates.isEmpty())
      return new Denied("module-access.denied-state", Map.of("states", labels(deniedStates)));
    var missing = required.stream().filter(state -> !states.contains(state)).toList();
    if (!missing.isEmpty())
      return new Denied("module-access.required-state", Map.of("states", labels(missing)));
    if (!any.isEmpty() && any.stream().noneMatch(states::contains))
      return new Denied("module-access.allowed-state", Map.of("states", labels(any)));
    return null;
  }

  private String label(String group, String key) {
    return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        .serialize(lang.render("module-access." + group + "." + key));
  }

  private String labels(List<String> states) {
    return states.stream()
        .map(state -> label("states", state))
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private Set<String> states(Player p) {
    var result = new HashSet<String>();
    boolean combat = love.combat(p), afk = gameplay.afk(p), vanish = gameplay.vanished(p);
    if (combat) result.add("combat");
    if (afk) result.add("afk");
    if (vanish) result.add("vanished");
    if (p.isFlying()) result.add("flying");
    if (p.isGliding()) result.add("gliding");
    if (p.isSwimming()) result.add("swimming");
    if (p.isSneaking()) result.add("sneaking");
    if (p.isSleeping() || p.getPose() == org.bukkit.entity.Pose.SLEEPING) result.add("sleeping");
    if (p.isInsideVehicle()) result.add("vehicle");
    if (result.isEmpty()) result.add("normal");
    result.add(p.isOnGround() ? "ground" : "airborne");
    result.add(love.authenticated(p) ? "authenticated" : "unauthenticated");
    return Set.copyOf(result);
  }

  private static boolean matches(List<String> values, String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    return values.stream()
        .anyMatch(
            pattern ->
                WORLD_PATTERNS.get(pattern.toLowerCase(Locale.ROOT)).matcher(normalized).matches());
  }

  public static void validate(org.bukkit.configuration.ConfigurationSection c, String module) {
    String path = dev.lovelace.loveessential.config.ModuleConfigs.prefix(module) + ".access.";
    for (String field :
        List.of(
            "allowed-worlds",
            "denied-worlds",
            "allowed-gamemodes",
            "denied-gamemodes",
            "allowed-states",
            "denied-states",
            "required-states")) {
      if (c.contains(path + field) && !c.isList(path + field))
        throw new IllegalArgumentException("errors.config");
      var entries = c.getList(path + field);
      if (entries != null
          && (entries.size() > 256
              || entries.stream().anyMatch(value -> !(value instanceof String))))
        throw new IllegalArgumentException("errors.config");
      for (String entry : c.getStringList(path + field)) {
        if (entry.length() > 128 || entry.isBlank())
          throw new IllegalArgumentException("errors.config");
        if (field.endsWith("states") && !STATES.contains(entry))
          throw new IllegalArgumentException("errors.config");
        if (field.endsWith("gamemodes"))
          try {
            org.bukkit.GameMode.valueOf(entry.toUpperCase(Locale.ROOT));
          } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("errors.config");
          }
      }
    }
  }
}
