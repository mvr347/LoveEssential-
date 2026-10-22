package dev.lovelace.loveessential.scoreboard;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.gui.ListMenu;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.tab.TabSettings;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * {@code /le scoreboard edit}: the list of named boards (paged 54-slot menu) and the editor of one
 * board. Lines are edited through {@link TabSettings#lines}, the same editor the TAB module uses.
 */
final class ScoreboardSettings {
  /** Upper bound on named boards; a larger list only makes the admin menu slower to page. */
  static final int MAX_BOARDS = 64;

  private final LoveEssentialPlugin plugin;
  private final Lang lang;
  private final ScoreboardModule module;
  private final NativeDialogs ui;
  private final Consumer<Player> moduleSettings;
  private final TabSettings lines;

  ScoreboardSettings(
      LoveEssentialPlugin plugin,
      Lang lang,
      ScoreboardModule module,
      NativeDialogs ui,
      Consumer<Player> moduleSettings,
      TabSettings lines) {
    this.plugin = plugin;
    this.lang = lang;
    this.module = module;
    this.ui = ui;
    this.moduleSettings = moduleSettings;
    this.lines = lines;
  }

  private static ListMenu.Item item(Material icon, Component title, List<Component> lore, Runnable action) {
    return new ListMenu.Item(ListMenu.entry(icon, title, lore), action);
  }

  /** The list shows the title as plain text; raw MiniMessage tags would read as broken text. */
  private static String plain(String miniMessage) {
    try {
      return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
          .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(miniMessage));
    } catch (RuntimeException e) {
      return "";
    }
  }

  private Component text(String key) {
    return lang.render(key);
  }

  void open(Player p) {
    boolean enabled = plugin.module("scoreboard");
    var controls =
        List.of(
            item(
                enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                text(enabled ? "sidebar.module-enabled" : "sidebar.module-disabled"),
                List.of(text("sidebar.module-toggle-help")),
                () -> toggleModule(p, !enabled)),
            item(
                Material.LIME_DYE,
                text("sidebar.add-board"),
                List.of(text("sidebar.add-board-help")),
                () -> board(p, null)),
            item(
                Material.COMPARATOR,
                text("sidebar.module-settings"),
                List.of(text("sidebar.module-settings-help")),
                () -> moduleSettings.accept(p)));
    var items = new ArrayList<ListMenu.Item>();
    for (var board : ScoreboardModule.boards(plugin.getConfig()))
      items.add(
          item(
              Material.WRITABLE_BOOK,
              lang.render("gui.label", Map.of("name", board.id())),
              List.of(
                  lang.render(
                      "sidebar.board-help",
                      Map.of(
                          "title", plain(board.title()),
                          "lines", Integer.toString(board.lines().size())))),
              () -> board(p, board.id())));
    ListMenu.open(lang, p, "sidebar-editor.title", controls, items, 0, null);
  }

  private void toggleModule(Player p, boolean value) {
    plugin
        .module("scoreboard", value)
        .whenComplete(
            (v, error) ->
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (!p.isOnline()) return;
                          if (error != null) ui.error(p, error);
                          else open(p);
                        }));
  }

  /** Editor for one board; {@code old} is null when a new board is being created. */
  @SuppressWarnings("unchecked")
  private void board(Player p, String old) {
    var expected = snapshot();
    var value = old == null ? Map.<String, Object>of() : (Map<String, Object>) expected.get(old);
    var fields =
        List.of(
            ui.text("id", "sidebar.id", old == null ? "" : old, 48),
            ui.text("title", "sidebar.title", Objects.toString(value.getOrDefault("title", "")), 1024),
            ui.text(
                "display-condition",
                "sidebar.display-condition",
                Objects.toString(value.getOrDefault("display-condition", "")),
                48));
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              String id = id(v.get("id"), old, expected);
              var next = new LinkedHashMap<>(expected);
              if (old != null) next.remove(old);
              var entry = new LinkedHashMap<String, Object>(value);
              String title = v.get("title").strip();
              if (title.isEmpty()) throw new IllegalArgumentException("errors.input");
              entry.put("title", title);
              String condition = v.get("display-condition").strip();
              if (condition.isEmpty()) entry.remove("display-condition");
              else entry.put("display-condition", condition);
              entry.putIfAbsent("lines", new ArrayList<>(List.of("<gray>Строка 1")));
              if (!next.containsKey(id) && next.size() >= MAX_BOARDS)
                throw new IllegalArgumentException("errors.config");
              next.put(id, entry);
              save(p, expected, next, () -> open(p));
            }));
    if (old != null) {
      buttons.add(
          ui.button(
              "lines",
              "sidebar.lines",
              v ->
                  lines.lines(
                      p,
                      "scoreboard.scoreboards." + old + ".lines",
                      0,
                      () -> board(p, old))));
      buttons.add(
          ui.button(
              "delete",
              "gui.remove",
              v ->
                  ui.confirm(
                      p,
                      "gui.remove",
                      () -> {
                        var next = new LinkedHashMap<>(expected);
                        next.remove(old);
                        save(p, expected, next, () -> open(p));
                      },
                      () -> board(p, old),
                      this::admin)));
    }
    buttons.add(ui.button("back", "common.back", v -> open(p)));
    ui.show(
        p,
        old == null ? "sidebar.board-new-title" : "sidebar.board-title",
        "sidebar.board-help-text",
        fields,
        buttons,
        this::admin);
  }

  private boolean admin(Player p) {
    return plugin.ready() && p.isOnline() && p.hasPermission("loveessential.admin");
  }

  private Map<String, Object> snapshot() {
    var result = new LinkedHashMap<String, Object>();
    var section = plugin.getConfig().getConfigurationSection("scoreboard.scoreboards");
    if (section == null) return result;
    for (String id : section.getKeys(false))
      result.put(id, dev.lovelace.loveessential.config.ModuleConfigs.snapshot(section.get(id)));
    return result;
  }

  private static String id(String raw, String old, Map<String, Object> expected) {
    String id = raw.strip();
    if (!id.matches("[A-Za-z0-9_-]{1,48}") || !id.equals(old) && expected.containsKey(id))
      throw new IllegalArgumentException("errors.input");
    return id;
  }

  private void save(Player p, Map<String, Object> expected, Map<String, Object> next, Runnable after) {
    plugin
        .editSetting("scoreboard", "scoreboards", expected, next)
        .whenComplete(
            (v, error) ->
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (!admin(p)) return;
                          if (error != null) ui.error(p, error);
                          else after.run();
                        }));
  }
}
