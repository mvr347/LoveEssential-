package dev.lovelace.loveessential.scoreboard;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.content.ContentLibrary;
import dev.lovelace.loveessential.gui.MenuStyle;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.*;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.scoreboard.*;

/** Personal, SQL-backed sidebar; shares the native TAB board instead of replacing its teams. */
public final class Sidebar implements Listener, AutoCloseable {
  public record Line(
      String id,
      String nameKey,
      String category,
      List<String> variants,
      String requirement,
      String expected,
      String reasonKey,
      List<String> variantNames,
      List<String> loreKeys) {
    public Line {
      variants = List.copyOf(variants);
      variantNames =
          variantNames == null
              ? Collections.nCopies(variants.size(), "ui.empty")
              : List.copyOf(variantNames);
      loreKeys = loreKeys == null ? List.of() : List.copyOf(loreKeys);
    }

    public Line(
        String id,
        String nameKey,
        String category,
        List<String> variants,
        String requirement,
        String expected,
        String reasonKey) {
      this(id, nameKey, category, variants, requirement, expected, reasonKey, null, null);
    }
  }

  public record Preset(String id, String nameKey, List<Selection> entries) {
    public Preset {
      entries = List.copyOf(entries);
    }
  }

  public record Selection(String id, int variant) {}

  public record Catalog(
      String titleKey,
      String separatorKey,
      String bottomKey,
      int max,
      int interval,
      List<String> disabledWorlds,
      Map<String, String> categories,
      List<Line> lines,
      List<Preset> presets,
      String defaultPreset) {
    public Catalog {
      disabledWorlds = List.copyOf(disabledWorlds);
      categories = Map.copyOf(categories);
      lines = List.copyOf(lines);
      presets = List.copyOf(presets);
    }
  }

  public record State(boolean enabled, boolean viewAll, List<Selection> entries) {
    public State {
      entries = List.copyOf(entries);
    }
  }

  private record View(Scoreboard board, Scoreboard previous) {}

  private static final class Menu implements InventoryHolder {
    Inventory inventory;
    boolean adminOnly;
    final Map<Integer, java.util.function.Consumer<ClickType>> clicks = new HashMap<>();

    public Inventory getInventory() {
      return inventory;
    }
  }

  private record Migration(Catalog catalog, Map<String, String> texts) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final Placeholders placeholders;
  private final TabModule tab;
  private final ContentLibrary texts;
  private Catalog catalog;
  private long catalogRevision;
  private dev.lovelace.loveessential.dialog.NativeDialogs ui;

  public void nativeDialogs(dev.lovelace.loveessential.dialog.NativeDialogs ui) {
    this.ui = ui;
  }

  private final Map<UUID, SqliteStore.Document<State>> states = new HashMap<>();
  private final Set<UUID> loading = new HashSet<>(), saving = new HashSet<>();
  private final Map<UUID, UUID> loadSessions = new HashMap<>();
  private final Map<UUID, View> views = new HashMap<>();
  private final Set<Scoreboard> rendered = new HashSet<>();
  private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();
  private final TweaksScoreboardBridge bridge;
  private final SidebarOverrides overrides;
  private org.bukkit.scheduler.BukkitTask task;
  private boolean ownership = true;
  private static final Pattern PAPI = Pattern.compile("%([A-Za-z0-9]+)_[^%]+%");

