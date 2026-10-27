package dev.lovelace.loveessential.tab;

import com.github.benmanes.caffeine.cache.*;
import dev.lovelace.loveessential.api.Condition;
import dev.lovelace.loveessential.api.LoveEssentialService.Verdict;
import dev.lovelace.loveessential.condition.ConditionEngine;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.lovecore.api.display.DisplayPrefs;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.*;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Renders TAB, scoreboard and bossbar texts. PlaceholderAPI values are cached per placeholder with
 * the TTL from {@code tab.placeholder-refresh}; output replacements and named conditions are
 * applied here, so every module shows the same text for the same placeholder.
 */
public final class Placeholders {
  /** Animation frame time used when a name has no {@code animations:} entry in tab.yml. */
  static final long LEGACY_ANIMATION_MS = 500;

  private final Lang lang;
  private final LoveBridge love;
  private final Predicate<Player> afk;
  private final ConditionEngine engine;
  private final Supplier<ConfigurationSection> tab;

  /** A cached PlaceholderAPI value and the time it may be reused. */
  private record Timed(String value, long ttlNanos) {}

  private final Cache<String, Timed> cache =
      Caffeine.newBuilder()
          .maximumSize(4096)
          .expireAfter(
              new Expiry<String, Timed>() {
                @Override
                public long expireAfterCreate(String key, Timed value, long now) {
                  return value.ttlNanos();
                }

                @Override
                public long expireAfterUpdate(
                    String key, Timed value, long now, long currentDuration) {
                  return value.ttlNanos();
                }

                @Override
                public long expireAfterRead(
                    String key, Timed value, long now, long currentDuration) {
                  return currentDuration;
                }
              })
          .build();

  private volatile PlaceholderRefresh refresh = new PlaceholderRefresh(200, Map.of());
  private volatile Map<String, PlaceholderOutput.Table> replacements = Map.of();
  private java.util.function.Function<UUID, Map<String, String>> playerValues = id -> Map.of();
  private java.util.function.Function<Player, String> groupOf = p -> "";
  private java.util.function.Consumer<String> unresolvedWarning = text -> {};
  private final Set<String> unresolved = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** The TAB group of a player, for the built-in {@code %group%}. */
  public void groups(java.util.function.Function<Player, String> provider) {
    groupOf = provider;
  }

  /** Called once when PlaceholderAPI is missing, with a ready-to-log message. */
  public void onUnresolved(java.util.function.Consumer<String> warning) {
    unresolvedWarning = warning;
  }

  /** Tokens that stayed unresolved since the last {@link #clear()}, for {@code /le tab debug}. */
  public Set<String> unresolvedTokens() {
    return Set.copyOf(unresolved);
  }

  public void playerValues(java.util.function.Function<UUID, Map<String, String>> provider) {
    playerValues = provider;
  }

  public Map<String, String> playerValues(UUID id) {
    return playerValues.apply(id);
  }

  public List<Component> renderLines(String key, Player viewer, Player target) {
    return lang.lines(key).stream().map(line -> renderText(line, viewer, target, 0)).toList();
  }

  private java.util.function.Function<Player, Component> nickname =
      p -> Component.text(p.getName());
  private java.util.function.BooleanSupplier nicknameTab = () -> false;

  public void nicknames(
      java.util.function.Function<Player, Component> provider,
      java.util.function.BooleanSupplier tab) {
    nickname = provider;
    nicknameTab = tab;
  }

  public Component renderName(String key, Player viewer, Player target) {
    String template = lang.text(key);
    if (nicknameTab.getAsBoolean()) template = template.replace("<player>", "<nickname>");
    return renderText(template, viewer, target, 0);
  }

