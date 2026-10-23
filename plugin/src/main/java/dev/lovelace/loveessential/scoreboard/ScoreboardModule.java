package dev.lovelace.loveessential.scoreboard;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.ScoreboardService;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.Placeholders;
import dev.lovelace.loveessential.tab.TabModule;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scoreboard.*;

/**
 * Named sidebars from {@code scoreboard.scoreboards}. The first board whose display condition holds
 * is shown; a runtime lease from {@link ScoreboardService} takes priority over all of them. Boards
 * are drawn on the TAB board when TAB manages the sidebar, otherwise on a private board.
 */
public final class ScoreboardModule implements Listener, AutoCloseable {
  /** Objective and score-entry names; the TAB module leaves entries with this prefix alone. */
  static final String OBJECTIVE = "le_sidebar", ENTRY_PREFIX = "le_sidebar_";

  static final int MAX_LINES = 15;
  static final String KIND = "scoreboard-visibility";

  /** A parsed named board; {@code lines} are MiniMessage templates. */
  public record Named(String id, String title, List<String> lines, String displayCondition) {}

  private record Content(Component title, List<Component> lines) {}

  private record View(Scoreboard board, Scoreboard previous) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final Placeholders placeholders;
  private final TabModule tab;
  private final SidebarOverrides overrides;
  private final Map<UUID, SqliteStore.Document<Boolean>> visibility = new HashMap<>();
  private final Set<UUID> loading = new HashSet<>();
  private final Map<UUID, Long> joinedAt = new HashMap<>();
  private final Map<UUID, View> views = new HashMap<>();
  private final Map<Scoreboard, List<Component>> drawn = new IdentityHashMap<>();
  private final Set<Scoreboard> rendered = Collections.newSetFromMap(new IdentityHashMap<>());
  private final TweaksScoreboardBridge bridge;
  private final dev.lovelace.loveessential.tab.TabSettings tabSettings;
  private dev.lovelace.loveessential.dialog.NativeDialogs ui;
  private java.util.function.Consumer<Player> moduleSettings = p -> {};
  private org.bukkit.scheduler.BukkitTask task;
  private boolean ownership = true;

