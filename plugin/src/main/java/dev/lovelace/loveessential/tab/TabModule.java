package dev.lovelace.loveessential.tab;

import com.google.gson.Gson;
import dev.lovelace.loveessential.api.Condition;
import dev.lovelace.loveessential.api.LoveEssentialService.Verdict;
import dev.lovelace.loveessential.condition.ConditionEngine;
import dev.lovelace.loveessential.lang.Lang;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

/** Per-viewer scoreboards; only this module's resources are restored on release. */
public final class TabModule implements Listener, AutoCloseable {
  private final JavaPlugin plugin;
  private final Lang lang;
  private final Placeholders placeholders;
  private final ConditionEngine conditions;
  private final Map<UUID, View> views = new HashMap<>();
  private BukkitTask task;
  private boolean enabled;
  private java.util.function.BiConsumer<Player, Scoreboard> customSidebar;
  private java.util.function.BooleanSupplier customEnabled = () -> false;
  private java.util.function.Predicate<Scoreboard> companionBoard = b -> false;

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

  private static final class View {
    final Scoreboard previous, board;
    Component header = Component.empty(), footer = Component.empty();
    final Map<String, BossBar> bars = new HashMap<>();

    View(Scoreboard before, Scoreboard board) {
      previous = before;
      this.board = board;
    }
  }

  private record Group(String id, int priority, String name, String prefix, String suffix) {}

  public TabModule(
      JavaPlugin plugin, Lang lang, Placeholders placeholders, ConditionEngine conditions) {
    this.plugin = plugin;
    this.lang = lang;
    this.placeholders = placeholders;
    this.conditions = conditions;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public void start() {
    enabled = true;
    if (task != null) task.cancel();
    task =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                this::update,
                1,
                Math.max(2, plugin.getConfig().getLong("tab.refresh-ticks", 10)));
  }

  private Group group(Player p) {
    var root = plugin.getConfig().getConfigurationSection("tab.groups");
    Group best = new Group("default", 1000, "tab.name", "tab.prefix", "tab.suffix");
    if (root != null)
      for (String id : root.getKeys(false)) {
        var s = root.getConfigurationSection(id);
        int priority = s.getInt("priority", 1000);
        if (priority < best.priority()
            && p.hasPermission(s.getString("permission", "loveessential.group." + id)))
          best =
              new Group(
                  id,
                  priority,
                  s.getString("name-key", "tab.name"),
                  s.getString("prefix-key", "tab.prefix"),
                  s.getString("suffix-key", "tab.suffix"));
      }
    for (var section :
        List.of("tab.worlds." + p.getWorld().getName(), "tab.users." + p.getUniqueId())) {
      var override = plugin.getConfig().getConfigurationSection(section);
      if (override != null)
        best =
            new Group(
                best.id(),
                override.getInt("priority", best.priority()),
                override.getString("name-key", best.name()),
                override.getString("prefix-key", best.prefix()),
                override.getString("suffix-key", best.suffix()));
    }
    return best;
  }

  private List<Player> sorted() {
    var players = new ArrayList<Player>(Bukkit.getOnlinePlayers());
    var keys = plugin.getConfig().getStringList("tab.sorting");
    Map<UUID, TabSort.Entry> values = new HashMap<>();
    for (var p : players) {
      var data = new HashMap<String, String>();
      for (String key : keys) {
        String metric =
            key.startsWith("number-desc:")
                ? key.substring(12)
                : key.startsWith("number:") ? key.substring(7) : key;
        if (!Set.of("name", "group").contains(metric))
          data.put(metric, placeholders.papi(p, p, metric));
      }
      values.put(
          p.getUniqueId(),
          new TabSort.Entry(p.getUniqueId(), p.getName(), group(p).priority(), data));
    }
    players.sort(
        (a, b) ->
            TabSort.comparator(keys)
                .compare(values.get(a.getUniqueId()), values.get(b.getUniqueId())));
    return players;
  }

  private boolean allowed(Player p, ConfigurationSection section) {
    if (section == null) return true;
    String permission = section.getString("permission", "");
    if (!permission.isEmpty() && !p.hasPermission(permission)) return false;
    var worlds = section.getStringList("worlds");
    if (!worlds.isEmpty() && !worlds.contains(p.getWorld().getName())) return false;
    String json = section.getString("condition", "");
    return json.isEmpty()
        || conditions.test(p, new Gson().fromJson(json, Condition.class)) == Verdict.ALLOW;
  }

