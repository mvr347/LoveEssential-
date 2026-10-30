package dev.lovelace.loveessential.gui;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.config.ModuleConfigs;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.npc.NpcService;
import dev.lovelace.loveessential.wizard.NpcWizard;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;

/** Native inventory controls; all mutations still pass through permission and service checks. */
public final class ControlMenus implements Listener, AutoCloseable {
  private dev.lovelace.loveessential.dialog.NativeDialogs nativeUi;

  public void nativeDialogs(dev.lovelace.loveessential.dialog.NativeDialogs ui) {
    nativeUi = ui;
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final Lang lang;
  private final LoveBridge love;
  private final NpcService npcs;
  private final NpcWizard wizard;
  private final Gameplay gameplay;
  private final dev.lovelace.loveessential.module.Nicknames nicknames;
  private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
  private final Map<UUID, String> roots = new HashMap<>();
  private final Set<UUID> direct = new HashSet<>(), dispatching = new HashSet<>();

  public void cancelInput(Player p) {
    prompts.remove(p.getUniqueId());
  }

  public void direct(Player p, Runnable open) {
    if (!dispatching.contains(p.getUniqueId())) direct.add(p.getUniqueId());
    try {
      open.run();
    } finally {
      direct.remove(p.getUniqueId());
    }
  }

  private record Prompt(
      long expires,
      Predicate<Player> allowed,
      Consumer<String> input,
      Runnable back,
      String token,
      Component context,
      String instructionKey,
      String example,
      String current) {}

  private static final class Menu implements dev.lovelace.loveessential.gui.GuiLayout {
    Inventory inventory;
    String menuId = "gui.menu";

    public String guiId() {
      return menuId;
    }

    public Map<Integer, ?> guiActions() {
      return actions;
    }

    public void guiPut(int slot, Runnable action) {
      actions.put(slot, click -> action.run());
    }

    final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
    final Predicate<Player> allowed;
    boolean root;
    Map<String, String> variables = Map.of();
    Runnable reopen;
    Object route;

    public Object guiRoute() {
      return route == null ? dev.lovelace.loveessential.gui.GuiLayout.super.guiRoute() : route;
    }

    public Map<String, String> guiVariables() {
      return variables;
    }

    public Runnable guiReopen() {
      return reopen;
    }

    public boolean guiAllowed(Player player) {
      return allowed.test(player);
    }

    Menu(Predicate<Player> allowed) {
      this.allowed = allowed;
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }
  }

  public ControlMenus(
      LoveEssentialPlugin plugin,
      MainThread main,
      Lang lang,
      LoveBridge love,
      NpcService npcs,
      NpcWizard wizard,
      Gameplay gameplay,
      dev.lovelace.loveessential.module.Nicknames nicknames) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.love = love;
    this.npcs = npcs;
    this.wizard = wizard;
    this.gameplay = gameplay;
    this.nicknames = nicknames;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  private boolean admin(Player p) {
    return p.hasPermission("loveessential.admin");
  }

  private boolean npcView(Player p) {
    return plugin.module("npc")
        && (p.hasPermission("loveessential.npc.view")
            || p.hasPermission("loveessential.npc.create"));
  }

  private Menu menu(Player p, String title, Map<String, String> vars, Predicate<Player> allowed) {
    if (!plugin.ready() || !love.authenticated(p) || !allowed.test(p))
      throw new IllegalArgumentException("errors.permission");
    prompts.remove(p.getUniqueId());
    wizard.cancelInput(p);
    var m = new Menu(allowed);
    String route = title + new TreeMap<>(vars);
    if (direct.remove(p.getUniqueId())) roots.put(p.getUniqueId(), route);
    m.root = route.equals(roots.get(p.getUniqueId()));
    m.menuId = title;
    m.variables = vars;
    m.inventory = Bukkit.createInventory(m, 54, MenuStyle.title(lang, title, vars));
    MenuStyle.frame(m.inventory);
    m.inventory.setItem(
        0,
        MenuStyle.head(
            "d0d5a4eba140cbb34d45e40d62dd3f7e6584b2ab0b4155a0bcc603d5d750f793",
            lang.render(title, vars),
            List.of()));
    return m;
  }

  private void put(
      Menu m, int slot, Material icon, String title, String action, Consumer<ClickType> click) {
    m.inventory.setItem(slot, MenuStyle.button(lang, icon, title, "gui.separator", action));
    m.actions.put(slot, click);
  }

  private void entry(
      Menu m,
      int slot,
      Material icon,
      Component title,
      List<Component> lore,
      Consumer<ClickType> click) {
    m.inventory.setItem(slot, MenuStyle.item(icon, title, lore));
    m.actions.put(slot, click);
  }

  /** Tile whose lore comes from a language list: subtitle, sections, separator and click line. */
  private void tile(
      Menu m,
      int slot,
      Material icon,
      String nameKey,
      String loreKey,
      Map<String, String> vars,
      Consumer<ClickType> click) {
    tile(m, slot, icon, nameKey, loreKey, vars, Map.of(), click);
  }

  private void tile(
      Menu m,
      int slot,
      Material icon,
      String nameKey,
      String loreKey,
      Map<String, String> vars,
      Map<String, Component> parts,
      Consumer<ClickType> click) {
    entry(
        m,
        slot,
        icon,
        lang.renderText(lang.text(nameKey), vars, parts),
        lang.lines(loreKey).stream().map(text -> lang.renderText(text, vars, parts)).toList(),
        click);
  }

  /** Components for {@code gui.toggle-lore}: the state now and the state after the click. */
  private Map<String, Component> toggleParts(boolean now, Map<String, Component> extra) {
    var parts = new HashMap<>(extra);
    parts.put("now", lang.render(now ? "gui.state-on" : "gui.state-off"));
    parts.put("next", lang.render(now ? "gui.state-off" : "gui.state-on"));
    return parts;
  }

  private void navigation(Menu m, Player p, int page, int total, IntConsumer pages, Runnable back) {
    navigation(m, p, page, total, MenuStyle.CONTENT.length, pages, back);
  }

  private void navigation(
      Menu m, Player p, int page, int total, int capacity, IntConsumer pages, Runnable back) {
    if (!m.root) put(m, 51, Material.ARROW, "common.back", "gui.click.open", c -> back.run());
    if (page > 0)
      put(m, 36, Material.ARROW, "gui.previous", "gui.click.open", c -> pages.accept(page - 1));
    if ((page + 1) * capacity < total)
      put(m, 44, Material.ARROW, "common.next", "gui.click.open", c -> pages.accept(page + 1));
    var badge = m.inventory.getItem(0);
    var badgeMeta = badge.getItemMeta();
    badgeMeta.lore(
        List.of(
            lang.render(
                    "gui.page",
                    Map.of(
                        "page",
                        "" + (page + 1),
                        "pages",
                        "" + Math.max(1, (total + capacity - 1) / capacity)))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
    badge.setItemMeta(badgeMeta);
    put(m, 52, Material.BARRIER, "gui.close", "gui.click.close", c -> p.closeInventory());
  }

  private void command(Player p, String command) {
    p.closeInventory();
    dispatching.add(p.getUniqueId());
    try {
      Bukkit.dispatchCommand(
          p,
          "loveessential:loveessential "
              + (command.startsWith("le ") ? command.substring(3) : command));
    } finally {
      dispatching.remove(p.getUniqueId());
    }
  }

  public void home(Player p) {
    modules(p);
  }

  private dev.lovelace.loveessential.module.DisplayPreferences displayPrefs;

  public void display(dev.lovelace.loveessential.module.DisplayPreferences prefs) {
    this.displayPrefs = prefs;
  }

  /**
   * Six owner choices: clan and suffix in chat, TAB and nametags, for every viewer.
   */
  public void displayMenu(Player p) {
    var m = menu(p, "display.gui-title", Map.of(), player -> plugin.module("display"));
    m.root = true;
    var id = p.getUniqueId();
    var scope = dev.lovelace.lovecore.api.display.DisplayPrefs.Scope.OWN;
    tile(
        m,
        50,
        Material.LIME_DYE,
        "display.all-on",
        "display.all-on-lore",
        Map.of(),
        Map.of(),
        c -> {
          displayPrefs.setAll(id, true);
          displayMenu(p);
        });
    var elements = dev.lovelace.lovecore.api.display.DisplayPrefs.Element.values();
    var surfaces = Arrays.asList(dev.lovelace.lovecore.api.display.DisplayPrefs.Surface.values());
    int index = 0;
    for (var element : elements)
      for (var surface : surfaces) {
        int slot = MenuStyle.slot(index++, elements.length * surfaces.size());
        var vars =
            Map.of(
                "element", lang.text("display.element-" + element.name().toLowerCase(Locale.ROOT)),
                "surface", lang.text("display.surface-" + surface.name().toLowerCase(Locale.ROOT)),
                // Texts saved by the previous version still read <scope>; keep it filled.
                "scope", lang.text("display.scope-own"));
        boolean shown = displayPrefs.shown(id, surface, element, scope);
        tile(
            m,
            slot,
            shown ? Material.LIME_DYE : Material.RED_DYE,
            shown ? "display.tile-on" : "display.tile-off",
            "display.tile-lore",
            vars,
            toggleParts(shown, Map.of()),
            c -> {
              displayPrefs.set(id, surface, element, scope, !shown);
              displayMenu(p);
            });
      }
    put(m, 52, Material.BARRIER, "gui.close", "gui.click.close", c -> p.closeInventory());
    MenuStyle.open(p, m.inventory);
  }

  private dev.lovelace.loveessential.module.Secrets secrets;

  public void secrets(dev.lovelace.loveessential.module.Secrets book) {
    this.secrets = book;
  }

  /** The book of secrets: found ones with their description, the rest as a hint. */
  public void secretsMenu(Player p, int page) {
    var m = menu(p, "secrets.gui-title", Map.of(), player -> plugin.module("secrets"));
    var catalog = secrets.catalog();
    var foundAt = secrets.foundAt(p.getUniqueId());
    int capacity = MenuStyle.CONTENT.length;
    int pages = Math.max(1, (catalog.size() + capacity - 1) / capacity);
    int shown = Math.max(0, Math.min(page, pages - 1));
    int from = shown * capacity;
    int to = Math.min(catalog.size(), from + capacity);
    var format =
        java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(java.time.ZoneId.systemDefault());
    int found = 0;
    for (var entry : catalog) if (foundAt.containsKey(entry.id())) found++;
    for (int i = from; i < to; i++) {
      var entry = catalog.get(i);
      int slot = MenuStyle.slot(i - from, to - from);
      Long when = foundAt.get(entry.id());
      var vars = new HashMap<String, String>();
      vars.put("name", entry.name());
      vars.put("hint", entry.hint());
      if (when != null) {
        vars.put("date", format.format(java.time.Instant.ofEpochMilli(when)));
        entry(
            m,
            slot,
            entry.icon(),
            lang.renderText(lang.text("secrets.tile-found-name"), vars),
            secretLore(lang.lines("secrets.tile-found-lore"), vars, entry.description()),
            c -> {});
      } else {
        entry(
            m,
            slot,
            Material.BOOK,
            lang.render("secrets.unknown-name"),
            secretLore(lang.lines("secrets.tile-unknown-lore"), vars, entry.hint()),
            c -> {});
      }
    }
    int filled = dev.lovelace.loveessential.module.SecretRules.filled(found, catalog.size());
    var bar =
        Component.text("█".repeat(filled), net.kyori.adventure.text.format.NamedTextColor.GREEN)
            .append(
                Component.text(
                    "░".repeat(dev.lovelace.loveessential.module.SecretRules.BAR - filled),
                    net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
    tile(
        m,
        50,
        Material.ENCHANTED_BOOK,
        "secrets.progress-name",
        "secrets.progress-lore",
        Map.of("found", "" + found, "total", "" + catalog.size()),
        Map.of("bar", bar),
        c -> {});
    navigation(m, p, shown, catalog.size(), capacity, target -> secretsMenu(p, target), () -> {});
    MenuStyle.open(p, m.inventory);
  }

  /** Lore from a language list; the line holding {@code <description>} is repeated per wrapped row. */
  private List<Component> secretLore(List<String> template, Map<String, String> vars, String text) {
    var lore = new ArrayList<Component>();
    for (String line : template) {
      if (!line.contains("<description>") && !line.contains("<hint>")) {
        lore.add(lang.renderText(line, vars));
        continue;
      }
      for (String row : dev.lovelace.loveessential.module.SecretRules.wrap(text, 38))
        lore.add(
            lang.renderText(
                line.replace("<description>", row).replace("<hint>", row), vars));
    }
    return lore;
  }

  private static final String[] VANISH_FILTERS = {"all", "world", "near"};
  private final Map<UUID, Integer> vanishFilter = new HashMap<>();

  private boolean vanishAllowed(Player p) {
    return plugin.module("vanish")
        && p.hasPermission("loveessential.vanish")
        && p.hasPermission("loveessential.vanish.gui");
  }

  /** Online players the viewer may jump to under the chosen filter, sorted by name. */
  private List<Player> vanishTargets(Player p, int filter) {
    double radius = plugin.getConfig().getDouble("vanish.gui.near-radius", 64);
    return Bukkit.getOnlinePlayers().stream()
        .filter(t -> !t.equals(p) && p.canSee(t))
        .filter(
            t ->
                switch (filter) {
                  case 1 -> t.getWorld().equals(p.getWorld());
                  case 2 ->
                      t.getWorld().equals(p.getWorld())
                          && t.getLocation().distanceSquared(p.getLocation()) <= radius * radius;
                  default -> true;
                })
        .sorted(Comparator.comparing(t -> t.getName().toLowerCase(Locale.ROOT)))
        .map(t -> (Player) t)
        .toList();
  }

  /** Staff menu: every visible player as a head; a click moves the viewer there, hidden. */
  public void vanishMenu(Player p, int page) {
    var m = menu(p, "vanish.gui-title", Map.of(), this::vanishAllowed);
    m.root = true;
    int filter = vanishFilter.getOrDefault(p.getUniqueId(), 0);
    boolean hidden = gameplay.vanished(p);
    boolean flight = gameplay.vanishEffects().flight(p);
    tile(
        m,
        3,
        hidden ? Material.ENDER_EYE : Material.ENDER_PEARL,
        hidden ? "vanish.toggle-on" : "vanish.toggle-off",
        "gui.toggle-lore",
        Map.of(),
        toggleParts(hidden, Map.of()),
        c -> gameplay.setVanish(p, !hidden).thenRun(() -> vanishMenu(p, page)));
    tile(
        m,
        4,
        Material.COMPASS,
        "vanish.filter",
        "vanish.filter-lore",
        Map.of("filter", lang.text("vanish.filter-" + VANISH_FILTERS[filter])),
        Map.of(),
        c -> {
          vanishFilter.put(p.getUniqueId(), (filter + 1) % VANISH_FILTERS.length);
          vanishMenu(p, 0);
        });
    tile(
        m,
        5,
        Material.FEATHER,
        flight ? "vanish.fly-on" : "vanish.fly-off",
        "gui.toggle-lore",
        Map.of(),
        toggleParts(flight, Map.of()),
        c -> {
          gameplay.vanishEffects().flight(p, !flight);
          vanishMenu(p, page);
        });
    var targets = vanishTargets(p, filter);
    int from = page * MenuStyle.CONTENT.length;
    var shown = targets.subList(Math.min(from, targets.size()), Math.min(targets.size(), from + 21));
    if (shown.isEmpty())
      m.inventory.setItem(22, MenuStyle.button(lang, Material.BARRIER, "vanish.empty", "vanish.empty-help"));
    for (int i = 0; i < shown.size(); i++) vanishHead(m, p, shown.get(i), MenuStyle.slot(i, shown.size()), page);
    navigation(m, p, page, targets.size(), n -> vanishMenu(p, n), () -> {});
    MenuStyle.open(p, m.inventory);
  }

  private void vanishHead(Menu m, Player viewer, Player target, int slot, int page) {
    var loc = target.getLocation();
    var vars =
        Map.of(
            "player", target.getName(),
            "world", loc.getWorld().getName(),
            "x", Integer.toString(loc.getBlockX()),
            "y", Integer.toString(loc.getBlockY()),
            "z", Integer.toString(loc.getBlockZ()),
            "mode", lang.text("vanish.mode-" + target.getGameMode().name().toLowerCase(Locale.ROOT)),
            "afk", lang.text(gameplay.afk(target) ? "vanish.afk-yes" : "vanish.afk-no"));
    var item =
        MenuStyle.item(
            Material.PLAYER_HEAD,
            lang.renderText(lang.text("vanish.player"), vars, Map.of()),
            lang.lines("vanish.player-lore").stream()
                .map(text -> lang.renderText(text, vars, Map.of()))
                .toList());
    var meta = (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();
    meta.setPlayerProfile(target.getPlayerProfile());
    item.setItemMeta(meta);
    m.inventory.setItem(slot, item);
    m.actions.put(
        slot,
        click -> {
          if (!target.isOnline()) {
            vanishMenu(viewer, page);
            return;
          }
          switch (click) {
            case LEFT -> jumpTo(viewer, target);
            case RIGHT -> command(viewer, "profile " + target.getName());
            case SHIFT_LEFT -> {
              if (viewer.hasPermission("loveessential.invsee"))
                command(viewer, "invsee " + target.getName());
              else throw new IllegalArgumentException("errors.permission");
            }
            default -> {}
          }
        });
  }

  private java.util.function.BiConsumer<Player, Location> jumper =
      (who, to) -> who.teleportAsync(to);

  /** Replaces the teleport used by the player menu (the mock server cannot teleport async). */
  public void jumper(java.util.function.BiConsumer<Player, Location> jumper) {
    this.jumper = Objects.requireNonNull(jumper);
  }

  /** Staff tool: instant jump, no warmup and no combat check, hiding the viewer first if asked. */
  private void jumpTo(Player viewer, Player target) {
    viewer.closeInventory();
    if (gameplay.vanished(viewer)
        || !plugin.getConfig().getBoolean("vanish.gui.auto-vanish", true)) {
      jumper.accept(viewer, target.getLocation());
      return;
    }
    // Hide first, so nobody sees the arrival; a failed save keeps the viewer where they are.
    gameplay
        .setVanish(viewer, true)
        .thenRun(() -> {
          if (viewer.isOnline() && target.isOnline()) jumper.accept(viewer, target.getLocation());
        });
  }

  private void locked(Menu m, int slot) {
    var item = m.inventory.getItem(slot);
    var meta = item.getItemMeta();
    meta.displayName(
        meta.displayName()
            .append(lang.render("gui.locked-suffix"))
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
    meta.lore(
        List.of(
                lang.render("gui.separator"),
                lang.render("gui.locked"),
                lang.render("gui.permission"),
                lang.render("gui.click.blocked"))
            .stream()
            .map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
            .toList());
    item.setItemMeta(meta);
    m.actions.put(
        slot,
        c -> {
          throw new IllegalArgumentException("errors.permission");
        });
  }

  public void modules(Player p) {
    modules(p, 0);
  }

  private void modules(Player p, int page) {
    var m = menu(p, "module-menu.title", Map.of(), this::admin);
    m.root = true;
    var names = LoveEssentialPlugin.MODULES.stream().sorted().toList();
    int i = 0;
    for (String id : names.subList(page * 21, Math.min(names.size(), (page + 1) * 21))) {
      boolean enabled = plugin.module(id);
      entry(
          m,
          MenuStyle.slot(i++, Math.min(21, names.size() - page * 21)),
          switch (id) {
            case "npc", "profile" -> Material.PLAYER_HEAD;
            case "tab", "nickname" -> Material.NAME_TAG;
            case "home" -> Material.RED_BED;
            case "spawn" -> Material.RESPAWN_ANCHOR;
            case "portal" -> Material.ENDER_PEARL;
            case "messages" -> Material.WRITABLE_BOOK;
            case "inventory" -> Material.CHEST;
            case "afk" -> Material.CLOCK;
            case "vanish" -> Material.ENDER_EYE;
            case "skin" -> Material.LEATHER_HELMET;
            case "sitride" -> Material.SADDLE;
            case "nightskip" -> Material.LANTERN;
            case "hologram", "motd", "joinquit" -> Material.OAK_SIGN;
            case "traces" -> Material.FEATHER;
            case "calamities" -> Material.WITHER_ROSE;
            case "secrets" -> Material.ENCHANTED_BOOK;
            default -> Material.COMPARATOR;
          },
          lang.render("gui.module-names." + id),
          java.util.stream.Stream.concat(
                  lang.lines("module-menu.descriptions." + id).stream()
                      .map(
                          line ->
                              net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                  .deserialize(line)),
                  java.util.stream.Stream.of(
                      lang.render(enabled ? "gui.enabled" : "gui.disabled"),
                      lang.render("gui.file", Map.of("path", "modules/" + id + "/" + id + ".yml")),
                      lang.render("gui.separator"),
                      lang.render("gui.click.settings"),
                      lang.render("gui.click.toggle")))
              .toList(),
          c -> {
            if (c.isRightClick())
              complete(p, plugin.module(id, !plugin.module(id)), () -> modules(p, page), true);
            else settings(p, id, "", 0);
          });
    }
    navigation(m, p, page, names.size(), n -> modules(p, n), () -> home(p));
    MenuStyle.open(p, m.inventory);
  }

  private java.util.function.Consumer<Player> scoreboardEditor = p -> {};

  public void scoreboardEditor(java.util.function.Consumer<Player> opener) {
    scoreboardEditor = opener;
  }

  public void settings(Player p, String id, String path, int page) {
    if (!LoveEssentialPlugin.MODULES.contains(id))
      throw new IllegalArgumentException("errors.module");
    var snapshot = ModuleConfigs.view(plugin.getConfig(), id);
    var section = path.isEmpty() ? snapshot : snapshot.getConfigurationSection(path);
    if (section == null) throw new IllegalArgumentException("errors.not-found");
    var keys =
        section.getKeys(false).stream()
            .filter(
                k ->
                    !id.equals("tab")
                        || !path.isEmpty()
                        || !Set.of("engine", "external").contains(k))
            .sorted()
            .toList();
    var m =
        menu(
            p,
            "gui.settings-title",
            Map.of("module", lang.text("gui.module-labels." + id), "path", settingName(path)),
            this::admin);
    m.route = List.of("settings", id, path);
    if (id.equals("scoreboard"))
      entry(
          m,
          8,
          Material.WRITABLE_BOOK,
          lang.render("sidebar.admin-menu"),
          List.of(lang.render("sidebar.edit-help")),
          click -> scoreboardEditor.accept(p));
    for (int i = page * 21; i < Math.min(keys.size(), (page + 1) * 21); i++) {
      String key = keys.get(i), full = path.isEmpty() ? key : path + "." + key;
      Object value = section.get(key);
      boolean folder = section.isConfigurationSection(key);
      String labelKey = "gui.settings." + id + "." + full + "._label";
      if (full.equals("access") || full.startsWith("access."))
        labelKey = "access-editor." + full.replace("access.", "") + ".title";
      var title =
          lang.contains(labelKey)
              ? lang.render(labelKey)
              : lang.renderText("<gold>" + settingName(key), Map.of());
      var lore = new ArrayList<Component>();
      String helpKey = "module-help." + id + "." + full + "._description";
      if (full.equals("access") || full.startsWith("access."))
        helpKey = "access-editor." + full.replace("access.", "") + ".help";
      if (lang.contains(helpKey)) lore.addAll(MenuStyle.description(lang, helpKey));
      else
        lore.add(
            lang.render(
                folder
                    ? "settings-help.section"
                    : value instanceof Boolean
                        ? "settings-help.boolean"
                        : value instanceof Number
                            ? "settings-help.number"
                            : value instanceof List<?>
                                ? "settings-help.list"
                                : "settings-help.text"));

      if (!folder)
        lore.add(
            lang.render(
                "gui.value",
                Map.of(
                    "value",
                    (value instanceof List<?> values
                        ? lang.text("gui.list-count") + " " + values.size()
                        : value instanceof Boolean flag
                            ? lang.text(flag ? "players.yes" : "players.no")
                            : value == null || String.valueOf(value).isEmpty()
                                ? lang.text("gui.value-empty")
                                : String.valueOf(value)))));
      lore.add(lang.render("gui.separator"));
      lore.add(
          lang.render(
              folder || value instanceof List<?>
                  ? "gui.click.open"
                  : value instanceof Boolean ? "gui.click.switch" : "gui.click.edit"));
      if (value instanceof String text && full.endsWith("-key") && lang.contains(text))
        lore.add(lang.render("gui.click.text"));
      entry(
          m,
          MenuStyle.slot(i % 21, Math.min(21, keys.size() - page * 21)),
          folder
              ? Material.CHEST
              : value instanceof Boolean b
                  ? (b ? Material.LIME_DYE : Material.GRAY_DYE)
                  : value instanceof List<?> ? Material.WRITABLE_BOOK : Material.COMPARATOR,
          title,
          lore,
          c -> {
            if (folder) settings(p, id, full, 0);
            else if (value instanceof Boolean b)
              saveSetting(p, id, full, value, !b, () -> settings(p, id, path, page));
            else if (value instanceof List<?> list)
              list(p, id, full, list, 0, () -> settings(p, id, path, page));
            else if (!settingChoices(id, full).isEmpty())
              settingPicker(p, id, full, value, 0, () -> settings(p, id, path, page));
            else if (c.isRightClick()
                && value instanceof String text
                && full.endsWith("-key")
                && lang.contains(text))
              ask(
                  p,
                  "gui.prompt-text",
                  this::admin,
                  input ->
                      complete(
                          p,
                          plugin.asyncRun(
                              () -> {
                                try {
                                  lang.putAll(Map.of(text, input));
                                } catch (Exception e) {
                                  throw new CompletionException(e);
                                }
                              }),
                          () -> settings(p, id, path, page),
                          true),
                  () -> settings(p, id, path, page),
                  lang.render("gui.path", Map.of("path", text)),
                  lang.text(text),
                  lang.text(text));
            else if (value instanceof Number)
              number(p, id, full, value, () -> settings(p, id, path, page));
            else
              ask(
                  p,
                  "gui.prompt-value",
                  this::admin,
                  input ->
                      saveSetting(
                          p,
                          id,
                          full,
                          value,
                          parse(value, input),
                          () -> settings(p, id, path, page)),
                  () -> settings(p, id, path, page),
                  title,
                  text(value),
                  text(value));
          });
    }
    if (keys.isEmpty())
      m.inventory.setItem(22, MenuStyle.button(lang, Material.BOOK, "gui.empty-settings"));
    navigation(
        m,
        p,
        page,
        keys.size(),
        n -> settings(p, id, path, n),
        () -> {
          if (path.isEmpty()) modules(p);
          else
            settings(p, id, path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : "", 0);
        });
    tile(
        m,
        4,
        plugin.module(id) ? Material.LIME_DYE : Material.GRAY_DYE,
        plugin.module(id) ? "gui.module-toggle-on" : "gui.module-toggle-off",
        "gui.toggle-lore",
        Map.of(),
        toggleParts(plugin.module(id), Map.of("module", lang.render("gui.module-names." + id))),
        c ->
            complete(
                p, plugin.module(id, !plugin.module(id)), () -> settings(p, id, path, page), true));
    MenuStyle.open(p, m.inventory);
  }

  private List<String> settingChoices(String module, String path) {
    if (module.equals("tab") && path.startsWith("bossbars.")) {
      if (path.endsWith(".color"))
        return Arrays.stream(net.kyori.adventure.bossbar.BossBar.Color.values())
            .map(Enum::name)
            .toList();
      if (path.endsWith(".overlay"))
        return Arrays.stream(net.kyori.adventure.bossbar.BossBar.Overlay.values())
            .map(Enum::name)
            .toList();
    }
    if (module.equals("profile") && path.startsWith("cards.") && path.endsWith(".material"))
      return Arrays.stream(Material.values())
          .filter(m -> m.isItem() && !m.isAir())
          .map(Enum::name)
          .sorted()
          .toList();
    return List.of();
  }

  private void settingPicker(
      Player p, String module, String path, Object expected, int page, Runnable back) {
    var menu =
        menu(p, "settings-help.choice-title", Map.of("path", settingName(path)), this::admin);
    var choices = settingChoices(module, path);
    int count = Math.max(0, Math.min(21, choices.size() - page * 21));
    for (int i = 0; i < count; i++) {
      String value = choices.get(page * 21 + i);
      Material icon = module.equals("profile") ? Material.matchMaterial(value) : Material.PAPER;
      Component title =
          lang.contains("settings-help.choices." + value)
              ? lang.render("settings-help.choices." + value)
              : module.equals("profile")
                  ? Component.translatable(icon.translationKey())
                  : lang.render("gui.label", Map.of("name", value));
      entry(
          menu,
          MenuStyle.slot(i, count),
          icon,
          title,
          List.of(lang.render("gui.click.select")),
          c -> saveSetting(p, module, path, expected, value, back));
    }
    navigation(
        menu,
        p,
        page,
        choices.size(),
        n -> settingPicker(p, module, path, expected, n, back),
        back);
    MenuStyle.open(p, menu.inventory);
  }

  private String settingName(String key) {
    var words = new ArrayList<String>();
    for (String word : key.split("[._-]+")) {
      String lookup = "settings-labels.words." + word.toLowerCase(Locale.ROOT);
      if (lang.contains(lookup)) words.add(lang.text(lookup));
    }
    if (words.isEmpty()) return lang.text("settings-labels.unknown");
    String result = String.join(" · ", words);
    return Character.toUpperCase(result.charAt(0)) + result.substring(1);
  }

  private static Object parse(Object previous, String input) {
    if (previous instanceof Map<?, ?> || previous instanceof List<?>)
      throw new IllegalArgumentException("errors.complex-setting");
    if (previous instanceof Integer) return Integer.parseInt(input);
    if (previous instanceof Long) return Long.parseLong(input);
    if (previous instanceof Number) {
      double n = Double.parseDouble(input);
      if (!Double.isFinite(n)) throw new IllegalArgumentException("errors.input");
      return n;
    }
    return input.equals("-") ? "" : input;
  }

  private List<String> accessChoices(String path) {
    if (!path.startsWith("access.")) return List.of();
    if (path.endsWith("states"))
      return dev.lovelace.loveessential.module.ModulePolicy.STATES.stream().sorted().toList();
    if (path.endsWith("gamemodes"))
      return Arrays.stream(org.bukkit.GameMode.values())
          .map(mode -> mode.name().toLowerCase(Locale.ROOT))
          .toList();
    return List.of();
  }

  private Component accessLabel(String path, String value) {
    if (path.startsWith("access.") && (path.endsWith("states") || path.endsWith("gamemodes")))
      return lang.render(
          "module-access."
              + (path.endsWith("states") ? "states." : "gamemodes.")
              + value.toLowerCase(Locale.ROOT));
    return lang.render("gui.label", Map.of("name", value));
  }

  private void accessPicker(
      Player p, String module, String path, List<?> values, int index, Runnable back) {
    var menu = menu(p, "access-editor.choice-title", Map.of(), this::admin);
    var choices = accessChoices(path);
    for (int i = 0; i < choices.size(); i++) {
      String choice = choices.get(i);
      entry(
          menu,
          MenuStyle.slot(i, choices.size()),
          Material.PAPER,
          accessLabel(path, choice),
          List.of(lang.render("gui.separator"), lang.render("gui.click.select")),
          click -> {
            var next = new ArrayList<Object>(values);
            if (index < 0) {
              if (!next.contains(choice)) next.add(choice);
            } else next.set(index, choice);
            saveSetting(p, module, path, values, next, back);
          });
    }
    navigation(menu, p, 0, choices.size(), ignored -> {}, back);
    MenuStyle.open(p, menu.inventory);
  }

  /** Text of a stored value for display and as the prefilled input; a missing value is empty, never "null". */
  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private void list(Player p, String module, String path, List<?> values, int page, Runnable back) {
    var m = menu(p, "gui.list-title", Map.of("path", settingName(path)), this::admin);
    for (int i = page * 21; i < Math.min(values.size(), (page + 1) * 21); i++) {
      int index = i;
      entry(
          m,
          MenuStyle.slot(i % 21, Math.min(21, values.size() - page * 21)),
          Material.PAPER,
          accessLabel(path, text(values.get(i))),
          List.of(
              lang.render("gui.separator"),
              lang.render("gui.click.edit"),
              lang.render("gui.click.remove")),
          c -> {
            if (c.isRightClick())
              confirm(
                  p,
                  "gui.remove",
                  () -> {
                    var next = new ArrayList<>(values);
                    next.remove(index);
                    saveSetting(p, module, path, values, next, back);
                  },
                  () -> list(p, module, path, values, page, back),
                  this::admin);
            else if (!accessChoices(path).isEmpty())
              accessPicker(
                  p, module, path, values, index, () -> list(p, module, path, values, page, back));
            else
              ask(
                  p,
                  "gui.prompt-value",
                  this::admin,
                  input -> {
                    var next = new ArrayList<Object>(values);
                    next.set(index, parse(values.get(index), input));
                    saveSetting(p, module, path, values, next, back);
                  },
                  () -> list(p, module, path, values, page, back),
                  lang.render("gui.path", Map.of("path", path + "[" + (index + 1) + "]")),
                  text(values.get(index)),
                  text(values.get(index)));
          });
    }
    navigation(m, p, page, values.size(), n -> list(p, module, path, values, n, back), back);
    put(
        m,
        50,
        Material.LIME_DYE,
        "gui.add",
        "gui.click.edit",
        c -> {
          if (!accessChoices(path).isEmpty()) {
            accessPicker(
                p, module, path, values, -1, () -> list(p, module, path, values, page, back));
            return;
          }
          ask(
              p,
              "gui.prompt-value",
              this::admin,
              input -> {
                var next = new ArrayList<Object>(values);
                next.add(values.isEmpty() ? input : parse(values.getFirst(), input));
                saveSetting(p, module, path, values, next, back);
              },
              () -> list(p, module, path, values, page, back),
              lang.render("gui.path", Map.of("path", path)),
              values.isEmpty() ? "" : text(values.getFirst()),
              "");
        });
    MenuStyle.open(p, m.inventory);
  }

  private void saveSetting(
      Player p, String module, String path, Object expected, Object value, Runnable back) {
    complete(p, plugin.editSetting(module, path, expected, value), back, true);
  }

  public void npcList(Player p, int page) {
    var m = menu(p, "gui.npcs", Map.of(), this::npcView);
    var list =
        npcs.all().stream().sorted(Comparator.comparingInt(npc -> npcs.number(npc.id()))).toList();
    int actual = Math.clamp(page, 0, Math.max(0, (list.size() - 1) / 20));
    int count = Math.min(20, list.size() - actual * 20);
    for (int i = actual * 20; i < actual * 20 + count; i++) {
      var n = list.get(i);
      entry(
          m,
          MenuStyle.slot(i % 20, count + 1),
          Material.PLAYER_HEAD,
          lang.render(n.nameKey())
              .append(
                  Component.text(
                      " · №" + npcs.number(n.id()),
                      net.kyori.adventure.text.format.NamedTextColor.GRAY)),
          List.of(
              lang.render("gui.npc-id", Map.of("id", "" + npcs.number(n.id()))),
              lang.render(n.enabled() ? "gui.enabled" : "gui.disabled"),
              lang.render("gui.separator"),
              lang.render("gui.npc-list-edit"),
              lang.render("gui.npc-list-delete")),
          click -> {
            if (click == ClickType.LEFT) wizard.edit(p, n.id());
            else if (click == ClickType.RIGHT)
              confirm(
                  p,
                  "gui.delete-npc",
                  () ->
                      complete(
                          p,
                          npcs.delete(n.id(), n.revision(), p.getUniqueId().toString()),
                          () -> npcList(p, actual),
                          false),
                  () -> npcList(p, actual),
                  who -> plugin.module("npc") && who.hasPermission("loveessential.npc.delete"),
                  "№" + npcs.number(n.id()));
          });
    }
    navigation(m, p, actual, list.size(), 20, n -> npcList(p, n), () -> home(p));
    put(m, 50, Material.WRITABLE_BOOK, "gui.resume", "gui.click.open", c -> wizard.open(p));
    int create = MenuStyle.slot(count, count + 1);
    put(m, create, Material.LIME_DYE, "gui.create-npc", "gui.create-npc-help", c -> wizard.open(p));
    if (!p.hasPermission("loveessential.npc.create") || !p.hasPermission("loveessential.npc.edit"))
      locked(m, create);
    if (!p.hasPermission("loveessential.npc.edit")) locked(m, 50);
    MenuStyle.open(p, m.inventory);
  }

  public void hologramCommand(Player p, String[] args) {
    if (!admin(p) || !plugin.module("hologram"))
      throw new IllegalArgumentException("errors.permission");
    var service = gameplay.holograms();
    String op = args[0].toLowerCase(Locale.ROOT);
    if (op.equals("create")) {
      if (args.length == 1)
        ask(
            p,
            "holo.prompt-text",
            this::admin,
            text -> service.create(p, text, id -> hologram(p, id, 0, 0)),
            () -> places(p, "hologram", 0));
      else
        service.create(
            p,
            String.join(" ", Arrays.copyOfRange(args, 1, args.length)),
            id -> hologram(p, id, 0, 0));
      return;
    }
    if (args.length < 2) throw new IllegalArgumentException("holo.usage");
    var old =
        service.get(args[1]).orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
    switch (op) {
      case "edit", "info" -> hologram(p, args[1], 0, 0);
      case "move" ->
          holoSave(
              p,
              old,
              dev.lovelace.loveessential.api.Position.of(p.getLocation().add(0, 2, 0)),
              old.value().pages(),
              old.value().background(),
              old.value().shadow(),
              old.value().spacing(),
              old.value().range(),
              () -> hologram(p, args[1], 0, 0));
      case "delete" ->
          confirm(
              p,
              "gui.remove",
              () -> service.remove(p, old, () -> places(p, "hologram", 0)),
              () -> hologram(p, args[1], 0, 0),
              this::admin,
              args[1]);
      case "addline" -> {
        if (args.length < 3) throw new IllegalArgumentException("holo.usage");
        holoText(
            p,
            old,
            0,
            -1,
            String.join(" ", Arrays.copyOfRange(args, 2, args.length)),
            () -> hologram(p, args[1], 0, 0));
      }
      default -> throw new IllegalArgumentException("holo.usage");
    }
  }

  private void holoSave(
      Player p,
      dev.lovelace.loveessential.api.HologramService.Snapshot old,
      dev.lovelace.loveessential.api.Position at,
      List<dev.lovelace.loveessential.api.HologramService.Page> pages,
      int background,
      boolean shadow,
      double spacing,
      double range,
      Runnable done) {
    var value =
        new dev.lovelace.loveessential.api.HologramService.Hologram(
            old.value().id(), at, pages, background, shadow, spacing, range);
    gameplay.holograms().mutate(p, value, old.revision(), done);
  }

  private void holoPages(
      Player p,
      dev.lovelace.loveessential.api.HologramService.Snapshot old,
      List<dev.lovelace.loveessential.api.HologramService.Page> pages,
      Runnable done) {
    var v = old.value();
    holoSave(p, old, v.position(), pages, v.background(), v.shadow(), v.spacing(), v.range(), done);
  }

  private void holoText(
      Player p,
      dev.lovelace.loveessential.api.HologramService.Snapshot old,
      int page,
      int line,
      String text,
      Runnable done) {
    String key =
        "holograms." + old.value().id() + ".text_" + UUID.randomUUID().toString().replace("-", "");
    var lines = new ArrayList<>(old.value().pages().get(page).lines());
    var value = new dev.lovelace.loveessential.api.HologramService.Line(key, "");
    if (line < 0) lines.add(value);
    else lines.set(line, value);
    var pages = new ArrayList<>(old.value().pages());
    pages.set(
        page,
        new dev.lovelace.loveessential.api.HologramService.Page(lines, pages.get(page).action()));
    gameplay.holograms().publishText(p, key, text, () -> holoPages(p, old, pages, done));
  }

  public void hologram(Player p, String id, int page, int scroll) {
    var old =
        gameplay
            .holograms()
            .get(id)
            .orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
    var v = old.value();
    int selected = Math.clamp(page, 0, v.pages().size() - 1);
    var current = v.pages().get(selected);
    Predicate<Player> allowed =
        who ->
            admin(who)
                && plugin.module("hologram")
                && gameplay
                    .holograms()
                    .get(id)
                    .map(s -> s.revision() == old.revision())
                    .orElse(false);
    var m =
        menu(
            p,
            "holo.title",
            Map.of(
                "id",
                id,
                "page",
                Integer.toString(selected + 1),
                "pages",
                Integer.toString(v.pages().size())),
            allowed);
    Runnable reopen = () -> hologram(p, id, selected, scroll);
    m.reopen = reopen;
    // Lines come first; the two "add" tiles follow them as content, never as footer controls.
    boolean canAdd = current.lines().size() < 32;
    int total = current.lines().size() + (canAdd ? 2 : 0);
    for (int i = scroll * 21; i < Math.min(total, (scroll + 1) * 21); i++) {
      int index = i;
      if (i >= current.lines().size()) {
        if (i == current.lines().size())
          tile(
              m,
              MenuStyle.CONTENT[i % 21],
              Material.WRITABLE_BOOK,
              "holo.add-text",
              "holo.add-text-lore",
              Map.of(),
              c ->
                  ask(
                      p,
                      "holo.prompt-text",
                      allowed,
                      text -> holoText(p, old, selected, -1, text, reopen),
                      reopen));
        else
          tile(
              m,
              MenuStyle.CONTENT[i % 21],
              Material.ITEM_FRAME,
              "holo.add-item",
              "holo.add-item-lore",
              Map.of(),
              c -> {
                var lines = new ArrayList<>(current.lines());
                lines.add(
                    dev.lovelace.loveessential.module.Holograms.item(
                        p.getInventory().getItemInMainHand()));
                var pages = new ArrayList<>(v.pages());
                pages.set(
                    selected,
                    new dev.lovelace.loveessential.api.HologramService.Page(
                        lines, current.action()));
                holoPages(p, old, pages, reopen);
              });
        continue;
      }
      var line = current.lines().get(i);
      boolean item = !line.item().isBlank();
      var lore = new ArrayList<Component>();
      lore.add(item ? lang.render("holo.item-line") : gameplay.holograms().text(line, p));
      if (item) lore.add(dev.lovelace.loveessential.module.Holograms.decode(line).displayName());
      lore.add(lang.render("holo.line-help"));
      entry(
          m,
          MenuStyle.CONTENT[i % 21],
          item
              ? dev.lovelace.loveessential.module.Holograms.decode(line).getType()
              : Material.PAPER,
          lang.render("holo.line", Map.of("number", Integer.toString(i + 1))),
          lore,
          c -> {
            if (c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT) {
              int to = index + (c == ClickType.SHIFT_LEFT ? -1 : 1);
              if (to < 0 || to >= current.lines().size()) return;
              var lines = new ArrayList<>(current.lines());
              Collections.swap(lines, index, to);
              var pages = new ArrayList<>(v.pages());
              pages.set(
                  selected,
                  new dev.lovelace.loveessential.api.HologramService.Page(lines, current.action()));
              holoPages(p, old, pages, reopen);
            } else holoLine(p, old, selected, index, scroll);
          });
    }
    navigation(
        m,
        p,
        scroll,
        total,
        n -> hologram(p, id, selected, n),
        () -> places(p, "hologram", 0));
    put(m, 4, Material.COMPARATOR, "holo.appearance", "holo.appearance-help",
        c -> hologramAppearance(p, id, selected, scroll));
    tile(
        m,
        8,
        Material.BOOK,
        "holo.pages",
        "holo.pages-lore",
        Map.of("count", Integer.toString(v.pages().size())),
        c -> hologramPages(p, id, selected));
    put(
        m,
        50,
        Material.BARRIER,
        "gui.remove",
        "gui.click.delete",
        c ->
            confirm(
                p,
                "gui.remove",
                () -> gameplay.holograms().remove(p, old, () -> places(p, "hologram", 0)),
                reopen,
                allowed,
                id));
    MenuStyle.open(p, m.inventory);
  }

  public void hologramAppearance(Player p, String id, int page, int scroll) {
    var old = gameplay.holograms().get(id)
        .orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
    var v = old.value();
    int selected = Math.clamp(page, 0, v.pages().size() - 1);
    Predicate<Player> allowed = who -> admin(who) && plugin.module("hologram")
        && gameplay.holograms().get(id).map(s -> s.revision() == old.revision()).orElse(false);
    var m = menu(p, "holo.appearance-title", Map.of("id", id), allowed);
    Runnable reopen = () -> hologramAppearance(p, id, selected, scroll);
    m.reopen = reopen;
    entry(
        m,
        MenuStyle.slot(0, 5),
        Material.BLACK_DYE,
        lang.render("holo.background", Map.of("value", String.format("%08X", v.background()))),
        List.of(lang.render("holo.background-help"), lang.render("gui.separator"), lang.render("gui.click.edit")),
        c ->
            request(
                p,
                "holo.prompt-background",
                "80000000",
                String.format("%08X", v.background()),
                allowed,
                text -> {
                  if (!text.matches("[0-9a-fA-F]{8}"))
                    throw new IllegalArgumentException("errors.input");
                  holoSave(
                      p,
                      old,
                      v.position(),
                      v.pages(),
                      (int) Long.parseLong(text, 16),
                      v.shadow(),
                      v.spacing(),
                      v.range(),
                      reopen);
                },
                reopen));
    entry(
        m,
        MenuStyle.slot(1, 5),
        Material.GLOWSTONE_DUST,
        lang.render("holo.shadow", Map.of("value", lang.text(v.shadow() ? "holo.on" : "holo.off"))),
        List.of(lang.render("holo.shadow-help"), lang.render("gui.separator"), lang.render("gui.click.switch")),
        c ->
            holoSave(
                p,
                old,
                v.position(),
                v.pages(),
                v.background(),
                !v.shadow(),
                v.spacing(),
                v.range(),
                reopen));
    entry(
        m,
        MenuStyle.slot(2, 5),
        Material.STRING,
        lang.render("holo.spacing", Map.of("value", Double.toString(v.spacing()))),
        List.of(lang.render("holo.spacing-help"), lang.render("gui.separator"), lang.render("gui.click.edit")),
        c ->
            request(
                p,
                "holo.prompt-spacing",
                "0.3",
                Double.toString(v.spacing()),
                allowed,
                text ->
                    holoSave(
                        p,
                        old,
                        v.position(),
                        v.pages(),
                        v.background(),
                        v.shadow(),
                        Double.parseDouble(text),
                        v.range(),
                        reopen),
                reopen));
    entry(
        m,
        MenuStyle.slot(3, 5),
        Material.ENDER_EYE,
        lang.render("holo.range", Map.of("value", Double.toString(v.range()))),
        List.of(lang.render("holo.range-help"), lang.render("gui.separator"), lang.render("gui.click.edit")),
        c ->
            request(
                p,
                "holo.prompt-range",
                "48",
                Double.toString(v.range()),
                allowed,
                text ->
                    holoSave(
                        p,
                        old,
                        v.position(),
                        v.pages(),
                        v.background(),
                        v.shadow(),
                        v.spacing(),
                        Double.parseDouble(text),
                        reopen),
                reopen));
    entry(
        m,
        MenuStyle.slot(4, 5),
        Material.COMPASS,
        lang.render("gui.move"),
        List.of(
            lang.render(
                "holo.position",
                Map.of(
                    "x",
                    Double.toString(v.position().x()),
                    "y",
                    Double.toString(v.position().y()),
                    "z",
                    Double.toString(v.position().z()))),
            lang.render("holo.move-help"),
            lang.render("holo.move-adjust")),
        c -> {
          var at =
              c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT
                  ? v.position().location().add(0, c == ClickType.SHIFT_LEFT ? .25 : -.25, 0)
                  : p.getLocation().add(0, 2, 0);
          holoSave(
              p,
              old,
              dev.lovelace.loveessential.api.Position.of(at),
              v.pages(),
              v.background(),
              v.shadow(),
              v.spacing(),
              v.range(),
              reopen);
        });
    navigation(m, p, 0, 0, n -> {}, () -> hologram(p, id, selected, scroll));
    MenuStyle.open(p, m.inventory);
  }

  /** Page manager: pages and their editing actions belong to the work zone. */
  public void hologramPages(Player p, String id, int selected) {
    var old =
        gameplay
            .holograms()
            .get(id)
            .orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
    var v = old.value();
    int page = Math.clamp(selected, 0, v.pages().size() - 1);
    var current = v.pages().get(page);
    Predicate<Player> allowed =
        who ->
            admin(who)
                && plugin.module("hologram")
                && gameplay
                    .holograms()
                    .get(id)
                    .map(s -> s.revision() == old.revision())
                    .orElse(false);
    var m = menu(p, "holo.pages-title", Map.of("id", id), allowed);
    Runnable reopen = () -> hologramPages(p, id, page);
    m.reopen = reopen;
    int count = v.pages().size();
    int create = count < 16 ? 1 : 0;
    int total = count + create + 1 + (count > 1 ? 1 : 0);
    entry(
        m,
        MenuStyle.slot(count + create, total),
        Material.COMMAND_BLOCK,
        lang.render("holo.action"),
        List.of(
            lang.render(
                "holo.action-value",
                Map.of(
                    "value",
                    current.action() == null
                        ? lang.text("holo.page-next")
                        : current.action().type() + " " + current.action().args())),
            lang.render("holo.action-help")),
        c ->
            request(
                p,
                "holo.prompt-action",
                "recipes",
                current.action() != null ? current.action().args().getOrDefault("value", "") : "",
                allowed,
                text -> {
                  var pages = new ArrayList<>(v.pages());
                  var action =
                      text.equalsIgnoreCase("reset")
                          ? null
                          : new dev.lovelace.loveessential.api.Action(
                              "command", Map.of("value", text));
                  pages.set(
                      page,
                      new dev.lovelace.loveessential.api.HologramService.Page(
                          current.lines(), action));
                  holoPages(p, old, pages, reopen);
                },
                reopen));
    if (v.pages().size() > 1)
      put(
          m,
          MenuStyle.slot(count + create + 1, total),
          Material.RED_DYE,
          "holo.delete-page",
          "gui.click.delete",
          c ->
              confirm(
                  p,
                  "holo.delete-page",
                  () -> {
                    var pages = new ArrayList<>(v.pages());
                    pages.remove(page);
                    holoPages(
                        p, old, pages, () -> hologramPages(p, id, Math.max(0, page - 1)));
                  },
                  reopen,
                  allowed,
                  Integer.toString(page + 1)));
    for (int i = 0; i < v.pages().size(); i++) {
      int index = i;
      boolean chosen = i == page;
      tile(
          m,
          MenuStyle.slot(i, total),
          Material.PAPER,
          chosen ? "holo.page-entry-selected" : "holo.page-entry",
          chosen ? "holo.page-lore-selected" : "holo.page-lore",
          Map.of(
              "number", Integer.toString(i + 1),
              "count", Integer.toString(v.pages().get(i).lines().size())),
          c -> {
            if (!chosen) hologramPages(p, id, index);
            else {
              // Return through Back so the manager is not stacked under the editor, then open the
              // editor on the page that was chosen here.
              MenuStyle.back(p);
              hologram(p, id, page, 0);
            }
          });
    }
    if (v.pages().size() < 16)
      tile(
          m,
          MenuStyle.slot(count, total),
          Material.BOOK,
          "holo.add-page",
          "holo.add-page-lore",
          Map.of(),
          c -> {
            var pages = new ArrayList<>(v.pages());
            pages.add(new dev.lovelace.loveessential.api.HologramService.Page(List.of(), null));
            holoPages(p, old, pages, () -> hologramPages(p, id, pages.size() - 1));
          });
    navigation(m, p, 0, v.pages().size() + 1, n -> {}, () -> hologram(p, id, page, 0));
    MenuStyle.open(p, m.inventory);
  }

  private void holoLine(
      Player p,
      dev.lovelace.loveessential.api.HologramService.Snapshot old,
      int page,
      int index,
      int scroll) {
    var v = old.value();
    var current = v.pages().get(page);
    var line = current.lines().get(index);
    Runnable back = () -> hologram(p, v.id(), page, scroll);
    Predicate<Player> allowed =
        who ->
            admin(who)
                && plugin.module("hologram")
                && gameplay
                    .holograms()
                    .get(v.id())
                    .map(s -> s.revision() == old.revision())
                    .orElse(false);
    var m = menu(p, "holo.line-title", Map.of("number", Integer.toString(index + 1)), allowed);
    var preview =
        List.of(
            line.item().isBlank()
                ? gameplay.holograms().text(line, p)
                : lang.render("holo.item-line"));
    entry(m, 19, Material.PAPER, lang.render("holo.preview"), preview, c -> {});
    put(
        m,
        20,
        Material.WRITABLE_BOOK,
        "holo.edit-text",
        "holo.text-help",
        c ->
            request(
                p,
                "holo.prompt-text",
                lang.text("holo.text-example"),
                line.item().isBlank() ? lang.text(line.textKey()) : "",
                allowed,
                text -> holoText(p, old, page, index, text, back),
                back));
    put(
        m,
        22,
        Material.ITEM_FRAME,
        "holo.replace-item",
        "holo.item-help",
        c -> {
          var lines = new ArrayList<>(current.lines());
          lines.set(
              index,
              dev.lovelace.loveessential.module.Holograms.item(
                  p.getInventory().getItemInMainHand()));
          var pages = new ArrayList<>(v.pages());
          pages.set(
              page,
              new dev.lovelace.loveessential.api.HologramService.Page(lines, current.action()));
          holoPages(p, old, pages, back);
        });
    put(
        m,
        24,
        Material.BARRIER,
        "gui.remove",
        "gui.click.delete",
        c ->
            confirm(
                p,
                "gui.remove",
                () -> {
                  var lines = new ArrayList<>(current.lines());
                  lines.remove(index);
                  var pages = new ArrayList<>(v.pages());
                  pages.set(
                      page,
                      new dev.lovelace.loveessential.api.HologramService.Page(
                          lines, current.action()));
                  holoPages(p, old, pages, back);
                },
                back,
                allowed,
                Integer.toString(index + 1)));
    for (int direction : new int[] {-1, 1})
      if (index + direction >= 0 && index + direction < current.lines().size())
        put(
            m,
            direction < 0 ? 30 : 32,
            Material.ARROW,
            direction < 0 ? "holo.up" : "holo.down",
            "holo.reorder-help",
            c -> {
              var lines = new ArrayList<>(current.lines());
              Collections.swap(lines, index, index + direction);
              var pages = new ArrayList<>(v.pages());
              pages.set(
                  page,
                  new dev.lovelace.loveessential.api.HologramService.Page(lines, current.action()));
              holoPages(p, old, pages, back);
            });
    navigation(m, p, 0, 0, n -> {}, back);
    MenuStyle.open(p, m.inventory);
  }

  public void places(Player p, String kind, int page) {
    if (kind.equals("portal")) {
      gameplay.portals().list(p, page);
      return;
    }
    Predicate<Player> allowed =
        who -> plugin.module(kind) && (!Set.of("portal", "hologram").contains(kind) || admin(who));
    var m =
        menu(
            p, "gui.places-title", Map.of("kind", lang.text("gui.module-labels." + kind)), allowed);
    var names = gameplay.names(p, kind);
    for (int i = page * 21; i < Math.min(names.size(), (page + 1) * 21); i++) {
      String id = names.get(i);
      entry(
          m,
          MenuStyle.slot(i % 21, Math.min(21, names.size() - page * 21)),
          Material.ENDER_PEARL,
          lang.render("gui.label", Map.of("name", id)),
          List.of(lang.render("gui.separator"), lang.render("gui.click.open")),
          c -> {
            if (kind.equals("home") || kind.equals("spawn")) command(p, kind + " " + id);
            else if (kind.equals("hologram")) hologram(p, id, 0, 0);
            else place(p, kind, id);
          });
    }
    navigation(m, p, page, names.size(), n -> places(p, kind, n), () -> home(p));
    if (kind.equals("hologram"))
      put(
          m,
          50,
          Material.LIME_DYE,
          "gui.add",
          "holo.create-help",
          c -> hologramCommand(p, new String[] {"create"}));
    else if (kind.equals("portal"))
      put(
          m,
          50,
          Material.LIME_DYE,
          "gui.add",
          "holo.portal-create-help",
          c -> command(p, "portal create"));
    else if (!kind.equals("spawn") || admin(p))
      put(
          m,
          50,
          Material.LIME_DYE,
          "gui.add",
          "gui.click.edit",
          c ->
              ask(
                  p,
                  "gui.prompt-id",
                  allowed,
                  id -> {
                    if (!id.matches("[a-z0-9_-]{1,48}"))
                      throw new IllegalArgumentException("validation.id");
                    if (kind.equals("hologram"))
                      ask(
                          p,
                          "gui.prompt-text",
                          allowed,
                          text -> command(p, "hologram create " + text),
                          () -> places(p, kind, page));
                    else
                      command(
                          p,
                          switch (kind) {
                            case "home" -> "sethome " + id;
                            case "spawn" -> "setspawn " + id;
                            default -> "portal create " + id;
                          });
                  },
                  () -> places(p, kind, page)));
    if (kind.equals("portal"))
      put(m, 4, Material.WOODEN_AXE, "gui.pos1", "gui.click.run", c -> command(p, "portal pos1"));
    MenuStyle.open(p, m.inventory);
  }

  private void place(Player p, String kind, String id) {
    var m = menu(p, "gui.place-title", Map.of("id", id), who -> admin(who) && plugin.module(kind));
    put(
        m,
        20,
        Material.COMPASS,
        kind.equals("portal") ? "gui.destination" : "gui.move",
        "gui.click.run",
        c -> command(p, kind + (kind.equals("portal") ? " destination " : " move ") + id));
    put(
        m,
        24,
        Material.BARRIER,
        "gui.remove",
        "gui.click.delete",
        c ->
            confirm(
                p,
                "gui.remove",
                () -> command(p, kind + " delete " + id),
                () -> place(p, kind, id),
                this::admin,
                id));
    navigation(m, p, 0, 0, n -> {}, () -> places(p, kind, 0));
    MenuStyle.open(p, m.inventory);
  }

  public void skin(Player p) {
    Predicate<Player> allowed = who -> plugin.module("skin");
    var m = menu(p, "gui.skin-title", Map.of(), allowed);
    put(
        m,
        20,
        Material.PLAYER_HEAD,
        "gui.skin-name",
        "gui.click.edit",
        c -> ask(p, "gui.prompt-skin", allowed, name -> command(p, "skin " + name), () -> skin(p)));
    put(
        m,
        24,
        Material.SUNFLOWER,
        "gui.skin-reset",
        "gui.click.run",
        c -> command(p, "skin " + p.getName()));
    navigation(m, p, 0, 0, n -> {}, () -> commands(p, 0));
    MenuStyle.open(p, m.inventory);
  }

  public void nickname(Player p) {
    nicknames.settings(p);
    var m = menu(p, "nick.title", Map.of(), nicknames::canUse);
    m.inventory.setItem(
        4,
        dev.lovelace.loveessential.module.PlayerProfiles.head(
            p,
            nicknames.display(p),
            List.of(
                lang.render("nick.real-name", Map.of("name", p.getName())),
                lang.render("nick.preview"))));
    put(
        m,
        19,
        Material.NAME_TAG,
        "nick.set",
        "gui.click.edit",
        c ->
            ask(
                p,
                "nick.prompt-name",
                nicknames::canUse,
                value -> nicknameChange(p, "set", value),
                () -> nickname(p)));
    put(m, 21, Material.YELLOW_DYE, "nick.color", "gui.click.open", c -> nicknameColors(p));
    put(
        m,
        23,
        Material.FIREWORK_STAR,
        "nick.gradient",
        "gui.click.open",
        c -> nicknameGradients(p, 0));
    put(
        m,
        25,
        Material.MILK_BUCKET,
        "nick.clear-style",
        "gui.click.run",
        c -> nicknameChange(p, "color", "reset"));
    put(
        m,
        31,
        Material.BARRIER,
        "nick.reset",
        "gui.click.run",
        c ->
            confirm(
                p,
                "nick.reset",
                () -> nicknameChange(p, "reset"),
                () -> nickname(p),
                nicknames::canUse));
    navigation(m, p, 0, 0, n -> {}, () -> home(p));
    MenuStyle.open(p, m.inventory);
  }

  private void nicknameChange(Player p, String... args) {
    complete(p, nicknames.change(p, p, args), () -> nickname(p), false);
  }

  private void nicknameColors(Player p) {
    Predicate<Player> allowed = who -> nicknames.canUse(who);
    var m = menu(p, "nick.color", Map.of(), allowed);
    var colors =
        net.kyori.adventure.text.format.NamedTextColor.NAMES.keys().stream().sorted().toList();
    for (int i = 0; i < colors.size(); i++) {
      String color = colors.get(i);
      entry(
          m,
          MenuStyle.slot(i, colors.size()),
          Material.WHITE_DYE,
          lang.render("nick.colors." + color)
              .color(dev.lovelace.loveessential.module.Nicknames.color(color)),
          List.of(
              dev.lovelace.loveessential.module.Nicknames.render(
                  nicknames.values(p.getUniqueId()).getOrDefault("nickname_plain", p.getName()),
                  List.of(color)),
              lang.render("gui.separator"),
              lang.render("gui.click.run")),
          c -> nicknameChange(p, "color", color));
    }
    put(
        m,
        50,
        Material.WRITABLE_BOOK,
        "nick.custom-color",
        "gui.click.edit",
        c ->
            ask(
                p,
                "nick.prompt-color",
                allowed,
                value -> nicknameChange(p, "color", value),
                () -> nicknameColors(p)));
    navigation(m, p, 0, 0, n -> {}, () -> nickname(p));
    MenuStyle.open(p, m.inventory);
  }

  private void nicknameGradients(Player p, int page) {
    Predicate<Player> allowed = who -> nicknames.canUse(who);
    var m = menu(p, "nick.gradient", Map.of(), allowed);
    var section = plugin.getConfig().getConfigurationSection("nickname.gradients");
    var keys =
        section == null ? List.<String>of() : section.getKeys(false).stream().sorted().toList();
    int count = Math.max(0, Math.min(21, keys.size() - page * 21));
    for (int i = 0; i < count; i++) {
      String key = keys.get(page * 21 + i);
      var colors = section.getStringList(key);
      String plain = nicknames.values(p.getUniqueId()).getOrDefault("nickname_plain", p.getName());
      var preview = dev.lovelace.loveessential.module.Nicknames.render(plain, colors);
      entry(
          m,
          MenuStyle.slot(i, count),
          Material.FIREWORK_STAR,
          lang.contains("nick.gradients." + key)
              ? lang.render("nick.gradients." + key)
              : lang.render("gui.label", Map.of("name", key)),
          List.of(preview, lang.render("gui.separator"), lang.render("gui.click.run")),
          c -> {
            var args = new ArrayList<String>();
            args.add("gradient");
            args.addAll(colors);
            nicknameChange(p, args.toArray(String[]::new));
          });
    }
    put(
        m,
        50,
        Material.WRITABLE_BOOK,
        "nick.custom-gradient",
        "gui.click.edit",
        c ->
            ask(
                p,
                "nick.prompt-gradient",
                allowed,
                value -> {
                  var args = new ArrayList<>(List.of("gradient"));
                  args.addAll(Arrays.asList(value.trim().split("\\s+")));
                  nicknameChange(p, args.toArray(String[]::new));
                },
                () -> nicknameGradients(p, page)));
    navigation(m, p, page, keys.size(), n -> nicknameGradients(p, n), () -> nickname(p));
    MenuStyle.open(p, m.inventory);
  }

  public void commands(Player p, int page) {
    var names = plugin.getDescription().getCommands().keySet().stream().sorted().toList();
    var m = menu(p, "gui.commands", Map.of(), who -> true);
    for (int i = page * 21; i < Math.min(names.size(), (page + 1) * 21); i++) {
      String name = names.get(i);
      entry(
          m,
          MenuStyle.slot(i % 21, Math.min(21, names.size() - page * 21)),
          Material.BOOK,
          lang.render(
              "gui.command-name",
              Map.of("command", name.equals("loveessential") ? "le" : "le " + name)),
          List.of(
              lang.render("gui.command-help." + name),
              lang.render("gui.separator"),
              lang.render("gui.click.run")),
          c -> {
            switch (name) {
              case "loveessential" -> home(p);
              case "npc" -> npcList(p, 0);
              case "homes", "home", "delhome" -> {
                if (name.equals("delhome"))
                  ask(
                      p,
                      "gui.prompt-id",
                      who -> true,
                      id ->
                          confirm(
                              p,
                              "gui.remove",
                              () -> command(p, "delhome " + id),
                              () -> commands(p, page),
                              who -> true,
                              id),
                      () -> commands(p, page));
                else places(p, "home", 0);
              }
              case "portal", "hologram" -> places(p, name, 0);
              case "skin" -> skin(p);
              case "nick" -> nickname(p);
              case "speed", "gamemode" ->
                  request(
                      p,
                      name.equals("speed") ? "comfort.prompt-speed" : "comfort.prompt-mode",
                      name.equals("speed") ? "2" : "survival",
                      "",
                      who ->
                          plugin.module("utilities") && who.hasPermission("loveessential." + name),
                      value -> command(p, name + " " + value),
                      () -> commands(p, page));
              case "sethome", "setspawn", "reply" ->
                  ask(
                      p,
                      "gui.prompt-value",
                      who -> true,
                      input -> command(p, name + " " + input),
                      () -> commands(p, page));
              default -> command(p, name);
            }
          });
    }
    navigation(m, p, page, names.size(), n -> commands(p, n), () -> home(p));
    MenuStyle.open(p, m.inventory);
  }

  private void confirm(
      Player p, String title, Runnable action, Runnable back, Predicate<Player> allowed) {
    confirm(p, title, action, back, allowed, "");
  }

  private void confirm(
      Player p,
      String title,
      Runnable action,
      Runnable back,
      Predicate<Player> allowed,
      String object) {
    var m = menu(p, title, Map.of(), allowed);
    entry(
        m,
        22,
        Material.PAPER,
        lang.render("gui.label", Map.of("name", object)),
        List.of(lang.render("gui.confirm-warning")),
        c -> {});
    put(
        m,
        20,
        Material.LIME_CONCRETE,
        "gui.confirm",
        "gui.click.run",
        c -> {
          p.closeInventory();
          action.run();
        });
    put(m, 24, Material.RED_CONCRETE, "gui.cancel", "gui.click.open", c -> back.run());
    put(m, 52, Material.BARRIER, "gui.close", "gui.click.close", c -> p.closeInventory());
    MenuStyle.open(p, m.inventory);
  }

  public void clearInput(Player p) {
    cancelInput(p);
    wizard.cancelInput(p);
  }

  public void request(
      Player p,
      String key,
      String example,
      String current,
      Predicate<Player> allowed,
      Consumer<String> input,
      Runnable back) {
    ask(
        p,
        key,
        allowed,
        input,
        back,
        lang.render(
            key.startsWith("holo.")
                ? "input-extra.hologram-context"
                : key.startsWith("comfort.")
                    ? "input-extra.comfort-context"
                    : "content.recipe-input-context"),
        example,
        current);
  }

  private void ask(
      Player p, String key, Predicate<Player> allowed, Consumer<String> input, Runnable back) {
    ask(
        p,
        key,
        allowed,
        input,
        back,
        lang.render(
            key.startsWith("holo.")
                ? "input-extra.hologram-context"
                : "input.contexts." + key.replace('.', '-')),
        lang.contains("input.examples." + key.replace('.', '-'))
            ? lang.text("input.examples." + key.replace('.', '-'))
            : "",
        "");
  }

  private void ask(
      Player p,
      String key,
      Predicate<Player> allowed,
      Consumer<String> input,
      Runnable back,
      Component context,
      String example,
      String current) {
    if (!allowed.test(p) || !love.authenticated(p))
      throw new IllegalArgumentException("errors.permission");
    wizard.cancelInput(p);
    p.closeInventory();
    if (nativeUi != null) {
      nativeUi.show(
          p,
          context,
          lang.render(key),
          List.of(
              dev.lovelace.loveessential.dialog.NativeDialogs.Field.text(
                  "value", lang.render("gui.value", Map.of("value", example)), current, 2048)),
          List.of(
              new dev.lovelace.loveessential.dialog.NativeDialogs.Button(
                  "save",
                  lang.render("gui.confirm"),
                  v -> {
                    input.accept(v.get("value"));
                  }),
              new dev.lovelace.loveessential.dialog.NativeDialogs.Button(
                  "back", lang.render("common.back"), v -> back.run())),
          allowed);
      return;
    }
    var prompt =
        new Prompt(
            System.currentTimeMillis() + 180000,
            allowed,
            input,
            back,
            UUID.randomUUID().toString(),
            context,
            key,
            example,
            current);
    prompts.put(p.getUniqueId(), prompt);
    sendPrompt(p, prompt);
  }

  private void sendPrompt(Player p, Prompt prompt) {
    ChatPrompt.send(
        p,
        lang,
        prompt.token,
        prompt.context,
        lang.render(prompt.instructionKey),
        prompt.example,
        prompt.current);
  }

  public boolean cancelPrompt(Player p, String token) {
    var prompt = prompts.get(p.getUniqueId());
    if (prompt == null || !prompt.token.equals(token)) return false;
    return input(p, "cancel");
  }

  public boolean input(Player p, String value) {
    var prompt = prompts.remove(p.getUniqueId());
    if (prompt == null) return false;
    if (prompt.expires < System.currentTimeMillis()) {
      lang.send(p, "input.expired");
      return true;
    }
    try {
      if (!plugin.ready() || !love.authenticated(p) || !prompt.allowed.test(p))
        throw new IllegalArgumentException("errors.permission");
      if (value.equalsIgnoreCase("cancel") || value.equalsIgnoreCase("отмена")) {
        lang.send(p, "input.cancelled");
        prompt.back.run();
      } else prompt.input.accept(value);
    } catch (RuntimeException failure) {
      error(p, failure);
      if (plugin.ready() && love.authenticated(p) && prompt.allowed.test(p)) {
        prompts.putIfAbsent(p.getUniqueId(), prompt);
        sendPrompt(p, prompts.get(p.getUniqueId()));
      }
    }
    return true;
  }

  private void number(Player p, String module, String path, Object value, Runnable back) {
    var m = menu(p, "settings-help.number-title", Map.of("path", settingName(path)), this::admin);
    m.inventory.setItem(
        22,
        MenuStyle.item(
            Material.COMPARATOR,
            lang.render("gui.label", Map.of("name", path)),
            List.of(
                lang.render("gui.value", Map.of("value", text(value))),
                lang.render("settings-help.number"))));
    double step = value instanceof Double || value instanceof Float ? .1 : 1;
    int[] changes = {-10, -5, -1, 1, 5, 10};
    for (int i = 0; i < changes.length; i++) {
      int delta = changes[i];
      entry(
          m,
          MenuStyle.slot(i, changes.length),
          delta < 0 ? Material.RED_DYE : Material.LIME_DYE,
          lang.render(
              "settings-help.change",
              Map.of(
                  "amount",
                  (delta > 0 ? "+" : "")
                      + java.math.BigDecimal.valueOf(delta * step)
                          .stripTrailingZeros()
                          .toPlainString())),
          List.of(lang.render("gui.click.run")),
          c -> {
            String next =
                java.math.BigDecimal.valueOf(((Number) value).doubleValue())
                    .add(java.math.BigDecimal.valueOf(delta * step))
                    .stripTrailingZeros()
                    .toPlainString();
            saveSetting(
                p,
                module,
                path,
                value,
                parse(value, next),
                () ->
                    number(
                        p,
                        module,
                        path,
                        dev.lovelace.loveessential.config.ModuleConfigs.view(
                                plugin.getConfig(), module)
                            .get(path),
                        back));
          });
    }
    put(
        m,
        50,
        Material.WRITABLE_BOOK,
        "gui.manual-input",
        "gui.click.edit",
        c ->
            ask(
                p,
                "gui.prompt-value",
                this::admin,
                input -> saveSetting(p, module, path, value, parse(value, input), back),
                () -> number(p, module, path, value, back),
                lang.render("gui.path", Map.of("path", path)),
                text(value),
                text(value)));
    navigation(m, p, 0, 0, n -> {}, back);
    MenuStyle.open(p, m.inventory);
  }

  private void complete(Player p, CompletableFuture<?> future, Runnable back, boolean adminOnly) {
    if (adminOnly && !admin(p)) throw new IllegalArgumentException("errors.permission");
    p.closeInventory();
    future.whenComplete(
        (v, e) ->
            main.execute(
                () -> {
                  if (!p.isOnline()) return;
                  if (e != null) error(p, e);
                  else lang.send(p, "gui.saved");
                  if (love.authenticated(p) && (!adminOnly || admin(p))) {
                    try {
                      back.run();
                    } catch (RuntimeException failure) {
                      error(p, failure);
                    }
                  }
                }));
  }

  private void error(Player p, Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    lang.error(p, e);
    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, .4f, 1);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)) return;
    if (e.getRawSlot() < 0 || e.getRawSlot() >= 54) return;
    var action = m.actions.get(e.getRawSlot());
    if (action == null) return;
    ClickType click = e.getClick();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!p.isOnline()
                  || p.getOpenInventory().getTopInventory() == null
                  || p.getOpenInventory().getTopInventory().getHolder() != m) return;
              try {
                if (!plugin.ready() || !love.authenticated(p) || !m.allowed.test(p))
                  throw new IllegalArgumentException("errors.permission");
                MenuStyle.action(p, () -> action.accept(click));
              } catch (RuntimeException ex) {
                error(p, ex);
              }
            });
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void chat(AsyncChatEvent e) {
    var prompt = prompts.get(e.getPlayer().getUniqueId());
    if (prompt == null) return;
    if (prompt.expires < System.currentTimeMillis()) {
      prompts.remove(e.getPlayer().getUniqueId(), prompt);
      e.setCancelled(true);
      main.execute(() -> lang.send(e.getPlayer(), "input.expired"));
      return;
    }
    e.setCancelled(true);
    String value = PlainTextComponentSerializer.plainText().serialize(e.message());
    main.execute(() -> input(e.getPlayer(), value));
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    prompts.remove(e.getPlayer().getUniqueId());
    roots.remove(e.getPlayer().getUniqueId());
    vanishFilter.remove(e.getPlayer().getUniqueId());
    direct.remove(e.getPlayer().getUniqueId());
    dispatching.remove(e.getPlayer().getUniqueId());
  }

  public void close() {
    HandlerList.unregisterAll(this);
    prompts.clear();
    roots.clear();
    vanishFilter.clear();
    direct.clear();
    dispatching.clear();
  }
}
