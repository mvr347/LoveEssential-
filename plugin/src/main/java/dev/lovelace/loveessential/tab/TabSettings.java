package dev.lovelace.loveessential.tab;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.config.ModuleConfigs;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.gui.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/**
 * Configuration edits keep snapshots so stale dialogs cannot replace another staff member's work.
 */
public final class TabSettings implements Listener {
  private final LoveEssentialPlugin plugin;
  private final NativeDialogs ui;
  private final Lang lang;
  private final LoveBridge love;
  public Consumer<Player> advanced = p -> {};

  private static final class Menu implements GuiLayout {
    Inventory inventory;
    final Map<Integer, Runnable> actions = new HashMap<>();

    public String guiId() {
      return "tab-editor.title";
    }

    public Map<Integer, ?> guiActions() {
      return actions;
    }

    public void guiPut(int slot, Runnable action) {
      actions.put(slot, action);
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }
  }

  public TabSettings(LoveEssentialPlugin plugin, NativeDialogs ui, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.ui = ui;
    this.lang = lang;
    this.love = love;
    Bukkit.getPluginManager().registerEvents(this, plugin);
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
    Object value = ModuleConfigs.snapshot(plugin.getConfig().get("tab." + path));
    return value instanceof Map<?, ?>
        ? new LinkedHashMap<>((Map<String, Object>) value)
        : new LinkedHashMap<>();
  }