  private void update() {
    if (!enabled) return;
    var sorted = sorted();
    for (int i = 0; i < sorted.size(); i++) {
      var target = sorted.get(i);
      if (plugin instanceof dev.lovelace.loveessential.LoveEssentialPlugin le
          && !le.allows("tab", target)) continue;
      if (plugin.getConfig().getBoolean("tab.sorting-enabled", true)) target.setPlayerListOrder(i);
      if (plugin.getConfig().getBoolean("tab.name-enabled", true)) {
        var g = group(target);
        var formatted =
            placeholders
                .render(g.prefix(), target, target)
                .append(placeholders.renderName(g.name(), target, target))
                .append(placeholders.render(g.suffix(), target, target));
        if (!formatted.equals(target.playerListName())) target.playerListName(formatted);
      }
    }
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      try {
        update(viewer, sorted);
      } catch (RuntimeException e) {
        plugin
            .getLogger()
            .warning(lang.text("logs.tab-failed") + " " + e.getClass().getSimpleName());
      }
    }
  }

  private void update(Player p, List<Player> sorted) {
    if (plugin instanceof dev.lovelace.loveessential.LoveEssentialPlugin le
        && !le.allows("tab", p)) {
      release(p);
      return;
    }
    var view = views.get(p.getUniqueId());
    if (view == null) {
      var board = Bukkit.getScoreboardManager().getNewScoreboard();
      view = new View(p.getScoreboard(), board);
      views.put(p.getUniqueId(), view);
    }
    if (plugin.getConfig().getBoolean("tab.header-footer.enabled", true)) {
      String header = plugin.getConfig().getString("tab.header-footer.header-key", "tab.header"),
          footer = plugin.getConfig().getString("tab.header-footer.footer-key", "tab.footer");
      var profiles = plugin.getConfig().getConfigurationSection("tab.header-footer.profiles");
      if (profiles != null)
        for (String id : profiles.getKeys(false)) {
          var profile = profiles.getConfigurationSection(id);
          if (allowed(p, profile)) {
            header = profile.getString("header-key", header);
            footer = profile.getString("footer-key", footer);
            break;
          }
        }
      Component h = placeholders.render(header, p, p), f = placeholders.render(footer, p, p);
      if (!h.equals(view.header) || !f.equals(view.footer)) {
        p.sendPlayerListHeaderAndFooter(h, f);
        view.header = h;
        view.footer = f;
      }
    }
    boolean respect = plugin.getConfig().getBoolean("tab.respect-other-scoreboards", true);
    if (!respect
        || companionBoard.test(p.getScoreboard())
        || p.getScoreboard().equals(view.board)
        || p.getScoreboard().equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
      p.setScoreboard(view.board);
      teams(p, view, sorted);
      sidebar(p, view);
      objective(p, view, sorted, "tab.playerlist-objective", DisplaySlot.PLAYER_LIST, "le_list");
      objective(p, view, sorted, "tab.below-name", DisplaySlot.BELOW_NAME, "le_below");
    }
    bossbars(p, view);
  }

  private void teams(Player viewer, View view, List<Player> sorted) {
    if (!plugin.getConfig().getBoolean("tab.nametag.enabled", true)) return;
    Set<String> expected = new HashSet<>();
    for (int i = 0; i < sorted.size(); i++) {
      Player target = sorted.get(i);
      if (!viewer.canSee(target)) continue;
      String id = "le_" + String.format(Locale.ROOT, "%05d", i);
      expected.add(id);
      Team team = view.board.getTeam(id);
      if (team == null) team = view.board.registerNewTeam(id);
      for (String old : new HashSet<>(team.getEntries()))
        if (!old.equals(target.getName())) team.removeEntry(old);
      team.addEntry(target.getName());
      var g = group(target);
      team.prefix(placeholders.render(g.prefix(), viewer, target));
      team.suffix(placeholders.render(g.suffix(), viewer, target));
      team.setOption(
          Team.Option.COLLISION_RULE,
          plugin.getConfig().getBoolean("tab.nametag.collision", false)
              ? Team.OptionStatus.ALWAYS
              : Team.OptionStatus.NEVER);
      team.setOption(
          Team.Option.NAME_TAG_VISIBILITY,
          plugin.getConfig().getBoolean("tab.nametag.visible", true)
              ? Team.OptionStatus.ALWAYS
              : Team.OptionStatus.NEVER);
    }
    for (Team team : new HashSet<>(view.board.getTeams()))
      if (!expected.contains(team.getName())) team.unregister();
  }

  private void sidebar(Player p, View view) {
    if (customSidebar != null && customEnabled.getAsBoolean()) {
      customSidebar.accept(p, view.board);
      return;
    }
    var section = plugin.getConfig().getConfigurationSection("tab.sidebar");
    if (section == null || !section.getBoolean("enabled", false) || !allowed(p, section)) {
      var old = view.board.getObjective("le_side");
      if (old != null) old.unregister();
      return;
    }
    Objective obj = view.board.getObjective("le_side");
    if (obj == null) {
      obj =
          view.board.registerNewObjective(
              "le_side",
              Criteria.DUMMY,
              placeholders.render(section.getString("title-key", "tab.sidebar.title"), p, p));
      obj.setDisplaySlot(DisplaySlot.SIDEBAR);
      obj.numberFormat(NumberFormat.blank());
    }
    obj.displayName(placeholders.render(section.getString("title-key", "tab.sidebar.title"), p, p));
    List<String> keys = section.getStringList("line-keys");
    for (int i = 0; i < 15; i++) {
      String entry = "le_line_" + i;
      var score = obj.getScore(entry);
      if (i < keys.size()) {
        score.setScore(keys.size() - i);
        score.customName(placeholders.render(keys.get(i), p, p));
      } else score.resetScore();
    }
  }

  private void objective(
      Player viewer, View view, List<Player> players, String path, DisplaySlot slot, String name) {
    var section = plugin.getConfig().getConfigurationSection(path);
    if (section == null || !section.getBoolean("enabled", false) || !allowed(viewer, section)) {
      var old = view.board.getObjective(name);
      if (old != null) old.unregister();
      return;
    }
    var obj = view.board.getObjective(name);
    if (obj == null) {
      obj =
          view.board.registerNewObjective(
              name,
              Criteria.DUMMY,
              placeholders.render(section.getString("title-key", "tab.objective"), viewer, viewer));
      obj.setDisplaySlot(slot);
    }
    Set<String> expected = new HashSet<>();
    for (var p : players)
      if (viewer.canSee(p)) {
        expected.add(p.getName());
        String source = section.getString("value", "ping");
        int value =
            switch (source) {
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
        obj.getScore(p.getName()).setScore(value);
      }
    for (String entry : new HashSet<>(view.board.getEntries()))
      if (!entry.startsWith("le_line_") && !expected.contains(entry))
        obj.getScore(entry).resetScore();
  }

  private void bossbars(Player p, View view) {
    var root = plugin.getConfig().getConfigurationSection("tab.bossbars");
    Set<String> active = new HashSet<>();
    if (root != null)
      for (String id : root.getKeys(false)) {
        var s = root.getConfigurationSection(id);
        if (!s.getBoolean("enabled", true) || !allowed(p, s)) continue;
        active.add(id);
        float progress = (float) Math.max(0, Math.min(1, s.getDouble("progress", 1)));
        var bar = view.bars.get(id);
        if (bar == null) {
          bar =
              BossBar.bossBar(
                  Component.empty(),
                  progress,
                  BossBar.Color.valueOf(s.getString("color", "YELLOW")),
                  BossBar.Overlay.valueOf(s.getString("overlay", "PROGRESS")));
          view.bars.put(id, bar);
          p.showBossBar(bar);
        }
        bar.name(placeholders.render(s.getString("text-key", "tab.bossbar"), p, p));
        bar.progress(progress);
      }
    for (var it = view.bars.entrySet().iterator(); it.hasNext(); ) {
      var entry = it.next();
      if (!active.contains(entry.getKey())) {
        p.hideBossBar(entry.getValue());
        it.remove();
      }
    }
  }

  private void release(Player p) {
    var view = views.remove(p.getUniqueId());
    if (view == null) return;
    view.bars.values().forEach(p::hideBossBar);
    if (p.getScoreboard().equals(view.board)) p.setScoreboard(view.previous);
    p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
    p.playerListName(null);
    p.setPlayerListOrder(0);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    release(e.getPlayer());
  }

  public void stop() {
    enabled = false;
    if (task != null) task.cancel();
    for (var p : Bukkit.getOnlinePlayers()) release(p);
    views.clear();
    placeholders.clear();
  }

  public void close() {
    stop();
    HandlerList.unregisterAll(this);
  }
}
