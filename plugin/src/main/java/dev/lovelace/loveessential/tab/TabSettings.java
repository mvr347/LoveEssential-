package dev.lovelace.loveessential.tab;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.config.ModuleConfigs;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.gui.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.inventory.*;

/**
 * Editor for tab.yml and for lists of text lines in any module. Configuration edits keep snapshots,
 * so a stale dialog cannot replace another staff member's work.
 */
public final class TabSettings {
  private static final String TAB = "tab.";

  private final LoveEssentialPlugin plugin;
  private final NativeDialogs ui;
  private final Lang lang;
  private final LoveBridge love;
  public Consumer<Player> advanced = p -> {};

  public TabSettings(LoveEssentialPlugin plugin, NativeDialogs ui, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.ui = ui;
    this.lang = lang;
    this.love = love;
  }

  private boolean admin(Player p) {
    return plugin.ready()
        && p.isOnline()
        && love.authenticated(p)
        && p.hasPermission("loveessential.admin");
  }

  private void require(Player p) {
    if (!admin(p)) throw new IllegalArgumentException("errors.permission");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(String path) {
    Object value = ModuleConfigs.snapshot(plugin.getConfig().get(path));
    return value instanceof Map<?, ?>
        ? new LinkedHashMap<>((Map<String, Object>) value)
        : new LinkedHashMap<>();
  }

  /**
   * Writes {@code value} at {@code path}, a full runtime path such as {@code tab.groups}. The first
   * segment names the module file; the rest is the path inside it.
   */
  private void save(Player p, String path, Object expected, Object value, Runnable next) {
    require(p);
    String module = path.substring(0, path.indexOf('.'));
    String local = path.substring(module.length() + 1);
    plugin
        .editSetting(module, local, expected, value)
        .whenComplete(
            (ignored, error) ->
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (!admin(p)) return;
                          if (error != null) ui.error(p, error);
                          else next.run();
                        }));
  }

  /** Lists show plain text; raw MiniMessage tags or &-codes in a lore line would read as broken text. */
  private static String plain(String miniMessage) {
    try {
      return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
          .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                  .deserialize(dev.lovelace.loveessential.tab.TabSyntax.legacy(miniMessage)));
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static ListMenu.Item item(Material icon, Component title, List<Component> lore, Runnable action) {
    return new ListMenu.Item(ListMenu.entry(icon, title, lore), action);
  }

  private Component text(String key, Map<String, String> vars) {
    return lang.render(key, vars);
  }

  /** Root of {@code /le tab}: groups, users, sorting, nametag, and the generic settings editor. */
  public void open(Player p) {
    require(p);
    var controls =
        List.of(
            item(
                Material.BOOK,
                text("tab-editor.groups", Map.of()),
                List.of(text("tab-editor.groups-help", Map.of())),
                () -> groups(p, 0)),
            item(
                Material.PLAYER_HEAD,
                text("tab-editor.users", Map.of()),
                List.of(text("tab-editor.users-help", Map.of())),
                () -> users(p, 0)),
            item(
                Material.COMPARATOR,
                text("tab-editor.sorting", Map.of()),
                List.of(text("tab-editor.sorting-help", Map.of())),
                () -> lines(p, TAB + "sorting", 0, () -> open(p))),
            item(
                Material.NAME_TAG,
                text("tab-editor.nametag", Map.of()),
                List.of(text("tab-editor.nametag-help", Map.of())),
                () -> nametag(p)),
            item(
                Material.REPEATER,
                text("tab-editor.advanced", Map.of()),
                List.of(text("tab-editor.advanced-help", Map.of())),
                () -> advanced.accept(p)));
    ListMenu.open(lang, p, "tab-editor.title", controls, List.of(), 0, null);
  }

  // ---------------------------------------------------------------- lists

  /** A page of groups or users; each entry opens its editor. */
  public void groups(Player p, int page) {
    entries(p, "groups", page);
  }

  public void users(Player p, int page) {
    entries(p, "users", page);
  }

  private void entries(Player p, String section, int page) {
    require(p);
    boolean groups = section.equals("groups");
    var ids = map(TAB + section).keySet().stream().sorted().toList();
    var items = new ArrayList<ListMenu.Item>();
    for (String id : ids) {
      var value = map(TAB + section + "." + id);
      items.add(
          item(
              groups ? Material.BOOK : Material.PLAYER_HEAD,
              lang.render("gui.label", Map.of("name", id)),
              List.of(
                  lang.render(
                      "tab-editor.entry-help",
                      Map.of("prefix", plain(Objects.toString(value.get("tabprefix"), ""))))),
              () -> editor(p, section, id)));
    }
    var controls =
        List.of(
            item(
                Material.LIME_DYE,
                text(groups ? "tab-editor.add-group" : "tab-editor.add-user", Map.of()),
                List.of(),
                () -> editor(p, section, null)));
    ListMenu.open(
        lang,
        p,
        groups ? "tab-groups.title" : "tab-users.title",
        controls,
        items,
        page,
        () -> open(p));
  }

  private void editor(Player p, String section, String id) {
    if (section.equals("groups")) group(p, id);
    else user(p, id);
  }

  /** Editor for one group; the header and footer are line lists edited separately. */
  @SuppressWarnings("unchecked")
  private void group(Player p, String old) {
    require(p);
    String section = "groups";
    var expected = map(TAB + section);
    var value = old == null ? Map.<String, Object>of() : (Map<String, Object>) expected.get(old);
    showProperties(p, section, old, value, expected, "tab-editor.group-title", "groups");
  }

  @SuppressWarnings("unchecked")
  private void user(Player p, String old) {
    require(p);
    var expected = map(TAB + "users");
    var value = old == null ? Map.<String, Object>of() : (Map<String, Object>) expected.get(old);
    showProperties(p, "users", old, value, expected, "tab-editor.user-title", "users");
  }

  private void showProperties(
      Player p,
      String section,
      String old,
      Map<String, Object> value,
      Map<String, Object> expected,
      String title,
      String back) {
    var fields = new ArrayList<NativeDialogs.Field>();
    fields.add(ui.text("id", "tab-editor.id", old == null ? "" : old, 48));
    for (String key : List.of("tabprefix", "tabsuffix", "customtabname", "tagprefix", "tagsuffix"))
      fields.add(
          ui.text(
              key,
              "tab-editor." + key,
              Objects.toString(value.get(key), ""),
              1024));
    fields.add(
        ui.text(
            "display-condition",
            "tab-editor.display-condition",
            Objects.toString(value.get("display-condition"), ""),
            48));
    fields.add(
        ui.text(
            "permission",
            "tab-editor.permission",
            Objects.toString(value.get("permission"), ""),
            128));
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              String id = id(v, old, expected);
              var next = new LinkedHashMap<>(expected);
              if (old != null) next.remove(old);
              var entry = new LinkedHashMap<String, Object>(value);
              for (String key : List.of("tabprefix", "tabsuffix", "customtabname", "tagprefix", "tagsuffix", "display-condition", "permission")) {
                String text = v.get(key).strip();
                if (text.isEmpty()) entry.remove(key);
                else entry.put(key, text);
              }
              next.put(id, entry);
              save(p, TAB + section, expected, next, () -> entries(p, section, 0));
            }));
    if (old != null) {
      buttons.add(
          ui.button(
              "header",
              "tab-editor.header-lines",
              v -> lines(p, TAB + section + "." + old + ".header", 0, () -> editor(p, section, old))));
      buttons.add(
          ui.button(
              "footer",
              "tab-editor.footer-lines",
              v -> lines(p, TAB + section + "." + old + ".footer", 0, () -> editor(p, section, old))));
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
                        save(p, TAB + section, expected, next, () -> entries(p, section, 0));
                      },
                      () -> editor(p, section, old),
                      this::admin)));
    }
    buttons.add(ui.button("back", "common.back", v -> entries(p, section, 0)));
    ui.show(p, title, "tab-editor.groups-help", fields, buttons, this::admin);
  }

  private String id(Map<String, String> values, String old, Map<String, Object> expected) {
    String id = values.get("id").strip();
    if (!id.matches("[a-zA-Z0-9_-]{1,48}") || !id.equals(old) && expected.containsKey(id))
      throw new IllegalArgumentException("errors.input");
    return id;
  }

  /**
   * Line-list editor for any text list: {@code path} is a full runtime path such as
   * {@code tab.sorting} or {@code scoreboard.scoreboards.main.lines}. {@code back} returns to the
   * caller's editor.
   */
  public void lines(Player p, String path, int page, Runnable back) {
    require(p);
    var old = List.copyOf(plugin.getConfig().getStringList(path));
    int actual = Math.clamp(page, 0, Math.max(0, (old.size() - 1) / 12));
    var buttons = new ArrayList<NativeDialogs.Button>();
    for (int i = actual * 12; i < Math.min(old.size(), (actual + 1) * 12); i++) {
      int index = i;
      buttons.add(
          new NativeDialogs.Button(
              "line" + i,
              lang.render("tab-editor.line", Map.of("index", "" + (i + 1))),
              values -> line(p, path, old, index, actual, back)));
    }
    buttons.add(
        ui.button("add", "tab-editor.add-line", v -> line(p, path, old, -1, actual, back)));
    if (actual > 0)
      buttons.add(ui.button("prev", "gui.previous", v -> lines(p, path, actual - 1, back)));
    if ((actual + 1) * 12 < old.size())
      buttons.add(ui.button("next", "common.next", v -> lines(p, path, actual + 1, back)));
    buttons.add(ui.button("back", "common.back", v -> back.run()));
    ui.show(
        p,
        "tab-editor.lines-title",
        path.endsWith(".sorting") ? "tab-editor.sorting-help" : "tab-editor.lines-help",
        List.of(),
        buttons,
        this::admin);
  }

  private void line(Player p, String path, List<String> expected, int index, int page, Runnable back) {
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              var next = new ArrayList<>(expected);
              if (index < 0) next.add(v.get("text"));
              else next.set(index, v.get("text"));
              save(p, path, expected, next, () -> lines(p, path, page, back));
            }));
    if (index >= 0) {
      if (index > 0)
        buttons.add(
            ui.button(
                "up",
                "tab-editor.up",
                v -> {
                  var next = new ArrayList<>(expected);
                  Collections.swap(next, index, index - 1);
                  save(p, path, expected, next, () -> lines(p, path, page, back));
                }));
      if (index + 1 < expected.size())
        buttons.add(
            ui.button(
                "down",
                "tab-editor.down",
                v -> {
                  var next = new ArrayList<>(expected);
                  Collections.swap(next, index, index + 1);
                  save(p, path, expected, next, () -> lines(p, path, page, back));
                }));
      buttons.add(
          ui.button(
              "delete",
              "gui.remove",
              v -> {
                var next = new ArrayList<>(expected);
                next.remove(index);
                save(p, path, expected, next, () -> lines(p, path, page, back));
              }));
    }
    buttons.add(ui.button("back", "common.back", v -> lines(p, path, page, back)));
    ui.show(
        p,
        "tab-editor.line-title",
        path.endsWith(".sorting") ? "tab-editor.sorting-help" : "tab-editor.lines-help",
        List.of(
            ui.text(
                "text",
                path.endsWith(".sorting") ? "tab-editor.sort-rule" : "tab-editor.text",
                index < 0 ? "" : expected.get(index),
                2048)),
        buttons,
        this::admin);
  }

  private void nametag(Player p) {
    require(p);
    var expected = map(TAB + "nametag");
    String invisible = String.join(",", plugin.getConfig().getStringList(TAB + "nametag.invisible-nametags"));
    ui.show(
        p,
        "tab-editor.nametag",
        "tab-editor.nametag-help",
        List.of(
            NativeDialogs.Field.select(
                "enabled",
                lang.render("tab-editor.enabled"),
                lang.text(
                    Boolean.TRUE.equals(expected.getOrDefault("enabled", true))
                        ? "players.yes"
                        : "players.no"),
                List.of(lang.text("players.yes"), lang.text("players.no"))),
            NativeDialogs.Field.select(
                "collision",
                lang.render("tab-editor.collision"),
                lang.text(
                    Boolean.TRUE.equals(expected.getOrDefault("collision", false))
                        ? "players.yes"
                        : "players.no"),
                List.of(lang.text("players.yes"), lang.text("players.no"))),
            ui.text("invisible", "tab-editor.invisible", invisible, 1024)),
        List.of(
            ui.button(
                "save",
                "content.save",
                v -> {
                  var next = new LinkedHashMap<>(expected);
                  for (String key : List.of("enabled", "collision")) {
                    String flag = v.get(key);
                    if (!List.of(lang.text("players.yes"), lang.text("players.no")).contains(flag))
                      throw new IllegalArgumentException("errors.input");
                    next.put(key, flag.equals(lang.text("players.yes")));
                  }
                  var names = new ArrayList<String>();
                  for (String part : v.get("invisible").split(",")) {
                    if (!part.isBlank()) names.add(part.strip());
                  }
                  next.put("invisible-nametags", names);
                  save(p, TAB + "nametag", expected, next, () -> open(p));
                }),
            ui.button("back", "common.back", v -> open(p))),
        this::admin);
  }

  /** Kept for the plugin's shutdown order; the editor registers no listeners of its own. */
  public void close() {}
}