  private void save(Player p, String path, Object expected, Object value, Runnable next) {
    require(p);
    plugin
        .editSetting("tab", path, expected, value)
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

  private void put(Menu menu, int slot, String key, Runnable action) {
    menu.inventory.setItem(
        slot, MenuStyle.button(lang, Material.BOOK, key, "gui.separator", "gui.click.open"));
    menu.actions.put(slot, action);
  }

  public void open(Player p) {
    require(p);
    var m = new Menu();
    m.inventory = Bukkit.createInventory(m, 54, MenuStyle.title(lang, m.guiId(), Map.of()));
    MenuStyle.frame(m.inventory);
    String[] labels = {
      "header", "footer", "groups", "placeholders", "sorting", "nametag", "sidebar", "advanced"
    };
    Runnable[] actions = {
      () -> lines(p, "header-footer.header", 0),
      () -> lines(p, "header-footer.footer", 0),
      () -> groups(p, 0),
      () -> aliases(p, 0),
      () -> lines(p, "sorting", 0),
      () -> nametag(p),
      () -> lines(p, "sidebar.lines", 0),
      () -> advanced.accept(p)
    };
    for (int i = 0; i < labels.length; i++)
      put(m, MenuStyle.slot(i, labels.length), "tab-editor." + labels[i], actions[i]);
    put(m, 51, "common.back", () -> p.performCommand("loveessential:loveessential"));
    put(m, 52, "gui.close", p::closeInventory);
    MenuStyle.open(p, m.inventory);
  }

  public void lines(Player p, String path, int page) {
    require(p);
    var old = List.copyOf(plugin.getConfig().getStringList("tab." + path));
    int actual = Math.clamp(page, 0, Math.max(0, (old.size() - 1) / 12));
    var buttons = new ArrayList<NativeDialogs.Button>();
    for (int i = actual * 12; i < Math.min(old.size(), (actual + 1) * 12); i++) {
      int index = i;
      buttons.add(
          new NativeDialogs.Button(
              "line" + i,
              lang.render("tab-editor.line", Map.of("index", "" + (i + 1))),
              values -> line(p, path, old, index, actual)));
    }
    buttons.add(ui.button("add", "tab-editor.add-line", v -> line(p, path, old, -1, actual)));
    if (actual > 0) buttons.add(ui.button("prev", "gui.previous", v -> lines(p, path, actual - 1)));
    if ((actual + 1) * 12 < old.size())
      buttons.add(ui.button("next", "common.next", v -> lines(p, path, actual + 1)));
    buttons.add(ui.button("back", "common.back", v -> open(p)));
    ui.show(
        p,
        "tab-editor.lines-title",
        path.equals("sorting") ? "tab-editor.sorting-help" : "tab-editor.lines-help",
        List.of(),
        buttons,
        this::admin);
  }

  private void line(Player p, String path, List<String> expected, int index, int page) {
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              var next = new ArrayList<>(expected);
              if (index < 0) next.add(v.get("text"));
              else next.set(index, v.get("text"));
              save(p, path, expected, next, () -> lines(p, path, page));
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
                  save(p, path, expected, next, () -> lines(p, path, page));
                }));
      if (index + 1 < expected.size())
        buttons.add(
            ui.button(
                "down",
                "tab-editor.down",
                v -> {
                  var next = new ArrayList<>(expected);
                  Collections.swap(next, index, index + 1);
                  save(p, path, expected, next, () -> lines(p, path, page));
                }));
      buttons.add(
          ui.button(
              "delete",
              "gui.remove",
              v -> {
                var next = new ArrayList<>(expected);
                next.remove(index);
                save(p, path, expected, next, () -> lines(p, path, page));
              }));
    }
    buttons.add(ui.button("back", "common.back", v -> lines(p, path, page)));
    ui.show(
        p,
        "tab-editor.line-title",
        path.equals("sorting") ? "tab-editor.sorting-help" : "tab-editor.lines-help",
        List.of(
            ui.text(
                "text",
                path.equals("sorting") ? "tab-editor.sort-rule" : "tab-editor.text",
                index < 0 ? "" : expected.get(index),
                2048)),
        buttons,
        this::admin);
  }

  public void groups(Player p, int page) {
    entries(p, "groups", page, false);
  }

  public void aliases(Player p, int page) {
    entries(p, "placeholders", page, true);
  }

  private void entries(Player p, String path, int page, boolean aliases) {
    require(p);
    var values = map(path);
    var ids = values.keySet().stream().sorted().toList();
    int actual = Math.clamp(page, 0, Math.max(0, (ids.size() - 1) / 12));
    var buttons = new ArrayList<NativeDialogs.Button>();
    for (int i = actual * 12; i < Math.min(ids.size(), (actual + 1) * 12); i++) {
      String id = ids.get(i);
      buttons.add(
          new NativeDialogs.Button(
              "entry" + i,
              lang.render("gui.label", Map.of("name", id)),
              v -> {
                if (aliases) alias(p, id, values);
                else group(p, id, values);
              }));
    }
    buttons.add(
        ui.button(
            "add",
            aliases ? "tab-editor.add-placeholder" : "tab-editor.add-group",
            v -> {
              if (aliases) alias(p, null, values);
              else group(p, null, values);
            }));
    if (actual > 0)
      buttons.add(ui.button("prev", "gui.previous", v -> entries(p, path, actual - 1, aliases)));
    if ((actual + 1) * 12 < ids.size())
      buttons.add(ui.button("next", "common.next", v -> entries(p, path, actual + 1, aliases)));
    buttons.add(ui.button("back", "common.back", v -> open(p)));
    ui.show(
        p,
        aliases ? "tab-editor.placeholders" : "tab-editor.groups",
        "tab-editor.groups-help",
        List.of(),
        buttons,
        this::admin);
  }

  private static List<String> csv(String value) {
    return value.isBlank()
        ? List.of()
        : Arrays.stream(value.split(","))
            .map(String::strip)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
  }

  private String id(Map<String, String> values, String old, Map<String, Object> expected) {
    String id = values.get("id").strip();
    if (!id.matches("[a-zA-Z0-9_-]{1,48}") || !id.equals(old) && expected.containsKey(id))
      throw new IllegalArgumentException("errors.input");
    return id;
  }

  @SuppressWarnings("unchecked")
  private void group(Player p, String old, Map<String, Object> expected) {
    var value = old == null ? Map.<String, Object>of() : (Map<String, Object>) expected.get(old);
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              String id = id(v, old, expected);
              int priority = Integer.parseInt(v.get("priority"));
              if (priority < 0 || priority > 100000)
                throw new IllegalArgumentException("errors.input");
              var next = new LinkedHashMap<>(expected);
              if (old != null) next.remove(old);
              next.put(
                  id,
                  Map.of(
                      "permission",
                      v.get("permission").strip(),
                      "priority",
                      priority,
                      "name",
                      v.get("name"),
                      "prefix",
                      v.get("prefix"),
                      "suffix",
                      v.get("suffix"),
                      "players",
                      csv(v.get("players")),
                      "worlds",
                      csv(v.get("worlds"))));
              save(p, "groups", expected, next, () -> groups(p, 0));
            }));
    if (old != null)
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
                        save(p, "groups", expected, next, () -> groups(p, 0));
                      },
                      () -> group(p, old, expected),
                      this::admin)));
    buttons.add(ui.button("back", "common.back", v -> groups(p, 0)));
    ui.show(
        p,
        "tab-editor.group-title",
        "tab-editor.groups-help",
        List.of(
            ui.text("id", "tab-editor.id", old == null ? "" : old, 48),
            ui.text(
                "permission",
                "tab-editor.permission",
                Objects.toString(value.getOrDefault("permission", "")),
                128),
            ui.text(
                "priority",
                "tab-editor.priority",
                Objects.toString(value.getOrDefault("priority", 100)),
                6),
            ui.text(
                "name",
                "tab-editor.name",
                Objects.toString(value.getOrDefault("name", "<player>")),
                1024),
            ui.text(
                "prefix",
                "tab-editor.prefix",
                Objects.toString(value.getOrDefault("prefix", "")),
                1024),
            ui.text(
                "suffix",
                "tab-editor.suffix",
                Objects.toString(value.getOrDefault("suffix", "")),
                1024),
            ui.text(
                "players",
                "tab-editor.players",
                String.join(",", (List<String>) value.getOrDefault("players", List.of())),
                2048),
            ui.text(
                "worlds",
                "tab-editor.worlds",
                String.join(",", (List<String>) value.getOrDefault("worlds", List.of())),
                1024)),
        buttons,
        this::admin);
  }

  private void alias(Player p, String old, Map<String, Object> expected) {
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              String id = id(v, old, expected);
              var next = new LinkedHashMap<>(expected);
              if (old != null) next.remove(old);
              next.put(id, v.get("text"));
              save(
                  p,
                  "placeholders",
                  expected.isEmpty() && !plugin.getConfig().contains("tab.placeholders")
                      ? null
                      : expected,
                  next,
                  () -> aliases(p, 0));
            }));
    if (old != null)
      buttons.add(
          ui.button(
              "delete",
              "gui.remove",
              v -> {
                var next = new LinkedHashMap<>(expected);
                next.remove(old);
                save(p, "placeholders", expected, next, () -> aliases(p, 0));
              }));
    buttons.add(ui.button("back", "common.back", v -> aliases(p, 0)));
    ui.show(
        p,
        "tab-editor.placeholder-title",
        "tab-editor.placeholder-help",
        List.of(
            ui.text("id", "tab-editor.id", old == null ? "" : old, 48),
            ui.text(
                "text",
                "tab-editor.text",
                old == null ? "" : Objects.toString(expected.get(old)),
                2048)),
        buttons,
        this::admin);
  }

  private void nametag(Player p) {
    var expected = map("nametag");
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
                "visible",
                lang.render("tab-editor.visible"),
                lang.text(
                    Boolean.TRUE.equals(expected.getOrDefault("visible", true))
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
                List.of(lang.text("players.yes"), lang.text("players.no")))),
        List.of(
            ui.button(
                "save",
                "content.save",
                v -> {
                  var next = new LinkedHashMap<>(expected);
                  for (String key : List.of("enabled", "visible", "collision")) {
                    String flag = v.get(key);
                    if (!List.of(lang.text("players.yes"), lang.text("players.no")).contains(flag))
                      throw new IllegalArgumentException("errors.input");
                    next.put(key, flag.equals(lang.text("players.yes")));
                  }
                  save(p, "nametag", expected, next, () -> open(p));
                }),
            ui.button("back", "common.back", v -> open(p))),
        this::admin);
  }

  @EventHandler
  public void click(InventoryClickEvent event) {
    if (!(event.getInventory().getHolder() instanceof Menu menu)) return;
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player p)
        || !event.getClick().isLeftClick()
        || event.getRawSlot() < 0
        || event.getRawSlot() >= menu.inventory.getSize()) return;
    var action = menu.actions.get(event.getRawSlot());
    if (action != null)
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (admin(p) && p.getOpenInventory().getTopInventory() == menu.inventory)
                  try {
                    MenuStyle.action(p, action);
                  } catch (RuntimeException error) {
                    ui.error(p, error);
                  }
              });
  }

  @EventHandler
  public void drag(InventoryDragEvent event) {
    if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
  }

  public void close() {
    HandlerList.unregisterAll(this);
  }
}
