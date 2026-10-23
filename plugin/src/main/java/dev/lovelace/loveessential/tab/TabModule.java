package dev.lovelace.loveessential.tab;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.api.TabService;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.module.Visibility;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

/**
 * TAB list: names, order, header and footer, nametags, objectives, bossbars and the per-world
 * player list. Properties come from groups, users and worlds in tab.yml. Each packet-bound value is
 * sent only when it differs from the last value sent to that viewer. Main thread only.
 */
public final class TabModule implements Listener, AutoCloseable {
  /** Team names LoveEssential owns on a viewer's board. */
  static final String TEAM_PREFIX = "le_";

  static final String LIST_OBJECTIVE = "le_list", BELOW_OBJECTIVE = "le_below";

  /** Score entries that belong to the scoreboard module and must never be reset here. */
  static final String SIDEBAR_ENTRY_PREFIX = "le_sidebar_";

  static final String DEFAULT_GROUP = "_DEFAULT_";
  static final String WORLD_LIST_REASON = "world-list";
  static final Pattern PAPI_TOKEN = Pattern.compile("%([A-Za-z0-9_:.]+)%");

  private final LoveEssentialPlugin plugin;
  private final Lang lang;
  private final Placeholders placeholders;
  private final Visibility visibility;
  private final TabOverrides overrides;
  private final TabPreferences preferences;
  private final Map<UUID, View> views = new HashMap<>();
  private final Map<UUID, Sent> listState = new HashMap<>();
  private final Map<String, Long> lastRun = new HashMap<>();
  private final Stats stats = new Stats();
  private BukkitTask task;
  private boolean enabled;
  private java.util.function.BiConsumer<Player, Scoreboard> customSidebar;
  private java.util.function.BooleanSupplier customEnabled = () -> false;
  private java.util.function.Predicate<Scoreboard> companionBoard = b -> false;

  /** A viewer's own TAB board and everything last sent to it, used to send only changes. */
  private static final class View {
    final Scoreboard previous, board;
    final Map<String, Object> sent = new HashMap<>();
    final Map<String, BossBar> bars = new HashMap<>();

    View(Scoreboard before, Scoreboard board) {
      previous = before;
      this.board = board;
    }
  }

  /** The last name and order sent for a player, shared by every viewer. */
  private record Sent(Component name, int order) {}

  /**
   * Everything one player's TAB entries need, computed once per update cycle instead of once per
   * viewer. {@code group} is the configured group id, or empty when no group matched.
   */
  record Target(
      Player player,
      TabProperties.Resolved properties,
      String group,
      Component tabName,
      Component tagPrefix,
      Component tagSuffix,
      NamedTextColor color,
      boolean collision,
      boolean nametagVisible) {}