  public ScoreboardModule(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      Placeholders placeholders,
      TabModule tab,
      dev.lovelace.loveessential.tab.TabSettings tabSettings) {
    this.plugin = plugin;
    this.tabSettings = tabSettings;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.placeholders = placeholders;
    this.tab = tab;
    overrides =
        new SidebarOverrides(
            System::nanoTime,
            () -> plugin.getConfig().getInt("scoreboard.overrides.max-seconds", 600),
            () -> plugin.getConfig().getInt("scoreboard.overrides.per-player-limit", 8));
    bridge = new TweaksScoreboardBridge(plugin);
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public void nativeDialogs(dev.lovelace.loveessential.dialog.NativeDialogs ui) {
    this.ui = ui;
  }

  public void moduleSettings(java.util.function.Consumer<Player> opener) {
    moduleSettings = opener;
  }

  /** Reads the named boards from scoreboard.yml in configuration order. */
  public static List<Named> boards(ConfigurationSection config) {
    var result = new ArrayList<Named>();
    var root = config == null ? null : config.getConfigurationSection("scoreboard.scoreboards");
    if (root == null) return result;
    for (String id : root.getKeys(false)) {
      var s = root.getConfigurationSection(id);
      if (s == null) throw new IllegalArgumentException("errors.config");
      result.add(
          new Named(
              id,
              s.getString("title", ""),
              s.getStringList("lines"),
              s.getString("display-condition", "")));
    }
    return result;
  }

  /** Rejects boards that cannot be drawn, so a bad edit never reaches a running server. */
  public static void validate(ConfigurationSection config) {
    var ids = new HashSet<String>();
    for (var board : boards(config)) {
      if (!board.id().matches("[A-Za-z0-9_-]{1,48}") || !ids.add(board.id()))
        throw new IllegalArgumentException("errors.config");
      if (board.title().isBlank() || board.lines().isEmpty() || board.lines().size() > MAX_LINES)
        throw new IllegalArgumentException("errors.config");
      if (!board.displayCondition().isBlank() && !board.displayCondition().matches("[A-Za-z0-9_-]{1,48}"))
        throw new IllegalArgumentException("errors.config");
    }
  }

  public void start() {
    Bukkit.getServicesManager()
        .register(ScoreboardService.class, overrides, plugin, org.bukkit.plugin.ServicePriority.Normal);
    ownership =
        bridge.take(
            (sender, command, label, args) -> {
              if (sender instanceof Player p)
                try {
                  if (args.length == 1 && (args[0].equals("edit") || args[0].equals("settings")))
                    settings(p);
                  else toggle(p);
                } catch (RuntimeException e) {
                  lang.error(p, e);
                }
              else lang.send(sender, "errors.player-only");
              return true;
            });
    if (!ownership) plugin.getLogger().warning(lang.text("sidebar.legacy-conflict"));
    tab.customSidebar(this::render, () -> plugin.module("scoreboard") && ownership, this::owns);
    task =
        Bukkit.getScheduler()
            .runTaskTimer(plugin, this::tick, 1, Math.max(2, refreshTicks()));
    for (var p : Bukkit.getOnlinePlayers()) load(p);
  }

  private long refreshTicks() {
    return plugin.getConfig().getLong("scoreboard.refresh-ticks", 20);
  }

  public boolean owns(Scoreboard board) {
    return views.values().stream().anyMatch(v -> v.board() == board);
  }

  // ------------------------------------------------------------- visibility

  private void load(Player p) {
    UUID id = p.getUniqueId();
    joinedAt.putIfAbsent(id, System.nanoTime());
    if (visibility.containsKey(id) || !loading.add(id)) return;
    store
        .get(KIND, id.toString(), Boolean.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      loading.remove(id);
                      if (error != null || !p.isOnline()) return;
                      visibility.put(
                          id,
                          saved.orElseGet(
                              () ->
                                  new SqliteStore.Document<>(
                                      id.toString(),
                                      0,
                                      plugin.getConfig().getBoolean("scoreboard.default-enabled", true))));
                    }));
  }

  /** Personal on/off state; before the stored value is loaded the configured default applies. */
  public boolean enabledFor(UUID player) {
    var document = visibility.get(player);
    return document == null
        ? plugin.getConfig().getBoolean("scoreboard.default-enabled", true)
        : document.value();
  }

  public CompletableFuture<Void> setEnabled(Player p, boolean value) {
    var current = visibility.get(p.getUniqueId());
    if (current == null) {
      lang.send(p, "errors.starting");
      return CompletableFuture.failedFuture(new IllegalStateException("errors.starting"));
    }
    return store
        .save(KIND, p.getUniqueId().toString(), current.revision(), value, p.getUniqueId().toString())
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      visibility.put(
                          p.getUniqueId(),
                          new SqliteStore.Document<>(p.getUniqueId().toString(), rev, value));
                      return null;
                    }));
  }

  private void toggle(Player p) {
    plugin.requireAccess("scoreboard", p);
    if (!love.authenticated(p)) throw new IllegalArgumentException("errors.access");
    boolean next = !enabledFor(p.getUniqueId());
    setEnabled(p, next)
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      if (!p.isOnline()) return;
                      if (error != null) lang.send(p, "errors.storage");
                      else lang.send(p, next ? "scoreboard.enabled" : "scoreboard.disabled");
                    }));
  }

  // ------------------------------------------------------------------ content

  private Optional<ScoreboardService.Board> lease(Player p) {
    return overrides.current(
        p.getUniqueId(),
        enabledFor(p.getUniqueId()),
        plugin.getConfig().getBoolean("scoreboard.overrides.allow-forced", true));
  }

  private boolean eligible(Player p) {
    if (!plugin.module("scoreboard") || !ownership) return false;
    if (!plugin.allows("scoreboard", p) || !love.authenticated(p)) return false;
    var joined = joinedAt.get(p.getUniqueId());
    long delay = plugin.getConfig().getLong("scoreboard.delay-on-join-ms", 1000);
    return joined == null || System.nanoTime() - joined >= Duration.ofMillis(delay).toNanos();
  }

  /** The lines and title to draw for a player right now, or nothing. */
  private Optional<Content> content(Player p) {
    if (!eligible(p)) return Optional.empty();
    var leased = lease(p);
    var boards = boards(plugin.getConfig());
    var choice =
        ScoreboardChoice.choose(
            leased.isPresent(),
            boards.stream().map(b -> new ScoreboardChoice.Named(b.id(), b.displayCondition())).toList(),
            name -> placeholders.condition(name, p, p));
    return switch (choice.kind()) {
      case LEASE ->
          leased.map(board -> new Content(board.title(), board.lines()));
      case NAMED -> {
        if (!enabledFor(p.getUniqueId())) yield Optional.empty();
        var board = boards.stream().filter(b -> b.id().equals(choice.id())).findFirst().orElseThrow();
        yield Optional.of(draw(p, board));
      }
      case NONE -> Optional.empty();
    };
  }

  /** Renders one named board for one player; {@code <condition:...>} tags are resolved too. */
  private Content draw(Player p, Named board) {
    var title = placeholders.renderRaw(board.title(), p, p);
    var lines = new ArrayList<Component>();
    for (String line : board.lines()) lines.add(placeholders.renderRaw(line, p, p));
    return new Content(title, List.copyOf(lines));
  }

  /** Shows one named board to a player for a while, regardless of the personal toggle. */
  public void show(Player target, String id, Duration ttl) {
    var board =
        boards(plugin.getConfig()).stream()
            .filter(b -> b.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("errors.input"));
    var content = draw(target, board);
    overrides.show(
        plugin,
        target.getUniqueId(),
        0,
        ttl,
        true,
        new ScoreboardService.Board(content.title(), content.lines()));
  }

  // ---------------------------------------------------------------- rendering

  /** Draws the sidebar on {@code board}; called by TAB on its own board and by the fallback tick. */
  public void render(Player p, Scoreboard board) {
    var content = content(p);
    if (content.isEmpty()) {
      var obj = board.getObjective(OBJECTIVE);
      if (obj != null) obj.unregister();
      drawn.remove(board);
      return;
    }
    var existing = board.getObjective(DisplaySlot.SIDEBAR);
    if (existing != null && !existing.getName().equals(OBJECTIVE)) return;
    var value = content.get();
    Objective obj = board.getObjective(OBJECTIVE);
    if (obj == null) {
      obj = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, value.title());
      obj.setDisplaySlot(DisplaySlot.SIDEBAR);
      obj.numberFormat(NumberFormat.blank());
      // A recreated objective has no scores, so the lines must be written again.
      drawn.remove(board);
    }
    obj.displayName(value.title());
    rendered.add(board);
    var previous = drawn.get(board);
    if (previous != null && previous.equals(value.lines())) return;
    drawn.put(board, value.lines());
    for (int i = 0; i < MAX_LINES; i++) {
      var score = obj.getScore(ENTRY_PREFIX + i);
      if (i < value.lines().size()) {
        score.setScore(value.lines().size() - i);
        score.customName(value.lines().get(i));
      } else score.resetScore();
    }
  }

  private void tick() {
    if (!plugin.ready()) return;
    if (ownership && !bridge.poll()) {
      ownership = false;
      plugin.getLogger().warning(lang.text("sidebar.legacy-conflict"));
    }
    for (var p : Bukkit.getOnlinePlayers()) {
      load(p);
      if (!eligible(p)) {
        release(p);
        continue;
      }
      if (tab.managesBoards() && plugin.allows("tab", p)) continue;
      var view = views.get(p.getUniqueId());
      if (view == null) {
        if (p.getScoreboard() != Bukkit.getScoreboardManager().getMainScoreboard()) continue;
        var board = Bukkit.getScoreboardManager().getNewScoreboard();
        view = new View(board, p.getScoreboard());
        views.put(p.getUniqueId(), view);
        p.setScoreboard(board);
      }
      if (p.getScoreboard() == Bukkit.getScoreboardManager().getMainScoreboard())
        p.setScoreboard(view.board());
      if (p.getScoreboard() == view.board()) render(p, view.board());
    }
  }

  private void release(Player p) {
    var view = views.remove(p.getUniqueId());
    if (view != null) {
      var obj = view.board().getObjective(OBJECTIVE);
      if (obj != null) obj.unregister();
      drawn.remove(view.board());
      rendered.remove(view.board());
      if (p.getScoreboard() == view.board()) p.setScoreboard(view.previous());
    }
    var objective = p.getScoreboard().getObjective(OBJECTIVE);
    if (objective != null) objective.unregister();
  }

  // ------------------------------------------------------------------- events

  @EventHandler
  public void join(PlayerJoinEvent e) {
    // Startup loads finish before start() loads all online players.
    if (plugin.ready()) load(e.getPlayer());
    joinedAt.put(e.getPlayer().getUniqueId(), System.nanoTime());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    overrides.removePlayer(id);
    release(e.getPlayer());
    visibility.remove(id);
    loading.remove(id);
    joinedAt.remove(id);
  }

  @EventHandler
  public void ownerDisabled(org.bukkit.event.server.PluginDisableEvent e) {
    overrides.removeOwner(e.getPlugin());
  }

  // ----------------------------------------------------------------- commands

  /** {@code /le scoreboard [toggle | edit | show <board> [player]]}. */
  public void command(Player p, String[] args) {
    if (args.length == 0 || args.length == 1 && args[0].equals("toggle")) {
      toggle(p);
      return;
    }
    if (args.length == 1 && args[0].equals("edit")) {
      settings(p);
      return;
    }
    if (args.length >= 2 && args.length <= 3 && args[0].equals("show")) {
      Player target = p;
      if (args.length == 3) {
        requireAdmin(p);
        target = Bukkit.getPlayerExact(args[2]);
        if (target == null) throw new IllegalArgumentException("errors.input");
      }
      show(target, args[1], Duration.ofSeconds(plugin.getConfig().getLong("scoreboard.show-seconds", 60)));
      lang.send(p, "scoreboard.shown");
      return;
    }
    throw new IllegalArgumentException("errors.input");
  }

  public List<String> suggestions(Player p, String[] args) {
    if (args.length == 1)
      return p.hasPermission("loveessential.admin")
          ? List.of("toggle", "edit", "show")
          : List.of("toggle");
    if (args.length == 2 && args[0].equals("show") && p.hasPermission("loveessential.admin"))
      return boards(plugin.getConfig()).stream().map(Named::id).toList();
    return List.of();
  }

  private void requireAdmin(Player p) {
    if (!plugin.ready()) throw new IllegalArgumentException("errors.starting");
    if (!love.authenticated(p)) throw new IllegalArgumentException("errors.access");
    if (!p.hasPermission("loveessential.admin"))
      throw new IllegalArgumentException("errors.permission");
  }

  /** Admin menu: named boards with pagination and module controls; see {@link ScoreboardSettings}. */
  public void settings(Player p) {
    requireAdmin(p);
    new ScoreboardSettings(plugin, lang, this, ui, moduleSettings, tabSettings).open(p);
  }

  /** Reloaded on every configuration change; the boards are read from the runtime config. */
  public void refresh() {
    if (task != null) {
      task.cancel();
      task =
          Bukkit.getScheduler()
              .runTaskTimer(plugin, this::tick, 1, Math.max(2, refreshTicks()));
    }
    if (!plugin.module("scoreboard")) for (var p : Bukkit.getOnlinePlayers()) release(p);
  }

  @Override
  public void close() {
    if (task != null) task.cancel();
    overrides.close();
    Bukkit.getServicesManager().unregister(ScoreboardService.class, overrides);
    for (var p : Bukkit.getOnlinePlayers()) release(p);
    for (var board : rendered) {
      var obj = board.getObjective(OBJECTIVE);
      if (obj != null) obj.unregister();
    }
    rendered.clear();
    drawn.clear();
    bridge.close();
    HandlerList.unregisterAll(this);
  }
}