  public Sidebar(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      Placeholders placeholders,
      TabModule tab,
      ContentLibrary texts) {
    this.plugin = plugin;
    overrides =
        new SidebarOverrides(
            System::nanoTime,
            () -> plugin.getConfig().getInt("scoreboard.overrides.max-seconds", 600),
            () -> plugin.getConfig().getInt("scoreboard.overrides.per-player-limit", 8));
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.placeholders = placeholders;
    this.tab = tab;
    this.texts = texts;
    bridge = new TweaksScoreboardBridge(plugin);
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public Catalog catalog() {
    return catalog;
  }

  public CompletableFuture<Void> load() {
    var tweaks = Bukkit.getPluginManager().getPlugin("LoveTweaks");
    Path folder =
        tweaks == null
            ? plugin.getDataFolder().toPath().resolveSibling("LoveTweaks")
            : tweaks.getDataFolder().toPath();
    return store
        .get("scoreboard-catalog", "default", Catalog.class)
        .thenCompose(
            saved -> {
              if (saved.isPresent())
                return main.<Void>call(
                    () -> {
                      catalog = saved.get().value();
                      catalogRevision = saved.get().revision();
                      return null;
                    });
              return CompletableFuture.supplyAsync(() -> migrate(folder), plugin.io())
                  .thenCompose(
                      m ->
                          texts
                              .texts(m.texts())
                              .thenCompose(
                                  v ->
                                      store.save(
                                          "scoreboard-catalog",
                                          "default",
                                          0,
                                          m.catalog(),
                                          "migration"))
                              .thenCompose(
                                  rev ->
                                      main.<Void>call(
                                          () -> {
                                            catalog = m.catalog();
                                            catalogRevision = rev;
                                            return null;
                                          })));
            })
        .thenCompose(v -> migratePlayers(folder));
  }

  private String legacy(String value) {
    return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        .serialize(
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(value.replace('§', '&')));
  }

  private Migration migrate(Path folder) {
    var y = new YamlConfiguration();
    if (folder != null && Files.exists(folder.resolve("config.yml")))
      try {
        y.load(folder.resolve("config.yml").toFile());
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    var s = y.getConfigurationSection("scoreboard");
    var values = new HashMap<String, String>();
    String prefix = "sidebar-text.imported";
    values.put(
        prefix + ".title",
        s == null ? "<gold>Love</gold>" : legacy(s.getString("title", "&6Love")));
    values.put(prefix + ".separator", s == null ? "" : legacy(s.getString("separator", "")));
    values.put(prefix + ".bottom", s == null ? "" : legacy(s.getString("bottom", "")));
    var categories = new LinkedHashMap<String, String>();
    var lines = new ArrayList<Line>();
    var presets = new ArrayList<Preset>();
    if (s != null) {
      var c = s.getConfigurationSection("categories");
      if (c != null)
        for (String id : c.getKeys(false)) {
          String key = prefix + ".categories." + id;
          values.put(key, legacy(c.getString(id + ".display-name", id)));
          categories.put(id, key);
        }
      var ps = s.getConfigurationSection("placeholders");
      if (ps != null)
        for (String id : ps.getKeys(false)) {
          var p = ps.getConfigurationSection(id);
          String base = prefix + ".lines." + id;
          values.put(base + ".name", legacy(p.getString("display-name", id)));
          var variants = new ArrayList<String>();
          var variantNames = new ArrayList<String>();
          var loreKeys = new ArrayList<String>();
          for (String text : p.getStringList("lore")) {
            String k = base + ".lore." + loreKeys.size();
            values.put(k, legacy(text));
            loreKeys.add(k);
          }
          int i = 0;
          for (var raw : p.getMapList("variants")) {
            String key = base + ".variant." + i++;
            values.put(key, legacy(Objects.toString(raw.get("template"), "")));
            variants.add(key);
            String label = base + ".variant-name." + (i - 1);
            values.put(label, legacy(Objects.toString(raw.get("name"), "")));
            variantNames.add(label);
          }
          if (variants.isEmpty()) {
            String key = base + ".variant.0";
            values.put(key, legacy(p.getString("template", "")));
            variants.add(key);
            variantNames.add("ui.empty");
          }
          values.put(base + ".reason", legacy(p.getString("requirement.reason", "")));
          lines.add(
              new Line(
                  id,
                  base + ".name",
                  p.getString("category", "general"),
                  variants,
                  p.getString("requirement.placeholder", ""),
                  p.getString("requirement.equals", ""),
                  base + ".reason",
                  variantNames,
                  loreKeys));
        }
      var pr = s.getConfigurationSection("presets");
      if (pr != null)
        for (String id : pr.getKeys(false)) {
          String key = prefix + ".presets." + id;
          values.put(key, legacy(pr.getString(id + ".name", id)));
          presets.add(
              new Preset(
                  id,
                  key,
                  pr.getStringList(id + ".placeholders").stream()
                      .map(this::parseSelection)
                      .toList()));
        }
    }
    if (lines.isEmpty()) {
      values.put(prefix + ".coords.name", "<gold>Координаты");
      values.put(prefix + ".coords.value", "<gray>XYZ: <white><x> <y> <z>");
      values.put(prefix + ".name.name", "<gold>Игрок");
      values.put(prefix + ".name.value", "<gold><nickname>");
      lines.add(
          new Line(
              "coords",
              prefix + ".coords.name",
              "general",
              List.of(prefix + ".coords.value"),
              "",
              "",
              "ui.empty"));
      lines.add(
          new Line(
              "name",
              prefix + ".name.name",
              "general",
              List.of(prefix + ".name.value"),
              "",
              "",
              "ui.empty"));
    }
    if (categories.isEmpty()) {
      categories.put("general", "sidebar.general");
    }
    return new Migration(
        new Catalog(
            prefix + ".title",
            prefix + ".separator",
            prefix + ".bottom",
            s == null ? 8 : Math.max(1, Math.min(8, s.getInt("max-placeholders", 8))),
            s == null ? 20 : Math.max(2, Math.min(1200, s.getInt("update-interval", 20))),
            s == null ? List.of() : s.getStringList("disabled-worlds"),
            categories,
            lines,
            presets,
            s == null ? "" : s.getString("default-preset", "")),
        values);
  }

  private Selection parseSelection(String value) {
    String[] parts = value.split(":", 2);
    return new Selection(parts[0], parts.length > 1 ? Math.max(0, Integer.parseInt(parts[1])) : 0);
  }

  private CompletableFuture<Void> migratePlayers(Path folder) {
    return store
        .get("migration", "scoreboard-players", Boolean.class)
        .thenCompose(
            done -> {
              if (done.isPresent()) return CompletableFuture.completedFuture(null);
              return CompletableFuture.supplyAsync(
                      () -> {
                        var out = new LinkedHashMap<String, State>();
                        if (folder != null) {
                          Path legacy = folder.resolve("scoreboard_data.yml");
                          if (!Files.exists(legacy))
                            legacy = folder.resolve("scoreboard_data.yml.bak");
                          if (Files.exists(legacy)) {
                            try {
                              var data = new YamlConfiguration();
                              data.load(legacy.toFile());
                              for (String id : data.getKeys(false)) {
                                try {
                                  UUID.fromString(id);
                                } catch (IllegalArgumentException ignored) {
                                  continue;
                                }
                                var selected = data.getStringList(id + ".placeholders");
                                if (selected.isEmpty())
                                  selected = data.getStringList(id + ".sections");
                                out.put(
                                    id,
                                    new State(
                                        data.getBoolean(id + ".enabled", true),
                                        false,
                                        selected.stream()
                                            .map(value -> new Selection(value, 0))
                                            .toList()));
                              }
                            } catch (Exception e) {
                              throw new CompletionException(e);
                            }
                          }
                        }
                        if (folder == null || !Files.exists(folder.resolve("lovetweaks.db")))
                          return out;
                        try (var c =
                                DriverManager.getConnection(
                                    "jdbc:sqlite:file:"
                                        + folder.resolve("lovetweaks.db").toAbsolutePath()
                                        + "?mode=ro");
                            var query = c.createStatement()) {
                          c.setAutoCommit(false);
                          boolean players = false;
                          try (var rs =
                              query.executeQuery(
                                  "SELECT name FROM sqlite_master WHERE type='table' AND"
                                      + " name='scoreboard_players'")) {
                            players = rs.next();
                          }
                          if (!players) return out;
                          boolean view = false, variant = false;
                          try (var rs =
                              query.executeQuery("PRAGMA table_info(scoreboard_players)")) {
                            while (rs.next())
                              if (rs.getString("name").equals("view_all")) view = true;
                          }
                          try (var rs =
                              query.executeQuery("PRAGMA table_info(scoreboard_placeholders)")) {
                            while (rs.next())
                              if (rs.getString("name").equals("variant")) variant = true;
                          }
                          try (var rs =
                              query.executeQuery(
                                  "SELECT uuid,enabled,"
                                      + (view ? "view_all" : "0")
                                      + " AS view_all FROM scoreboard_players")) {
                            while (rs.next())
                              out.put(
                                  rs.getString("uuid"),
                                  new State(
                                      rs.getInt("enabled") != 0,
                                      rs.getInt("view_all") != 0,
                                      List.of()));
                          }
                          try (var rs =
                              query.executeQuery(
                                  "SELECT uuid,placeholder_id,"
                                      + (variant ? "variant" : "0")
                                      + " AS variant FROM scoreboard_placeholders ORDER BY"
                                      + " uuid,position")) {
                            while (rs.next()) {
                              String id = rs.getString("uuid");
                              var state = out.get(id);
                              if (state == null) continue;
                              var entries = new ArrayList<>(state.entries());
                              entries.add(
                                  new Selection(
                                      rs.getString("placeholder_id"), rs.getInt("variant")));
                              out.put(id, new State(state.enabled(), state.viewAll(), entries));
                            }
                          }
                        } catch (Exception e) {
                          throw new CompletionException(e);
                        }
                        return out;
                      },
                      plugin.io())
                  .thenCompose(
                      imported -> {
                        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                        for (var e : imported.entrySet())
                          chain =
                              chain.thenCompose(
                                  v ->
                                      store
                                          .get("scoreboard-player", e.getKey(), State.class)
                                          .thenCompose(
                                              old ->
                                                  old.isPresent()
                                                      ? CompletableFuture.completedFuture(0L)
                                                      : store.save(
                                                          "scoreboard-player",
                                                          e.getKey(),
                                                          0,
                                                          normalize(e.getValue()),
                                                          "migration"))
                                          .thenApply(ignored -> null));
                        return chain
                            .thenCompose(
                                v ->
                                    store.save(
                                        "migration", "scoreboard-players", 0, true, "migration"))
                            .thenApply(v -> null);
                      });
            });
  }

  private State normalize(State state) {
    if (line("datetime") == null) return state;
    var entries = new ArrayList<Selection>();
    var seen = new HashSet<String>();
    for (var entry : state.entries()) {
      var value =
          switch (entry.id()) {
            case "date" -> new Selection("datetime", 0);
            case "time" -> new Selection("datetime", 1);
            case "server_time" -> new Selection("datetime", 2);
            default -> entry;
          };
      if (seen.add(value.id())) entries.add(value);
    }
    return new State(state.enabled(), state.viewAll(), entries);
  }

  public void start() {
    Bukkit.getServicesManager()
        .register(
            dev.lovelace.loveessential.api.ScoreboardService.class,
            overrides,
            plugin,
            org.bukkit.plugin.ServicePriority.Normal);
    ownership =
        bridge.take(
            (sender, command, label, args) -> {
              if (sender instanceof Player p)
                try {
                  if (args.length == 1 && (args[0].equals("edit") || args[0].equals("settings")))
                    settings(p);
                  else open(p, "", 0);
                } catch (RuntimeException e) {
                  lang.send(p, lang.contains(e.getMessage()) ? e.getMessage() : "errors.input");
                }
              else lang.send(sender, "errors.player-only");
              return true;
            });
    if (!ownership) plugin.getLogger().warning(lang.text("sidebar.legacy-conflict"));
    tab.customSidebar(this::render, () -> plugin.module("scoreboard") && ownership, this::owns);
    task =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                this::tick,
                1,
                Math.max(
                    2, plugin.getConfig().getLong("scoreboard.refresh-ticks", catalog.interval())));
    for (var p : Bukkit.getOnlinePlayers()) loadPlayer(p);
  }

  public boolean owns(Scoreboard board) {
    return views.values().stream().anyMatch(v -> v.board() == board);
  }

  private void loadPlayer(Player p) {
    UUID id = p.getUniqueId();
    if (states.containsKey(id) || !loading.add(id)) return;
    UUID session = UUID.randomUUID();
    loadSessions.put(id, session);
    store
        .get("scoreboard-player", id.toString(), State.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      if (!session.equals(loadSessions.get(id))) return;
                      loading.remove(id);
                      loadSessions.remove(id);
                      if (error != null || !p.isOnline() || Bukkit.getPlayer(id) != p) return;
                      states.put(
                          id,
                          saved.orElseGet(
                              () -> new SqliteStore.Document<>(id.toString(), 0, defaults())));
                    }));
  }

  private State defaults() {
    var entries =
        catalog.presets().stream()
            .filter(p -> p.id().equals(catalog.defaultPreset()))
            .findFirst()
            .map(preset -> preset.entries().stream().limit(maxSelected()).toList())
            .orElseGet(
                () ->
                    catalog.lines().stream()
                        .limit(maxSelected())
                        .map(l -> new Selection(l.id(), 0))
                        .toList());
    return new State(
        plugin.getConfig().getBoolean("scoreboard.default-enabled", true), false, entries);
  }

  private Line line(String id) {
    return catalog.lines().stream().filter(l -> l.id().equals(id)).findFirst().orElse(null);
  }

  public SqliteStore.Document<State> state(UUID id) {
    return states.get(id);
  }

  private String expanded(Player p, String text) {
    if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return text;
    return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, text);
  }

  private boolean available(Player p, Line line) {
    String raw = lang.text(line.variants().getFirst());
    Matcher matcher = PAPI.matcher(raw);
    while (matcher.find()) {
      String expression = matcher.group();
      String result =
          expression.startsWith("%loveessential_")
              ? placeholders.papi(p, p, expression)
              : placeholders.papi(p, p, expression);
      if (result.isEmpty() || result.equals(expression)) return false;
    }
    if (!line.requirement().isBlank()) {
      String result = placeholders.papi(p, p, line.requirement());
      if (!line.expected().isBlank()) return result.equalsIgnoreCase(line.expected());
      return !result.isBlank() && !result.equals(line.requirement());
    }
    return true;
  }

  private Component value(Player p, String key) {
    String raw = lang.text(key);
    Matcher matcher = PAPI.matcher(raw);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String expression = matcher.group();
      String id = expression.substring(1, expression.length() - 1).replace("'", "\\'");
      matcher.appendReplacement(result, Matcher.quoteReplacement("<papi:'" + id + "'>"));
    }
    matcher.appendTail(result);
    return placeholders.renderRaw(result.toString(), p, p);
  }

  private int maxSelected() {
    return Math.min(
        8, Math.min(plugin.getConfig().getInt("scoreboard.max-selected-lines", 8), catalog.max()));
  }

  public boolean eligible(Player p) {
    String world = p.getWorld().getName();
    var config = plugin.getConfig();
    String permission = config.getString("scoreboard.display-permission", "");
    var only = config.getStringList("scoreboard.enabled-worlds");
    return love.authenticated(p)
        && catalog.disabledWorlds().stream().noneMatch(world::equalsIgnoreCase)
        && config.getStringList("scoreboard.disabled-worlds").stream()
            .noneMatch(world::equalsIgnoreCase)
        && (only.isEmpty() || only.stream().anyMatch(world::equalsIgnoreCase))
        && config.getStringList("scoreboard.disabled-gamemodes").stream()
            .noneMatch(mode -> mode.equalsIgnoreCase(p.getGameMode().name()))
        && (permission.isBlank() || p.hasPermission(permission))
        && (!config.getBoolean("scoreboard.hide-while-afk", false)
            || !"true".equals(placeholders.papi(p, p, "%loveessential_afk%")))
        && (!config.getBoolean("scoreboard.hide-in-combat", false) || !love.combat(p));
  }

  private Optional<dev.lovelace.loveessential.api.ScoreboardService.Board> override(Player p) {
    var s = state(p.getUniqueId());
    return overrides.current(
        p.getUniqueId(),
        s != null && s.value().enabled(),
        plugin.getConfig().getBoolean("scoreboard.overrides.allow-forced", true));
  }

  public List<Component> lines(Player p) {
    var s = state(p.getUniqueId());
    if (!eligible(p)) return List.of();
    var forced = override(p);
    if (forced.isPresent()) return forced.get().lines();
    if (s == null || !s.value().enabled()) return List.of();
    var active = new ArrayList<Component>();
    for (var selection : s.value().entries()) {
      var line = line(selection.id());
      if (line == null || !available(p, line)) continue;
      active.add(
          value(
              p, line.variants().get(Math.floorMod(selection.variant(), line.variants().size()))));
      if (active.size() >= maxSelected()) break;
    }
    if (active.isEmpty()) return List.of();
    var out = new ArrayList<Component>();
    out.add(value(p, catalog.separatorKey()));
    for (int i = 0; i < active.size(); i++) {
      out.add(active.get(i));
      if (i % 2 == 1 || i == active.size() - 1) out.add(value(p, catalog.separatorKey()));
    }
    out.add(value(p, catalog.bottomKey()));
    return out.stream().limit(15).toList();
  }

  public void render(Player p, Scoreboard board) {
    var old = board.getObjective("le_side");
    if (old != null) old.unregister();
    var obj = board.getObjective("le_sidebar");
    var forced =
        eligible(p)
            ? override(p)
            : Optional.<dev.lovelace.loveessential.api.ScoreboardService.Board>empty();
    var lines =
        plugin.module("scoreboard") && ownership
            ? forced
                .map(dev.lovelace.loveessential.api.ScoreboardService.Board::lines)
                .orElseGet(() -> lines(p))
            : List.<Component>of();
    var title =
        forced
            .map(dev.lovelace.loveessential.api.ScoreboardService.Board::title)
            .orElseGet(() -> value(p, catalog.titleKey()));
    if (lines.isEmpty()) {
      if (obj != null) obj.unregister();
      return;
    }
    var existing = board.getObjective(DisplaySlot.SIDEBAR);
    if (existing != null && !existing.getName().equals("le_sidebar")) return;
    if (obj == null) {
      obj = board.registerNewObjective("le_sidebar", Criteria.DUMMY, title);
      obj.setDisplaySlot(DisplaySlot.SIDEBAR);
      obj.numberFormat(NumberFormat.blank());
    }
    obj.displayName(title);
    var previous = playerBoards.put(p.getUniqueId(), board);
    if (previous != null && previous != board) {
      var oldSidebar = previous.getObjective("le_sidebar");
      if (oldSidebar != null) oldSidebar.unregister();
      rendered.remove(previous);
    }
    rendered.add(board);
    for (int i = 0; i < 15; i++) {
      var score = obj.getScore("le_sidebar_" + i);
      if (i < lines.size()) {
        score.setScore(lines.size() - i);
        score.customName(lines.get(i));
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
      loadPlayer(p);
      if (!plugin.module("scoreboard") || !ownership || !eligible(p)) {
        release(p);
        continue;
      }
      if (tab.managesBoards()) {
        if (tab.owns(p)) render(p, p.getScoreboard());
        continue;
      }
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
    var renderedBoard = playerBoards.remove(p.getUniqueId());
    if (renderedBoard != null) {
      var owned = renderedBoard.getObjective("le_sidebar");
      if (owned != null) owned.unregister();
      rendered.remove(renderedBoard);
    }
    var view = views.remove(p.getUniqueId());
    if (view != null) {
      var obj = view.board().getObjective("le_sidebar");
      if (obj != null) obj.unregister();
      rendered.remove(view.board());
      if (p.getScoreboard() == view.board()) p.setScoreboard(view.previous());
    }
    var objective = p.getScoreboard().getObjective("le_sidebar");
    if (objective != null) objective.unregister();
  }

  private void save(Player p, State state, Runnable next) {
    UUID id = p.getUniqueId();
    var d = states.get(id);
    if (d == null || !saving.add(id)) return;
    store
        .save("scoreboard-player", id.toString(), d.revision(), state, id.toString())
        .whenComplete(
            (rev, error) ->
                main.execute(
                    () -> {
                      saving.remove(id);
                      if (!p.isOnline()) return;
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      states.put(id, new SqliteStore.Document<>(id.toString(), rev, state));
                      next.run();
                    }));
  }

  public void open(Player p, String category, int page) {
    if (!plugin.module("scoreboard")
        || !p.hasPermission("loveessential.scoreboard")
        || !love.authenticated(p)) throw new IllegalArgumentException("errors.permission");
    var d = states.get(p.getUniqueId());
    if (d == null) {
      loadPlayer(p);
      lang.send(p, "errors.starting");
      return;
    }
    var state = d.value();
    var filtered =
        catalog.lines().stream()
            .filter(l -> state.viewAll() || category.isBlank() || l.category().equals(category))
            .toList();
    int actual = Math.max(0, Math.min(page, Math.max(0, (filtered.size() - 1) / 21)));
    var menu = new Menu();
    menu.inventory = Bukkit.createInventory(menu, 54, lang.render("sidebar.menu"));
    MenuStyle.frame(menu.inventory);
    menu.inventory.setItem(
        0,
        dev.lovelace.loveessential.module.PlayerProfiles.head(
            p, lang.render("sidebar.profile"), List.of(lang.render("sidebar.profile-help"))));
    menu.clicks.put(0, c -> p.performCommand("loveessential:loveessential profile"));
    put(
        menu,
        1,
        Material.LEVER,
        state.enabled() ? "sidebar.on" : "sidebar.off",
        c ->
            save(
                p,
                new State(!state.enabled(), state.viewAll(), state.entries()),
                () -> open(p, category, actual)));
    put(
        menu,
        3,
        Material.COMPASS,
        "sidebar.category",
        c -> {
          var categories = new ArrayList<>(catalog.categories().keySet());
          categories.sort(String::compareTo);
          categories.addFirst("");
          int index = categories.indexOf(category);
          open(p, categories.get((index + 1) % categories.size()), 0);
        });
    put(
        menu,
        2,
        Material.SPYGLASS,
        "sidebar.view-all",
        c ->
            save(
                p,
                new State(state.enabled(), !state.viewAll(), state.entries()),
                () -> open(p, category, actual)));
    if (p.hasPermission("loveessential.admin") && ui != null)
      put(menu, 50, Material.COMPARATOR, "sidebar.edit", c -> settings(p));
    for (int i = 0; i < Math.min(5, catalog.presets().size()); i++) {
      var preset = catalog.presets().get(i);
      int slot = 4 + i;
      menu.inventory.setItem(
          slot,
          MenuStyle.item(
              Material.BOOK,
              lang.render(preset.nameKey()),
              List.of(lang.render("sidebar.preset-help"))));
      menu.clicks.put(
          slot,
          c ->
              save(
                  p,
                  new State(
                      true,
                      state.viewAll(),
                      preset.entries().stream()
                          .filter(e -> line(e.id()) != null && available(p, line(e.id())))
                          .limit(maxSelected())
                          .toList()),
                  () -> open(p, category, actual)));
    }
    for (int i = actual * 21; i < Math.min(filtered.size(), (actual + 1) * 21); i++) {
      var l = filtered.get(i);
      int position = -1;
      for (int j = 0; j < state.entries().size(); j++)
        if (state.entries().get(j).id().equals(l.id())) position = j;
      int selected = position;
      var lore = new ArrayList<Component>();
      lore.add(
          lang.render(
              selected < 0 ? "sidebar.not-selected" : "sidebar.selected",
              Map.of("position", "" + (selected + 1))));
      int variant =
          selected < 0
              ? 0
              : Math.floorMod(state.entries().get(selected).variant(), l.variants().size());
      lore.add(value(p, l.variants().get(variant)));
      if (variant < l.variantNames().size() && !lang.text(l.variantNames().get(variant)).isBlank())
        lore.add(value(p, l.variantNames().get(variant)));
      for (String key : l.loreKeys()) lore.add(value(p, key));
      lore.add(lang.render("sidebar.line-help"));
      if (!available(p, l)) lore.add(lang.render("sidebar.unavailable"));
      menu.inventory.setItem(
          MenuStyle.slot(i % 21, Math.min(21, filtered.size() - actual * 21)),
          MenuStyle.item(
              available(p, l) ? Material.PAPER : Material.GRAY_DYE,
              lang.render(l.nameKey()),
              lore));
      menu.clicks.put(
          MenuStyle.slot(i % 21, Math.min(21, filtered.size() - actual * 21)),
          c -> {
            var entries = new ArrayList<>(state.entries());
            if (c == ClickType.SHIFT_LEFT && selected >= 0)
              entries.set(
                  selected,
                  new Selection(
                      l.id(), (entries.get(selected).variant() + 1) % l.variants().size()));
            else if (c == ClickType.SHIFT_RIGHT && selected > 0)
              Collections.swap(entries, selected, selected - 1);
            else if (c == ClickType.RIGHT && selected >= 0 && selected + 1 < entries.size())
              Collections.swap(entries, selected, selected + 1);
            else if (c == ClickType.LEFT) {
              if (selected >= 0) entries.remove(selected);
              else if (available(p, l) && entries.size() < maxSelected())
                entries.add(new Selection(l.id(), 0));
              else {
                lang.send(p, "sidebar.limit");
                return;
              }
            } else return;
            save(
                p,
                new State(state.enabled(), state.viewAll(), entries),
                () -> open(p, category, actual));
          });
    }
    if (actual > 0)
      put(menu, 36, Material.ARROW, "gui.previous", c -> open(p, category, actual - 1));
    if ((actual + 1) * 21 < filtered.size())
      put(menu, 44, Material.ARROW, "common.next", c -> open(p, category, actual + 1));
    put(menu, 52, Material.BARRIER, "gui.close", c -> p.closeInventory());
    p.openInventory(menu.inventory);
  }

  private boolean admin(Player p) {
    return plugin.module("scoreboard") && p.hasPermission("loveessential.admin");
  }

  public CompletableFuture<Void> saveCatalog(
      Catalog value, long revision, String actor, Map<String, String> values) {
    if (value.max() < 1
        || value.max() > 8
        || value.lines().size() > 256
        || value.interval() < 2
        || value.interval() > 1200) throw new IllegalArgumentException("errors.input");
    var ids = new HashSet<String>();
    for (var line : value.lines())
      if (!line.id().matches("[A-Za-z0-9_-]{1,48}")
          || !ids.add(line.id())
          || line.variants().isEmpty()
          || line.variants().size() > 8) throw new IllegalArgumentException("errors.input");
    return texts
        .texts(values)
        .thenCompose(v -> store.save("scoreboard-catalog", "default", revision, value, actor))
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      catalog = value;
                      catalogRevision = rev;
                      return null;
                    }));
  }

  private void saved(Player p, CompletableFuture<?> future, Runnable after) {
    future.whenComplete(
        (v, e) ->
            main.execute(
                () -> {
                  if (!p.isOnline()) return;
                  if (e != null) ui.error(p, e);
                  else {
                    lang.send(p, "chat.saved");
                    if (admin(p)) after.run();
                  }
                }));
  }

  public void settings(Player p) {
    if (!admin(p)) throw new IllegalArgumentException("errors.permission");
    var menu = new Menu();
    menu.adminOnly = true;
    menu.inventory = Bukkit.createInventory(menu, 54, lang.render("sidebar.admin-menu"));
    MenuStyle.frame(menu.inventory);
    menu.inventory.setItem(
        20, MenuStyle.button(lang, Material.WRITABLE_BOOK, "sidebar.edit", "sidebar.edit-help"));
    menu.clicks.put(20, click -> editCatalog(p, 0));
    menu.inventory.setItem(
        22,
        MenuStyle.button(lang, Material.GRASS_BLOCK, "sidebar.world-menu", "sidebar.world-help"));
    menu.clicks.put(22, click -> worlds(p, 0));
    menu.inventory.setItem(
        24,
        MenuStyle.button(
            lang, Material.COMPARATOR, "sidebar.module-settings", "sidebar.module-settings-help"));
    menu.clicks.put(24, click -> moduleSettings.accept(p));
    menu.inventory.setItem(
        31,
        MenuStyle.item(
            Material.BOOK,
            lang.render("sidebar.rules"),
            List.of(lang.render("sidebar.rules-help"), lang.render("sidebar.api-help"))));
    menu.inventory.setItem(
        51, MenuStyle.button(lang, Material.ARROW, "common.back", "gui.click.open"));
    menu.clicks.put(51, click -> open(p, "", 0));
    menu.inventory.setItem(
        52, MenuStyle.button(lang, Material.BARRIER, "gui.close", "gui.click.close"));
    menu.clicks.put(52, click -> p.closeInventory());
    p.openInventory(menu.inventory);
  }

  private java.util.function.Consumer<Player> moduleSettings = p -> {};

  public void moduleSettings(java.util.function.Consumer<Player> opener) {
    moduleSettings = opener;
  }

  public void worlds(Player p, int page) {
    if (!admin(p)) throw new IllegalArgumentException("errors.permission");
    var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    Bukkit.getWorlds().forEach(w -> names.add(w.getName()));
    names.addAll(catalog.disabledWorlds());
    names.addAll(plugin.getConfig().getStringList("scoreboard.enabled-worlds"));
    names.addAll(plugin.getConfig().getStringList("scoreboard.disabled-worlds"));
    var worlds = List.copyOf(names);
    int actual = Math.max(0, Math.min(page, Math.max(0, (worlds.size() - 1) / 21)));
    var menu = new Menu();
    menu.adminOnly = true;
    menu.inventory = Bukkit.createInventory(menu, 54, lang.render("sidebar.world-menu"));
    MenuStyle.frame(menu.inventory);
    for (int i = actual * 21; i < Math.min(worlds.size(), (actual + 1) * 21); i++) {
      String name = worlds.get(i);
      boolean blocked = catalog.disabledWorlds().stream().anyMatch(name::equalsIgnoreCase);
      boolean configBlocked =
          plugin.getConfig().getStringList("scoreboard.disabled-worlds").stream()
              .anyMatch(name::equalsIgnoreCase);
      int slot = MenuStyle.CONTENT[i % 21];
      menu.inventory.setItem(
          slot,
          MenuStyle.item(
              blocked || configBlocked ? Material.RED_CONCRETE : Material.GRASS_BLOCK,
              lang.render("sidebar.world-title", Map.of("world", name)),
              List.of(
                  lang.render(
                      blocked || configBlocked ? "sidebar.world-blocked" : "sidebar.world-allowed"),
                  lang.render(configBlocked ? "sidebar.world-config" : "sidebar.world-click"))));
      if (!configBlocked)
        menu.clicks.put(
            slot,
            click -> {
              var current = catalog;
              var disabled = new ArrayList<>(current.disabledWorlds());
              if (!disabled.removeIf(name::equalsIgnoreCase)) disabled.add(name);
              saved(
                  p,
                  saveCatalog(
                      new Catalog(
                          current.titleKey(),
                          current.separatorKey(),
                          current.bottomKey(),
                          current.max(),
                          current.interval(),
                          disabled,
                          current.categories(),
                          current.lines(),
                          current.presets(),
                          current.defaultPreset()),
                      catalogRevision,
                      p.getUniqueId().toString(),
                      Map.of()),
                  () -> worlds(p, actual));
            });
    }
    if (actual > 0) {
      menu.inventory.setItem(36, MenuStyle.button(lang, Material.ARROW, "gui.previous"));
      menu.clicks.put(36, click -> worlds(p, actual - 1));
    }
    if ((actual + 1) * 21 < worlds.size()) {
      menu.inventory.setItem(44, MenuStyle.button(lang, Material.ARROW, "common.next"));
      menu.clicks.put(44, click -> worlds(p, actual + 1));
    }
    menu.inventory.setItem(51, MenuStyle.button(lang, Material.ARROW, "common.back"));
    menu.clicks.put(51, click -> settings(p));
    menu.inventory.setItem(52, MenuStyle.button(lang, Material.BARRIER, "gui.close"));
    menu.clicks.put(52, click -> p.closeInventory());
    p.openInventory(menu.inventory);
  }

  public void editCatalog(Player p, int page) {
    if (!admin(p)) throw new IllegalArgumentException("errors.permission");
    var c = catalog;
    long revision = catalogRevision;
    var buttons = new ArrayList<dev.lovelace.loveessential.dialog.NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              String prefix = "sidebar-text.custom." + UUID.randomUUID();
              var value =
                  new Catalog(
                      prefix + ".title",
                      prefix + ".separator",
                      prefix + ".bottom",
                      Integer.parseInt(v.get("max")),
                      c.interval(),
                      v.get("worlds").isBlank()
                          ? List.of()
                          : Arrays.stream(v.get("worlds").split(",")).map(String::trim).toList(),
                      c.categories(),
                      c.lines(),
                      c.presets(),
                      c.defaultPreset());
              saved(
                  p,
                  saveCatalog(
                      value,
                      revision,
                      p.getUniqueId().toString(),
                      Map.of(
                          value.titleKey(),
                          v.get("title"),
                          value.separatorKey(),
                          v.get("separator"),
                          value.bottomKey(),
                          v.get("bottom"))),
                  () -> editCatalog(p, page));
            }));
    int start = page * 16;
    for (int i = start; i < Math.min(start + 16, c.lines().size()); i++) {
      int index = i;
      buttons.add(
          new dev.lovelace.loveessential.dialog.NativeDialogs.Button(
              "line" + i,
              lang.render(c.lines().get(i).nameKey()),
              v -> editLine(p, c, revision, index)));
    }
    buttons.add(ui.button("add", "sidebar.add-line", v -> editLine(p, c, revision, -1)));
    if (page > 0) buttons.add(ui.button("prev", "gui.previous", v -> editCatalog(p, page - 1)));
    if (start + 16 < c.lines().size())
      buttons.add(ui.button("next", "common.next", v -> editCatalog(p, page + 1)));
    ui.show(
        p,
        "sidebar.edit",
        "sidebar.edit-help",
        List.of(
            ui.text("title", "windows.heading", lang.text(c.titleKey()), 256),
            ui.text("separator", "sidebar.separator", lang.text(c.separatorKey()), 256),
            ui.text("bottom", "sidebar.bottom", lang.text(c.bottomKey()), 256),
            ui.text("max", "sidebar.max", "" + c.max(), 1),
            ui.text(
                "worlds", "sidebar.disabled-worlds", String.join(",", c.disabledWorlds()), 1024)),
        buttons,
        this::admin);
  }

  private void editLine(Player p, Catalog c, long revision, int index) {
    var old =
        index < 0
            ? new Line("", "ui.empty", "general", List.of("ui.empty"), "", "", "ui.empty")
            : c.lines().get(index);
    var buttons = new ArrayList<dev.lovelace.loveessential.dialog.NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              String prefix = "sidebar-text.custom." + UUID.randomUUID();
              var values = new HashMap<String, String>();
              values.put(prefix + ".name", v.get("name"));
              values.put(prefix + ".reason", v.get("reason"));
              var variants = new ArrayList<String>();
              int n = 0;
              for (String value : v.get("variants").split("\\|", -1)) {
                String key = prefix + ".variant." + n++;
                values.put(key, value);
                variants.add(key);
              }
              var line =
                  new Line(
                      v.get("id"),
                      prefix + ".name",
                      v.get("category"),
                      variants,
                      v.get("requirement"),
                      v.get("expected"),
                      prefix + ".reason",
                      java.util.stream.IntStream.range(0, variants.size())
                          .mapToObj(
                              i ->
                                  i < old.variantNames().size()
                                      ? old.variantNames().get(i)
                                      : "ui.empty")
                          .toList(),
                      old.loreKeys());
              var list = new ArrayList<>(c.lines());
              if (index < 0) list.add(line);
              else list.set(index, line);
              var categories = new LinkedHashMap<>(c.categories());
              if (!categories.containsKey(line.category())) {
                values.put(prefix + ".category", line.category());
                categories.put(line.category(), prefix + ".category");
              }
              saved(
                  p,
                  saveCatalog(
                      new Catalog(
                          c.titleKey(),
                          c.separatorKey(),
                          c.bottomKey(),
                          c.max(),
                          c.interval(),
                          c.disabledWorlds(),
                          categories,
                          list,
                          c.presets(),
                          c.defaultPreset()),
                      revision,
                      p.getUniqueId().toString(),
                      values),
                  () -> settings(p));
            }));
    if (index >= 0)
      buttons.add(
          ui.button(
              "delete",
              "ui.delete",
              v ->
                  ui.confirm(
                      p,
                      "sidebar.delete-line",
                      () -> {
                        var list = new ArrayList<>(c.lines());
                        list.remove(index);
                        saved(
                            p,
                            saveCatalog(
                                new Catalog(
                                    c.titleKey(),
                                    c.separatorKey(),
                                    c.bottomKey(),
                                    c.max(),
                                    c.interval(),
                                    c.disabledWorlds(),
                                    c.categories(),
                                    list,
                                    c.presets(),
                                    c.defaultPreset()),
                                revision,
                                p.getUniqueId().toString(),
                                Map.of()),
                            () -> settings(p));
                      },
                      () -> editLine(p, c, revision, index),
                      this::admin)));
    buttons.add(ui.button("back", "common.back", v -> editCatalog(p, 0)));
    ui.show(
        p,
        "sidebar.line-editor",
        "sidebar.line-editor-help",
        List.of(
            ui.text("id", "sidebar.line-id", old.id(), 48),
            ui.text("name", "windows.label", lang.text(old.nameKey()), 256),
            ui.text("category", "sidebar.category-id", old.category(), 48),
            ui.text(
                "variants",
                "sidebar.variants",
                String.join("|", old.variants().stream().map(lang::text).toList()),
                2048),
            ui.text("requirement", "sidebar.requirement", old.requirement(), 256),
            ui.text("expected", "sidebar.expected", old.expected(), 128),
            ui.text("reason", "sidebar.reason", lang.text(old.reasonKey()), 256)),
        buttons,
        this::admin);
  }

  private void put(
      Menu m, int slot, Material icon, String title, java.util.function.Consumer<ClickType> click) {
    m.inventory.setItem(slot, MenuStyle.button(lang, icon, title));
    m.clicks.put(slot, click);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() < 0 || e.getRawSlot() >= 54)
      return;
    var click = m.clicks.get(e.getRawSlot());
    if (click != null)
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (plugin.ready()
                    && plugin.module("scoreboard")
                    && love.authenticated(p)
                    && p.hasPermission("loveessential.scoreboard")
                    && (!m.adminOnly || admin(p))
                    && p.getOpenInventory().getTopInventory() != null
                    && p.getOpenInventory().getTopInventory().getHolder() == m)
                  click.accept(e.getClick());
              });
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    // Startup imports finish before start() loads all online players.
    if (plugin.ready()) loadPlayer(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    overrides.removePlayer(e.getPlayer().getUniqueId());
    release(e.getPlayer());
    states.remove(e.getPlayer().getUniqueId());
    loading.remove(e.getPlayer().getUniqueId());
    loadSessions.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void ownerDisabled(org.bukkit.event.server.PluginDisableEvent e) {
    overrides.removeOwner(e.getPlugin());
  }

  public void refresh() {
    if (task != null) {
      task.cancel();
      task =
          Bukkit.getScheduler()
              .runTaskTimer(
                  plugin,
                  this::tick,
                  1,
                  Math.max(2, plugin.getConfig().getLong("scoreboard.refresh-ticks", 20)));
    }
    if (!plugin.module("scoreboard")) for (var p : Bukkit.getOnlinePlayers()) release(p);
  }

  @Override
  public void close() {
    if (task != null) task.cancel();
    overrides.close();
    Bukkit.getServicesManager()
        .unregister(dev.lovelace.loveessential.api.ScoreboardService.class, overrides);
    for (var p : Bukkit.getOnlinePlayers()) {
      if (p.getOpenInventory().getTopInventory() != null
          && p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
      release(p);
    }
    for (var board : rendered) {
      var obj = board.getObjective("le_sidebar");
      if (obj != null) obj.unregister();
    }
    rendered.clear();
    bridge.close();
    HandlerList.unregisterAll(this);
  }
}