  public TabModule(
      LoveEssentialPlugin plugin,
      Lang lang,
      Placeholders placeholders,
      Visibility visibility,
      MainThread main,
      SqliteStore store) {
    this.plugin = plugin;
    this.lang = lang;
    this.placeholders = placeholders;
    this.visibility = visibility;
    this.overrides =
        new TabOverrides(
            System::nanoTime,
            () -> plugin.getConfig().getInt("tab.overrides.max-seconds", 600),
            () -> plugin.getConfig().getInt("tab.overrides.per-player-limit", 8));
    this.preferences = new TabPreferences(main, store);
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public Placeholders placeholders() {
    return placeholders;
  }

  /**
   * Load-time checks for tab.yml. A bad value fails the reload with an error instead of breaking the
   * TAB list while players are online.
   */
  public static void validate(ConfigurationSection config) {
    var tab = config.getConfigurationSection("tab");
    if (tab == null) return;
    var refresh = tab.getConfigurationSection("placeholder-refresh");
    if (refresh != null)
      for (String key : refresh.getKeys(false))
        if (!PlaceholderRefresh.valid(refresh.getLong(key)))
          throw new IllegalArgumentException("errors.config");
    var replacements = tab.getConfigurationSection("placeholder-output-replacements");
    if (replacements != null)
      for (String token : replacements.getKeys(false)) {
        var rules = replacements.getConfigurationSection(token);
        if (rules == null) throw new IllegalArgumentException("errors.config");
        var map = new LinkedHashMap<String, String>();
        for (String key : rules.getKeys(false)) map.put(key, rules.getString(key, ""));
        PlaceholderOutput.parse(map);
      }
    var animations = tab.getConfigurationSection("animations");
    if (animations != null)
      for (String name : animations.getKeys(false)) {
        var s = animations.getConfigurationSection(name);
        if (s == null || !name.matches("[A-Za-z0-9_-]{1,48}")) throw new IllegalArgumentException("errors.config");
        long interval = s.getLong("change-interval", 500);
        if (interval < TabAnimations.MIN_INTERVAL_MS || interval > TabAnimations.MAX_INTERVAL_MS)
          throw new IllegalArgumentException("errors.config");
      }
    Placeholders.validateConditions(tab);
    var engines = tab.getConfigurationSection("engine-conditions");
    if (engines != null)
      for (String id : engines.getKeys(false)) {
        try {
          var condition =
              new com.google.gson.Gson()
                  .fromJson(engines.getString(id, ""), dev.lovelace.loveessential.api.Condition.class);
          if (condition == null || condition.type() == null) throw new IllegalArgumentException("errors.config");
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("errors.config", e);
        }
      }
    bounded(tab, "overrides.max-seconds", 1, 86400);
    bounded(tab, "overrides.per-player-limit", 1, 32);
  }

  private static void bounded(ConfigurationSection tab, String path, long min, long max) {
    if (!tab.contains(path)) return;
    long value = tab.getLong(path, min - 1);
    if (value < min || value > max) throw new IllegalArgumentException("errors.config");
  }

  /** The public API implementation; registered with the services manager by the plugin. */
  public TabService service() {
    return overrides;
  }

  public TabPreferences preferences() {
    return preferences;
  }

  /** Shows a configured bossbar to every online player for {@code seconds}. */
  public void announce(String id, long seconds) {
    var s = section("bossbars." + id);
    if (s == null) throw new IllegalArgumentException("errors.input");
    for (var p : Bukkit.getOnlinePlayers())
      overrides.bossBar(
          plugin,
          p.getUniqueId(),
          0,
          java.time.Duration.ofSeconds(seconds),
          placeholders.renderRaw(expand(s.getString("text", "")), p, p),
          progress(p, s),
          color(p, s),
          overlay(p, s));
  }

  public void customSidebar(
      java.util.function.BiConsumer<Player, Scoreboard> render,
      java.util.function.BooleanSupplier enabled,
      java.util.function.Predicate<Scoreboard> owned) {
    customSidebar = render;
    customEnabled = enabled;
    companionBoard = owned;
  }

  public boolean managesBoards() {
    return enabled && task != null && !task.isCancelled();
  }

  public boolean owns(Player p) {
    var v = views.get(p.getUniqueId());
    return v != null && p.getScoreboard() == v.board;
  }

  public void start() {
    enabled = true;
    applyConfiguration();
    for (var p : Bukkit.getOnlinePlayers()) preferences.load(p.getUniqueId());
    if (task != null) task.cancel();
    task =
        Bukkit.getScheduler()
            .runTaskTimer(plugin, this::update, 1, Math.max(2, interval("refresh-ticks", 10)));
  }

  /** Placeholder TTLs and output replacements are read from tab.yml once per (re)load. */
  private void applyConfiguration() {
    var tab = section("");
    var refresh = tab == null ? null : tab.getConfigurationSection("placeholder-refresh");
    long defaultMillis = refresh == null ? 200 : refresh.getLong("default", 200);
    var perPlaceholder = new HashMap<String, Long>();
    if (refresh != null)
      for (String key : refresh.getKeys(false))
        if (!key.equals("default")) perPlaceholder.put(key, refresh.getLong(key));
    var tables = new HashMap<String, PlaceholderOutput.Table>();
    var replacements = tab == null ? null : tab.getConfigurationSection("placeholder-output-replacements");
    if (replacements != null)
      for (String token : replacements.getKeys(false)) {
        var rules = replacements.getConfigurationSection(token);
        if (rules == null) continue;
        var map = new LinkedHashMap<String, String>();
        for (String key : rules.getKeys(false)) map.put(key, rules.getString(key, ""));
        tables.put(token, PlaceholderOutput.parse(map));
      }
    placeholders.configure(new PlaceholderRefresh(defaultMillis, perPlaceholder), tables);
  }

  private ConfigurationSection section(String path) {
    return path.isEmpty()
        ? plugin.getConfig().getConfigurationSection("tab")
        : plugin.getConfig().getConfigurationSection("tab." + path);
  }

  private long interval(String key, long fallback) {
    return plugin.getConfig().getLong("tab." + key, fallback);
  }

  // ---------------------------------------------------------------- properties

  /** Resolves every layer for one player; the display conditions are checked here, once. */
  Target target(Player p) {
    var layers = new ArrayList<TabProperties.Layer>();
    layers.add(new TabProperties.Layer("api", overrides.properties(p.getUniqueId())));
    var users = section("users");
    String userKey = matchKey(users, p);
    if (userKey != null) {
      var user = users.getConfigurationSection(userKey);
      addLayer(layers, "user:" + userKey + "/world", user, p, true);
      addLayer(layers, "user:" + userKey, user, p, false);
    }
    var groups = section("groups");
    String group = matchGroup(groups, p);
    if (group != null) {
      var section = groups.getConfigurationSection(group);
      addLayer(layers, "group:" + group + "/world", section, p, true);
      addLayer(layers, "group:" + group, section, p, false);
    }
    var resolved = TabProperties.resolve(layers);
    return build(p, resolved, group == null ? "" : group);
  }

  private Target build(Player p, TabProperties.Resolved props, String group) {
    var tagPrefix = render(props.value(TabProperties.TAG_PREFIX).orElse(""), p);
    var tagSuffix = render(props.value(TabProperties.TAG_SUFFIX).orElse(""), p);
    String name = props.value(TabProperties.CUSTOM_TAB_NAME).orElse("<player>");
    var tabName =
        render(props.value(TabProperties.TAB_PREFIX).orElse(""), p)
            .append(placeholders.renderTabName(name, p, p))
            .append(render(props.value(TabProperties.TAB_SUFFIX).orElse(""), p));
    var nametag = section("nametag");
    boolean collision =
        props.value(TabProperties.COLLISION)
            .map(Boolean::parseBoolean)
            .orElse(nametag != null && nametag.getBoolean("collision", false));
    boolean visible = nametag == null || nametag.getBoolean("enabled", true);
    visible = visible && !overrides.nametagHidden(p.getUniqueId());
    visible = visible && !"never".equals(props.value(TabProperties.VISIBILITY).orElse("always"));
    visible = visible && !invisible(p, group, nametag);
    return new Target(
        p,
        props,
        group,
        tabName,
        tagPrefix,
        tagSuffix,
        lastColor(tagPrefix),
        collision,
        visible);
  }

  private boolean invisible(Player p, String group, ConfigurationSection nametag) {
    if (nametag == null) return false;
    for (String entry : nametag.getStringList("invisible-nametags")) {
      if (entry.equalsIgnoreCase(p.getName())
          || entry.equalsIgnoreCase(p.getUniqueId().toString())
          || !group.isEmpty() && entry.equalsIgnoreCase(group)) return true;
    }
    return false;
  }

  private Component render(String template, Player p) {
    return placeholders.renderRaw(expand(template == null ? "" : template), p, p);
  }

  /** Expands {@code <placeholder:id>} references from the tab.placeholders aliases. */
  private String expand(String template) {
    return expandAliases(section("placeholders"), template);
  }

  private void addLayer(
      List<TabProperties.Layer> out,
      String source,
      ConfigurationSection s,
      Player p,
      boolean perWorld) {
    if (s == null) return;
    ConfigurationSection values = perWorld ? null : s;
    if (perWorld) {
      var worlds = s.getConfigurationSection("per-world");
      values = worlds == null ? null : worlds.getConfigurationSection(p.getWorld().getName());
      if (values == null) return;
    }
    String condition = values.getString("display-condition", "");
    if (!condition.isBlank() && !placeholders.condition(condition, p, p)) return;
    var map = new HashMap<String, String>();
    for (String key : TabProperties.KEYS)
      if (values.contains(key))
        map.put(
            key,
            values.isList(key)
                ? String.join("\n", values.getStringList(key))
                : values.getString(key, ""));
    out.add(new TabProperties.Layer(source, map));
  }

  private static String matchKey(ConfigurationSection users, Player p) {
    if (users == null) return null;
    for (String key : users.getKeys(false))
      if (key.equalsIgnoreCase(p.getName()) || key.equalsIgnoreCase(p.getUniqueId().toString()))
        return key;
    return null;
  }

  /** The first group in configuration order whose {@code group.<id>} permission the player has. */
  private String matchGroup(ConfigurationSection groups, Player p) {
    if (groups == null) return null;
    for (String id : groups.getKeys(false)) {
      if (id.equals(DEFAULT_GROUP)) continue;
      var s = groups.getConfigurationSection(id);
      if (s == null) continue;
      String node = s.getString("permission", "");
      if (p.hasPermission(node.isBlank() ? "group." + id : node)) return id;
    }
    return groups.isConfigurationSection(DEFAULT_GROUP) ? DEFAULT_GROUP : null;
  }

  /** The last named color in a component, used as the nametag team color. */
  static NamedTextColor lastColor(Component component) {
    var found = new Object[1];
    scanColors(component, found);
    if (found[0] instanceof TextColor color) return NamedTextColor.nearestTo(color);
    return NamedTextColor.WHITE;
  }

  private static void scanColors(Component component, Object[] found) {
    if (component.color() != null) found[0] = component.color();
    for (var child : component.children()) scanColors(child, found);
  }

  // -------------------------------------------------------------- update cycle

  private void update() {
    if (!enabled) return;
    long started = System.nanoTime();
    List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
    var targets = new HashMap<UUID, Target>();
    for (var p : players) {
      long t0 = System.nanoTime();
      targets.put(p.getUniqueId(), target(p));
      stats.record("resolve", System.nanoTime() - t0);
    }
    var sorted = sorted(players, targets);
    long listStarted = System.nanoTime();
    boolean sortingEnabled = plugin.getConfig().getBoolean("tab.sorting-enabled", true);
    boolean names = plugin.getConfig().getBoolean("tab.name-enabled", true);
    for (int i = 0; i < sorted.size(); i++) {
      var target = targets.get(sorted.get(i).getUniqueId());
      if (!plugin.allows("tab", target.player())) continue;
      listEntry(target, i, sortingEnabled, names);
    }
    stats.record("list", System.nanoTime() - listStarted);
    if (dueFor("per-world-playerlist", interval("per-world-playerlist.refresh-ticks", 20)))
      worldLists(players);
    for (Player viewer : players) {
      long t0 = System.nanoTime();
      try {
        updateViewer(viewer, sorted, targets);
      } catch (RuntimeException e) {
        plugin
            .getLogger()
            .warning(lang.text("logs.tab-failed") + " " + e.getClass().getSimpleName());
      }
      stats.record("viewer", System.nanoTime() - t0);
    }
    stats.record("cycle", System.nanoTime() - started);
  }

  /** Whether a function with its own refresh interval is due; the first run is always due. */
  private boolean dueFor(String name, long intervalTicks) {
    long now = Bukkit.getCurrentTick();
    Long last = lastRun.get(name);
    if (last != null && now - last < Math.max(1, intervalTicks)) return false;
    lastRun.put(name, now);
    return true;
  }

  private void listEntry(Target target, int index, boolean sortingEnabled, boolean names) {
    var p = target.player();
    var previous = listState.get(p.getUniqueId());
    int order = sortingEnabled ? index : 0;
    if (sortingEnabled && (previous == null || previous.order() != order)) {
      p.setPlayerListOrder(order);
      sentValues++;
    }
    Component name = names ? target.tabName() : null;
    if (names && (previous == null || !Objects.equals(previous.name(), name))) {
      p.playerListName(name);
      sentValues++;
    }
    listState.put(p.getUniqueId(), new Sent(name, order));
  }

  /** Players sorted by the configured criteria; each value is resolved once per player. */
  private List<Player> sorted(List<Player> players, Map<UUID, Target> targets) {
    var keys = plugin.getConfig().getStringList("tab.sorting");
    var needed = new LinkedHashSet<String>();
    for (String key : keys) {
      String token = TabSort.placeholderOf(key);
      if (token != null) needed.add(token);
    }
    var permissions = new LinkedHashSet<String>();
    for (String key : keys) permissions.addAll(TabSort.permissionsOf(key));
    Map<UUID, TabSort.Entry> entries = new HashMap<>();
    for (var p : players) {
      var data = new HashMap<String, String>();
      for (String token : needed) data.put(token, placeholders.papi(p, p, token));
      var granted = new HashSet<String>();
      for (String node : permissions) if (p.hasPermission(node)) granted.add(node);
      var target = targets.get(p.getUniqueId());
      int group = groupIndex(target.group());
      entries.put(
          p.getUniqueId(), new TabSort.Entry(p.getUniqueId(), p.getName(), group, data, granted));
    }
    var comparator = TabSort.comparator(keys);
    var result = new ArrayList<>(players);
    result.sort((a, b) -> comparator.compare(entries.get(a.getUniqueId()), entries.get(b.getUniqueId())));
    return result;
  }

  /** Groups sort in configuration order; players without a group come last. */
  private int groupIndex(String group) {
    if (group.isEmpty()) return Integer.MAX_VALUE;
    if (group.equals(DEFAULT_GROUP)) return Integer.MAX_VALUE - 1;
    var groups = section("groups");
    int index = 0;
    for (String id : groups == null ? Set.<String>of() : groups.getKeys(false)) {
      if (id.equals(group)) return index;
      index++;
    }
    return Integer.MAX_VALUE;
  }

  private void updateViewer(Player viewer, List<Player> sorted, Map<UUID, Target> targets) {
    if (!plugin.allows("tab", viewer)) {
      release(viewer);
      return;
    }
    var view = views.get(viewer.getUniqueId());
    if (view == null) {
      var board = Bukkit.getScoreboardManager().getNewScoreboard();
      view = new View(viewer.getScoreboard(), board);
      views.put(viewer.getUniqueId(), view);
    }
    if (plugin.getConfig().getBoolean("tab.header-footer.enabled", true)
        && dueFor(
            "header-footer:" + viewer.getUniqueId(),
            interval("header-footer.refresh-ticks", 20))) headerFooter(viewer, view, targets);
    boolean respect = plugin.getConfig().getBoolean("tab.respect-other-scoreboards", true);
    if (!respect
        || companionBoard.test(viewer.getScoreboard())
        || viewer.getScoreboard().equals(view.board)
        || viewer.getScoreboard().equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
      viewer.setScoreboard(view.board);
      if (dueFor("nametag:" + viewer.getUniqueId(), interval("nametag.refresh-ticks", 10)))
        teams(viewer, view, sorted, targets);
      if (customSidebar != null && customEnabled.getAsBoolean()) customSidebar.accept(viewer, view.board);
      objective(viewer, view, sorted, "playerlist-objective", DisplaySlot.PLAYER_LIST, LIST_OBJECTIVE);
      objective(viewer, view, sorted, "below-name", DisplaySlot.BELOW_NAME, BELOW_OBJECTIVE);
    }
    if (dueFor("bossbar:" + viewer.getUniqueId(), interval("bossbar-refresh-ticks", 20)))
      bossbars(viewer, view);
  }

  private void headerFooter(Player viewer, View view, Map<UUID, Target> targets) {
    var props = targets.get(viewer.getUniqueId()).properties();
    Component header = placeholders.renderRaw(expand(props.value(TabProperties.HEADER).orElse("")), viewer, viewer);
    Component footer = placeholders.renderRaw(expand(props.value(TabProperties.FOOTER).orElse("")), viewer, viewer);
    if (!Objects.equals(view.sent.get("header"), header) || !Objects.equals(view.sent.get("footer"), footer)) {
      viewer.sendPlayerListHeaderAndFooter(header, footer);
      sentValues++;
      view.sent.put("header", header);
      view.sent.put("footer", footer);
    }
  }

  private void teams(Player viewer, View view, List<Player> sorted, Map<UUID, Target> targets) {
    boolean nametags = plugin.getConfig().getBoolean("tab.nametag.enabled", true);
    Set<String> expected = new HashSet<>();
    boolean hideAll = preferences.get(viewer.getUniqueId()).nametagsHidden();
    if (nametags)
      for (int i = 0; i < sorted.size(); i++) {
        Player target = sorted.get(i);
        if (!viewer.canSee(target)) continue;
        var t = targets.get(target.getUniqueId());
        String id = TEAM_PREFIX + String.format(Locale.ROOT, "%05d", i);
        expected.add(id);
        Team existing = view.board.getTeam(id);
        final Team team = existing != null ? existing : view.board.registerNewTeam(id);
        if (team.getEntries().size() != 1 || !team.hasEntry(target.getName())) {
          for (String old : new HashSet<>(team.getEntries())) team.removeEntry(old);
          team.addEntry(target.getName());
        }
        setSent(view, id + ".prefix", t.tagPrefix(), () -> team.prefix(t.tagPrefix()));
        setSent(view, id + ".suffix", t.tagSuffix(), () -> team.suffix(t.tagSuffix()));
        setSent(view, id + ".color", t.color(), () -> team.color(t.color()));
        boolean collision = t.collision();
        setSent(
            view,
            id + ".collision",
            collision,
            () ->
                team.setOption(
                    Team.Option.COLLISION_RULE,
                    collision ? Team.OptionStatus.ALWAYS : Team.OptionStatus.NEVER));
        boolean visible = t.nametagVisible() && !hideAll;
        setSent(
            view,
            id + ".visible",
            visible,
            () ->
                team.setOption(
                    Team.Option.NAME_TAG_VISIBILITY,
                    visible ? Team.OptionStatus.ALWAYS : Team.OptionStatus.NEVER));
      }
    for (Team team : new HashSet<>(view.board.getTeams()))
      if (!expected.contains(team.getName()) && team.getName().startsWith(TEAM_PREFIX)) {
        team.unregister();
        view.sent.keySet().removeIf(key -> key.startsWith(team.getName() + "."));
      }
  }

  /** Packet-bound values actually sent since start; unchanged values never increase it. */
  private long sentValues;

  public long sentValues() {
    return sentValues;
  }

  /** Calls {@code apply} only when {@code value} differs from the value last sent under this key. */
  private void setSent(View view, String key, Object value, Runnable apply) {
    if (Objects.equals(view.sent.get(key), value)) return;
    apply.run();
    view.sent.put(key, value);
    sentValues++;
  }

  private void objective(
      Player viewer,
      View view,
      List<Player> players,
      String path,
      DisplaySlot slot,
      String name) {
    var section = plugin.getConfig().getConfigurationSection("tab." + path);
    if (section == null || !section.getBoolean("enabled", false) || !allowedFor(viewer, section)) {
      removeObjective(view, name);
      return;
    }
    Objective existing = view.board.getObjective(name);
    final Objective obj =
        existing != null ? existing : view.board.registerNewObjective(name, Criteria.DUMMY, Component.empty());
    Component title = placeholders.renderRaw(expand(section.getString("title", "")), viewer, viewer);
    setSent(view, name + ".title", title, () -> obj.displayName(title));
    obj.setDisplaySlot(slot);
    String renderType = section.getString("render-type", "integer");
    RenderType type = renderType.equalsIgnoreCase("hearts") ? RenderType.HEARTS : RenderType.INTEGER;
    setSent(view, name + ".render", type, () -> obj.setRenderType(type));
    String fancy = section.getString("fancy-value", "");
    Set<String> expected = new HashSet<>();
    for (var p : players) {
      if (!viewer.canSee(p)) continue;
      expected.add(p.getName());
      int value = objectiveValue(viewer, p, section.getString("value", "ping"));
      var score = obj.getScore(p.getName());
      setSent(view, name + ":" + p.getName() + ".score", value, () -> score.setScore(value));
      if (!fancy.isBlank()) {
        Component text = placeholders.renderRaw(expand(fancy), viewer, p);
        setSent(view, name + ":" + p.getName() + ".fancy", text, () -> score.numberFormat(NumberFormat.fixed(text)));
      }
    }
    for (String entry : new HashSet<>(view.board.getEntries()))
      if (!entry.startsWith(SIDEBAR_ENTRY_PREFIX) && !expected.contains(entry)) {
        obj.getScore(entry).resetScore();
        view.sent.keySet().removeIf(key -> key.startsWith(name + ":" + entry + "."));
      }
  }

  private static boolean allowedFor(Player viewer, ConfigurationSection section) {
    String permission = section.getString("permission", "");
    return permission.isEmpty() || viewer.hasPermission(permission);
  }

  private int objectiveValue(Player viewer, Player p, String source) {
    return switch (source) {
      case "ping" -> p.getPing();
      case "health" -> (int) Math.ceil(p.getHealth());
      default -> {
        try {
          yield (int) Double.parseDouble(placeholders.papi(viewer, p, source));
        } catch (NumberFormatException ex) {
          yield 0;
        }
      }
    };
  }

  private void removeObjective(View view, String name) {
    var old = view.board.getObjective(name);
    if (old != null) {
      old.unregister();
      view.sent.keySet().removeIf(key -> key.startsWith(name + ":") || key.startsWith(name + "."));
    }
  }

  // ------------------------------------------------------------------ bossbars

  private void bossbars(Player p, View view) {
    var wanted = new LinkedHashMap<String, BossBar>();
    boolean hidden = preferences.get(p.getUniqueId()).bossbarsHidden() || !plugin.allows("tab", p);
    var temporary = overrides.bossBar(p.getUniqueId());
    if (hidden) {
      // Nothing is wanted; every bar shown so far is hidden below.
    } else if (temporary.isPresent()) {
      var bar = temporary.get();
      wanted.put("api", BossBar.bossBar(bar.name(), bar.progress(), bar.color(), bar.overlay()));
    } else {
      var root = section("bossbars");
      if (root != null)
        for (String id : root.getKeys(false)) {
          var s = root.getConfigurationSection(id);
          if (s == null || !s.getBoolean("enabled", true)) continue;
          String condition = s.getString("display-condition", "");
          if (!condition.isBlank() && !placeholders.condition(condition, p, p)) continue;
          wanted.put(id, bossBar(p, s));
        }
    }
    for (var entry : wanted.entrySet()) {
      var fresh = entry.getValue();
      var shown = view.bars.get(entry.getKey());
      if (shown == null) {
        view.bars.put(entry.getKey(), fresh);
        p.showBossBar(fresh);
      } else {
        shown.name(fresh.name());
        shown.progress(fresh.progress());
        shown.color(fresh.color());
        shown.overlay(fresh.overlay());
      }
    }
    for (var it = view.bars.entrySet().iterator(); it.hasNext(); ) {
      var entry = it.next();
      if (!wanted.containsKey(entry.getKey())) {
        p.hideBossBar(entry.getValue());
        it.remove();
      }
    }
  }

  /** A bossbar from tab.yml; color, overlay and progress may come from placeholders or conditions. */
  BossBar bossBar(Player p, ConfigurationSection s) {
    var name = placeholders.renderRaw(expand(s.getString("text", "")), p, p);
    return BossBar.bossBar(
        name, progress(p, s), color(p, s), overlay(p, s));
  }

  private float progress(Player p, ConfigurationSection s) {
    String raw = placeholders.plain(s.getString("progress", "1"), p, p).strip();
    try {
      double v = Double.parseDouble(raw);
      return Double.isFinite(v) ? (float) Math.max(0, Math.min(1, v)) : 1f;
    } catch (NumberFormatException e) {
      return 1f;
    }
  }

  private BossBar.Color color(Player p, ConfigurationSection s) {
    String raw = placeholders.plain(s.getString("color", "YELLOW"), p, p).strip();
    return parseEnum(raw, BossBar.Color.class, BossBar.Color.YELLOW);
  }

  private BossBar.Overlay overlay(Player p, ConfigurationSection s) {
    String raw = placeholders.plain(s.getString("overlay", "PROGRESS"), p, p).strip();
    return parseEnum(raw, BossBar.Overlay.class, BossBar.Overlay.PROGRESS);
  }

  private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, E fallback) {
    try {
      return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  // ------------------------------------------------------------ per-world list

  /** Players see only others in their own world group; {@code bypass} sees everyone. */
  private void worldLists(List<Player> players) {
    var section = section("per-world-playerlist");
    boolean on = section != null && section.getBoolean("enabled", false);
    if (!on) {
      visibility.release(WORLD_LIST_REASON);
      return;
    }
    var worldGroup = new HashMap<String, String>();
    var groups = section.getConfigurationSection("groups");
    if (groups != null)
      for (String group : groups.getKeys(false))
        for (String world : groups.getStringList(group)) worldGroup.put(world, group);
    String bypass = section.getString("bypass-permission", "loveessential.tab.worldlist.bypass");
    for (Player viewer : players) {
      boolean sees = viewer.hasPermission(bypass);
      String viewerGroup = group(viewer, worldGroup);
      for (Player target : players) {
        if (target.equals(viewer)) continue;
        boolean hide = !sees && !viewerGroup.equals(group(target, worldGroup));
        visibility.set(WORLD_LIST_REASON, viewer.getUniqueId(), target.getUniqueId(), hide);
      }
    }
  }

  private static String group(Player p, Map<String, String> worldGroup) {
    String world = p.getWorld().getName();
    return worldGroup.getOrDefault(world, "world:" + world);
  }

  // ----------------------------------------------------------- lifecycle

  private void release(Player p) {
    var view = views.remove(p.getUniqueId());
    listState.remove(p.getUniqueId());
    if (view == null) return;
    view.bars.values().forEach(p::hideBossBar);
    lastRun.keySet().removeIf(key -> key.endsWith(p.getUniqueId().toString()));
    if (p.getScoreboard().equals(view.board)) p.setScoreboard(view.previous);
    p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
    p.playerListName(null);
    p.setPlayerListOrder(0);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    preferences.load(e.getPlayer().getUniqueId());
  }

  /** Leases from a plugin that is being disabled end with it. */
  @EventHandler
  public void ownerDisabled(org.bukkit.event.server.PluginDisableEvent e) {
    overrides.removeOwner(e.getPlugin());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    release(e.getPlayer());
    visibility.forget(e.getPlayer().getUniqueId());
    overrides.removePlayer(e.getPlayer().getUniqueId());
    preferences.forget(e.getPlayer().getUniqueId());
  }

  public void stop() {
    enabled = false;
    if (task != null) task.cancel();
    task = null;
    for (var p : Bukkit.getOnlinePlayers()) release(p);
    views.clear();
    listState.clear();
    lastRun.clear();
    visibility.release(WORLD_LIST_REASON);
    placeholders.clear();
  }

  @Override
  public void close() {
    stop();
    HandlerList.unregisterAll(this);
  }

  // ------------------------------------------------------------ diagnostics

  /** Time per part of the update, averaged over the last completed 10 second window. */
  public Map<String, Double> cpuMillis() {
    return stats.averages();
  }

  /** Expands {@code <placeholder:id>} references from tab.placeholders. */
  public static String expandAliases(ConfigurationSection aliases, String template) {
    if (aliases == null) return template;
    String text = template;
    var pattern = Pattern.compile("<placeholder:([A-Za-z0-9_-]+)>");
    for (int depth = 0; depth < 8; depth++) {
      var matcher = pattern.matcher(text);
      var result = new StringBuilder();
      boolean found = false;
      while (matcher.find()) {
        found = true;
        matcher.appendReplacement(
            result, Matcher.quoteReplacement(aliases.getString(matcher.group(1), "")));
        if (result.length() > 16384) throw new IllegalArgumentException("errors.config");
      }
      matcher.appendTail(result);
      text = result.toString();
      if (text.length() > 16384) throw new IllegalArgumentException("errors.config");
      if (!found) return text;
    }
    return pattern.matcher(text).replaceAll("");
  }

  /** Rolling timing counters; a window completes every 10 seconds. */
  static final class Stats {
    private static final long WINDOW_NANOS = 10_000_000_000L;
    private Map<String, long[]> current = new HashMap<>(), completed = new HashMap<>();
    private long windowStart = System.nanoTime();

    void record(String name, long nanos) {
      roll();
      long[] values = current.computeIfAbsent(name, k -> new long[2]);
      values[0] += nanos;
      values[1]++;
    }

    private void roll() {
      long now = System.nanoTime();
      if (now - windowStart >= WINDOW_NANOS) {
        completed = current;
        current = new HashMap<>();
        windowStart = now;
      }
    }

    Map<String, Double> averages() {
      roll();
      var out = new TreeMap<String, Double>();
      completed.forEach((name, v) -> out.put(name, v[1] == 0 ? 0 : v[0] / 1e6 / v[1]));
      return out;
    }
  }
}