  private final MiniMessage mm = MiniMessage.miniMessage();
  // Values that come back from PlaceholderAPI can be chosen by players (titles, subnames), so they
  // may only style text: click, hover, insertion, font, keybind and similar tags stay plain text.
  private static final MiniMessage STYLE_ONLY =
      MiniMessage.builder()
          .tags(
              TagResolver.builder()
                  .resolver(net.kyori.adventure.text.minimessage.tag.standard.StandardTags.color())
                  .resolver(
                      net.kyori.adventure.text.minimessage.tag.standard.StandardTags.decorations())
                  .resolver(
                      net.kyori.adventure.text.minimessage.tag.standard.StandardTags.gradient())
                  .resolver(
                      net.kyori.adventure.text.minimessage.tag.standard.StandardTags.rainbow())
                  .resolver(
                      net.kyori.adventure.text.minimessage.tag.standard.StandardTags.transition())
                  .resolver(net.kyori.adventure.text.minimessage.tag.standard.StandardTags.reset())
                  .build())
          .build();

  public Placeholders(
      Lang lang,
      LoveBridge love,
      Predicate<Player> afk,
      ConditionEngine engine,
      Supplier<ConfigurationSection> tab) {
    this.lang = lang;
    this.love = love;
    this.afk = afk;
    this.engine = engine;
    this.tab = tab;
  }

  /** Applies the cache TTLs and output replacements from the current tab.yml. */
  public void configure(PlaceholderRefresh value, Map<String, PlaceholderOutput.Table> tables) {
    refresh = value;
    replacements = Map.copyOf(tables);
    cache.invalidateAll();
  }

  public String papi(Player viewer, Player target, String placeholder) {
    if (placeholder.startsWith("%loveessential_") && placeholder.endsWith("%")) {
      String key = placeholder.substring(15, placeholder.length() - 1);
      // Live state is read directly, the same way LoveExpansion reports it.
      if (key.equals("afk")) return Boolean.toString(afk.test(target));
      if (key.equals("combat")) return Boolean.toString(love.combat(target));
      return playerValues.apply(target.getUniqueId()).getOrDefault(key, "");
    }
    if (placeholder.startsWith("%") && placeholder.endsWith("%") && placeholder.length() > 2) {
      String name = placeholder.substring(1, placeholder.length() - 1);
      if (TabSyntax.builtin(name)) return builtin(name.toLowerCase(Locale.ROOT), viewer, target);
    }
    if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
        || placeholder.contains("%loveessential_")) {
      noteUnresolved(placeholder, "PlaceholderAPI is not enabled");
      return "";
    }
    String key = viewer.getUniqueId() + ":" + target.getUniqueId() + ":" + placeholder;
    Timed cached = cache.getIfPresent(key);
    if (cached != null) return cached.value();
    String value =
        PlaceholderAPI.setRelationalPlaceholders(
            viewer, target, PlaceholderAPI.setPlaceholders(target, placeholder));
    if (value == null || value.equals(placeholder)) {
      noteUnresolved(placeholder, "no PlaceholderAPI expansion answered it");
      value = "";
    }
    long ttlNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(refresh.ttl(placeholder));
    cache.put(key, new Timed(value, ttlNanos));
    return value;
  }

  private boolean missingPapiReported;

  /**
   * Remembers a token that produced nothing, for {@code /le tab debug}. Absent plugins make many
   * tokens legitimately empty, so only a missing PlaceholderAPI itself is logged, and only once.
   */
  private void noteUnresolved(String token, String reason) {
    unresolved.add(token);
    if (!missingPapiReported && !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      missingPapiReported = true;
      unresolvedWarning.accept("PlaceholderAPI is not enabled: %...% placeholders render as empty text");
    }
  }

  private static final java.time.format.DateTimeFormatter TIME_FALLBACK =
      java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final java.time.format.DateTimeFormatter DATE_FALLBACK =
      java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /** TAB's own placeholders ({@code %player%}, {@code %ping%}, ...), resolved without PlaceholderAPI. */
  private String builtin(String name, Player viewer, Player target) {
    return switch (name) {
      case "player" -> target.getName();
      case "displayname" -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
          .serialize(nickname.apply(target));
      case "ping" -> Integer.toString(target.getPing());
      case "health" -> Integer.toString((int) Math.ceil(target.getHealth()));
      case "world" -> target.getWorld().getName();
      case "online" ->
          Long.toString(Bukkit.getOnlinePlayers().stream().filter(viewer::canSee).count());
      case "group" -> groupOf.apply(target);
      case "time" -> now("time-format", "HH:mm:ss", TIME_FALLBACK);
      case "date" -> now("date-format", "dd.MM.yyyy", DATE_FALLBACK);
      default -> "";
    };
  }

  /** Server time shifted by {@code placeholders.time-offset} hours; a bad format falls back to the default. */
  private String now(String key, String pattern, java.time.format.DateTimeFormatter fallback) {
    var tabSection = tab.get();
    var s = tabSection == null ? null : tabSection.getConfigurationSection("placeholders");
    var moment =
        java.time.ZonedDateTime.now().plusHours(s == null ? 0 : (long) s.getDouble("time-offset", 0));
    try {
      return java.time.format.DateTimeFormatter.ofPattern(s == null ? pattern : s.getString(key, pattern))
          .format(moment);
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      return fallback.format(moment);
    }
  }

  /** The raw PlaceholderAPI value after the configured output replacement, if there is one. */
  String replaced(String token, String raw) {
    var table = replacements.get(token);
    return table == null ? raw : PlaceholderOutput.apply(table, raw);
  }

  public Component render(String key, Player viewer, Player target) {
    return renderText(lang.text(key), viewer, target, 0);
  }

  /** Tab list name; {@code <player>} shows the nickname when nicknames are used in the tab. */
  public Component renderTabName(String template, Player viewer, Player target) {
    return renderTabName(template, viewer, target, NONE);
  }

  /** Tab list name with the given elements ({@code <clan>}, {@code <suffix>}) left out. */
  public Component renderTabName(
      String template, Player viewer, Player target, Set<DisplayPrefs.Element> hidden) {
    String text = nicknameTab.getAsBoolean() ? template.replace("<player>", "<nickname>") : template;
    return renderText(text, viewer, target, 0, hidden);
  }

  /** Plain text of a template, for settings that must parse as a number or an enum name. */
  public String plain(String template, Player viewer, Player target) {
    return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        .serialize(renderText(template, viewer, target, 0));
  }

  public Component renderRaw(String template, Player viewer, Player target) {
    return renderText(template, viewer, target, 0);
  }

  /**
   * Like {@link #renderRaw(String, Player, Player)}, with the given elements left out: {@code <clan>}
   * and {@code <suffix>} render as nothing when their element is in {@code hidden}.
   */
  public Component renderRaw(
      String template, Player viewer, Player target, Set<DisplayPrefs.Element> hidden) {
    return renderText(template, viewer, target, 0, hidden);
  }

  /** Evaluates a named tab condition; false when the name does not exist. */
  public boolean condition(String name, Player viewer, Player target) {
    var definition = definition(name);
    if (definition == null) return false;
    return TextConditions.evaluate(
        definition,
        text -> resolveOperand(text, viewer, target),
        node -> target.hasPermission(node),
        id -> engineAllows(id, target));
  }

  private static final java.util.regex.Pattern TOKEN =
      java.util.regex.Pattern.compile("%([A-Za-z0-9_:.]+)%");

  /** Resolves {@code %...%} operands of conditions; plain words are returned unchanged. */
  private String resolveOperand(String text, Player viewer, Player target) {
    var matcher = TOKEN.matcher(text);
    var out = new StringBuilder();
    while (matcher.find()) {
      String token = "%" + matcher.group(1) + "%";
      String value = replaced(token, papi(viewer, target, token));
      matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value.strip()));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** {@code engine:<id>} checks use the JSON condition stored in tab.engine-conditions. */
  private boolean engineAllows(String id, Player target) {
    var section = tab.get();
    var engines = section == null ? null : section.getConfigurationSection("engine-conditions");
    String json = engines == null ? null : engines.getString(id);
    if (json == null || json.isBlank()) return false;
    return engine.test(target, new com.google.gson.Gson().fromJson(json, Condition.class))
        == Verdict.ALLOW;
  }

  private TextConditions.Definition definition(String name) {
    var section = tab.get();
    var conditions = section == null ? null : section.getConfigurationSection("conditions");
    var s = conditions == null ? null : conditions.getConfigurationSection(name);
    if (s == null) return null;
    var checks = new ArrayList<TextConditions.Check>();
    for (String check : s.getStringList("checks")) checks.add(TextConditions.parse(check));
    return new TextConditions.Definition(
        TextConditions.Type.parse(s.getString("type", "and")),
        checks,
        s.getString("yes", ""),
        s.getString("no", ""));
  }

  /** Validates every named condition; used when tab.yml is loaded. */
  public static void validateConditions(ConfigurationSection tab) {
    var conditions = tab.getConfigurationSection("conditions");
    if (conditions == null) return;
    for (String name : conditions.getKeys(false)) {
      var s = conditions.getConfigurationSection(name);
      if (s == null || !name.matches("[A-Za-z0-9_-]{1,48}"))
        throw new IllegalArgumentException("errors.config");
      TextConditions.Type.parse(s.getString("type", "and"));
      for (String check : s.getStringList("checks")) TextConditions.parse(check);
    }
  }

  private Component conditionText(
      String name, Player viewer, Player target, int depth, Set<DisplayPrefs.Element> hidden) {
    var definition = definition(name);
    if (definition == null) return Component.empty();
    boolean holds = condition(name, viewer, target);
    String text = holds ? definition.yes() : definition.no();
    return text.isEmpty() ? Component.empty() : renderText(text, viewer, target, depth + 1, hidden);
  }

  private static final Set<DisplayPrefs.Element> NONE = Set.of();

  /** {@code tab.legacy-colors}: read the {@code &c} color codes of the original TAB; on by default. */
  private boolean legacyColors() {
    var section = tab.get();
    return section == null || section.getBoolean("legacy-colors", true);
  }

  private Component renderText(String template, Player viewer, Player target, int depth) {
    return renderText(template, viewer, target, depth, NONE);
  }

  private Component renderText(
      String template, Player viewer, Player target, int depth, Set<DisplayPrefs.Element> hidden) {
    if (depth > 4) return Component.empty();
    final Player headViewer = viewer, headTarget = target;
    template =
        TabSyntax.heads(
            template,
            name ->
                switch (name) {
                  case "player", "real_name" -> headTarget.getName();
                  default -> {
                    String token = "%" + name + "%";
                    String text = replaced(token, papi(headViewer, headTarget, token));
                    yield token.equals(text) ? "" : text;
                  }
                });
    template = TabSyntax.tokens(legacyColors() ? TabSyntax.legacy(template) : template);
    var b = TagResolver.builder();
    Map<String, java.util.function.Supplier<String>> variables =
        Map.ofEntries(
            Map.entry("player", target::getName),
            Map.entry("viewer", viewer::getName),
            Map.entry("world", () -> target.getWorld().getName()),
            Map.entry(
                "online",
                () ->
                    Long.toString(
                        Bukkit.getOnlinePlayers().stream().filter(viewer::canSee).count())),
            Map.entry(
                "visible_online",
                () ->
                    Long.toString(
                        Bukkit.getOnlinePlayers().stream().filter(viewer::canSee).count())),
            Map.entry("max_players", () -> Integer.toString(Bukkit.getMaxPlayers())),
            Map.entry("ping", () -> Integer.toString(target.getPing())),
            Map.entry(
                "clan",
                () ->
                    hidden.contains(DisplayPrefs.Element.CLAN_TAG)
                        ? ""
                        : love.clan(target).orElse("")),
            Map.entry("territory", () -> love.territory(target)),
            Map.entry(
                "reputation",
                () -> {
                  var reputation = love.reputation(target);
                  return reputation.isPresent() ? Integer.toString(reputation.getAsInt()) : "";
                }));
    playerValues
        .apply(target.getUniqueId())
        .forEach((k, v) -> b.resolver(Placeholder.unparsed(k, v)));
    variables.forEach(
        (key, supplier) ->
            b.resolver(
                TagResolver.resolver(
                    key, (args, context) -> Tag.inserting(Component.text(supplier.get())))));
    b.resolver(
        TagResolver.resolver("nickname", (args, context) -> Tag.inserting(nickname.apply(target))));
    b.resolver(Placeholder.unparsed("real_name", target.getName()));
    b.resolver(
        TagResolver.resolver(
            "afk",
            (args, context) ->
                Tag.inserting(
                    afk.test(target) ? lang.render("tab.status.afk") : Component.empty())));
    b.resolver(
        TagResolver.resolver(
            "suffix",
            (args, context) ->
                Tag.inserting(
                    hidden.contains(DisplayPrefs.Element.SUFFIX)
                        ? Component.empty()
                        : legacyOrMini(papi(viewer, target, "%lovesubnames_current%")))));
    b.resolver(
        TagResolver.resolver(
            "papi",
            (args, context) -> {
              String id = args.popOr("identifier").value();
              String fallback = args.hasNext() ? args.pop().value() : "";
              String token = "%" + id + "%", value = replaced(token, papi(viewer, target, token));
              return Tag.inserting(
                  value == null || value.isBlank() || value.equals(token)
                      ? Component.text(fallback)
                      : legacyOrMini(value));
            }));
    b.resolver(
        TagResolver.resolver(
            "condition",
            (args, context) -> {
              String name = args.popOr("condition name").value();
              return Tag.inserting(conditionText(name, viewer, target, depth, hidden));
            }));
    b.resolver(
        TagResolver.resolver(
            "animation",
            (args, context) -> {
              String name = args.popOr("animation name").value();
              var animation = animation(name);
              return Tag.inserting(
                  animation.frames().isEmpty()
                      ? Component.empty()
                      : renderText(
                          animation.frames()
                              .get(
                                  TabAnimations.frame(
                                      System.currentTimeMillis(),
                                      animation.intervalMillis(),
                                      animation.frames().size())),
                          viewer,
                          target,
                          depth + 1,
                          hidden));
            }));
    return mm.deserialize(template, b.build());
  }

  /** One animation: frame texts and the time each frame stays visible. */
  record Animation(List<String> frames, long intervalMillis) {}

  /**
   * Animation from {@code animations.<name>} in tab.yml. Names that are not configured fall back
   * to the older {@code tab.animations.<name>} language lines with a fixed 500 ms frame time.
   */
  Animation animation(String name) {
    var section = tab.get();
    var animations = section == null ? null : section.getConfigurationSection("animations");
    var s = animations == null ? null : animations.getConfigurationSection(name);
    if (s != null) {
      long interval = s.getLong("change-interval", LEGACY_ANIMATION_MS);
      return new Animation(List.copyOf(s.getStringList("texts")), interval);
    }
    return new Animation(lang.lines("tab.animations." + name), LEGACY_ANIMATION_MS);
  }

  public Component legacyOrMini(String value) {
    if (value == null || value.startsWith("%") && value.endsWith("%")) return Component.empty();
    return value.contains("§")
        ? LegacyComponentSerializer.legacySection().deserialize(value)
        : value.contains("&")
            ? LegacyComponentSerializer.legacyAmpersand().deserialize(value)
            : styleOnly(value);
  }

  /** MiniMessage limited to colors and decorations, for values players can influence. */
  public static Component styleOnly(String value) {
    return STYLE_ONLY.deserialize(value);
  }

  /** Drops cached values that involve one player, so a toggle shows at once without a global flush. */
  public void invalidate(UUID player) {
    String id = player.toString();
    cache.asMap().keySet().removeIf(k -> k.startsWith(id + ":") || k.contains(":" + id + ":"));
  }

  public void clear() {
    cache.invalidateAll();
    unresolved.clear();
  }
}
