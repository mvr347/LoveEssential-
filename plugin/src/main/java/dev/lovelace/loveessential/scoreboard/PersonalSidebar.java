package dev.lovelace.loveessential.scoreboard;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.gui.GuiLayout;
import dev.lovelace.loveessential.gui.MenuStyle;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.scoreboard.PersonalCatalog.Item;
import dev.lovelace.loveessential.scoreboard.PersonalState.Entry;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.Placeholders;
import dev.lovelace.loveessential.tab.TabSyntax;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The player's own scoreboard: every player picks and orders up to eight lines from the catalog in
 * scoreboard.yml, two per row. The choice is stored per player in SQLite. A new player starts with
 * {@code scoreboard.default-preset}. The menu follows gui_gen v2.1: the header holds the quick
 * layouts and the view toggle, the work zone the numbered line cards and the categories (or one
 * flat paged list), the footer the on/off switch, Back and Close.
 */
final class PersonalSidebar implements Listener, AutoCloseable {
  static final String KIND = "scoreboard-personal";
  static final String MENU = "sidebar.menu-title", CATALOG_MENU = "sidebar.catalog-title",
      PRESETS_MENU = "sidebar.presets-title";
  private static final String HEAD =
      "d0d5a4eba140cbb34d45e40d62dd3f7e6584b2ab0b4155a0bcc603d5d750f793";

  /** The three screens of the personal scoreboard. */
  enum Kind {
    MAIN,
    CATALOG,
    PRESETS
  }

  /** What a player last chose in the catalog: category filter (blank is all) and sort. */
  private static final class View {
    String filter = "";
    CatalogView.Sort sort = CatalogView.Sort.NAME;
  }

  /** Why a line cannot be used right now. */
  enum Status {
    AVAILABLE,
    MISSING_PLUGIN,
    REQUIREMENT
  }

  private static final class Slot {
    final PersonalState state;
    long revision;
    CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    Slot(long revision, PersonalState state) {
      this.revision = revision;
      this.state = state;
    }
  }

  private final class Menu implements GuiLayout {
    final String id;
    final Map<String, String> variables;
    final Kind kind;
    /** Catalog only: the slot being replaced, or -1 when a line is added. */
    final int target;
    final Player player;
    final Map<Integer, Consumer<ClickType>> clicks = new HashMap<>();
    Inventory inventory;
    int page;

    Menu(Player player, Kind kind, String id, int target) {
      this.player = player;
      this.kind = kind;
      this.id = id;
      this.variables = Map.of();
      this.target = target;
    }

    @Override
    public String guiId() {
      return id;
    }

    @Override
    public Map<String, String> guiVariables() {
      return variables;
    }

    @Override
    public boolean guiAllowed(Player p) {
      return plugin.module("scoreboard");
    }

    /** Back from a child: the parent is drawn again, because the choice may have changed. */
    @Override
    public void guiResume() {
      draw(this);
    }

    /** The main menu has no parent when opened from the profile settings link, so Back returns there. */
    @Override
    public Runnable guiBack() {
      return kind == Kind.MAIN ? () -> Bukkit.dispatchCommand(player, "profile settings") : null;
    }

    @Override
    public Runnable guiReopen() {
      return () -> open(player);
    }

    @Override
    public Map<Integer, ?> guiActions() {
      return clicks;
    }

    @Override
    public void guiPut(int slot, Runnable action) {
      clicks.put(slot, click -> action.run());
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }

    @Override
    public void guiInventory(Inventory value) {
      inventory = value;
    }
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final Placeholders placeholders;
  private final ScoreboardModule host;
  private final MiniMessage mini = MiniMessage.miniMessage();
  private final Map<UUID, Slot> slots = new HashMap<>();
  private final Set<UUID> loading = new HashSet<>();
  private final Map<UUID, View> views = new HashMap<>();
  private volatile PersonalCatalog catalog = PersonalCatalog.EMPTY;
  private boolean closed;

  PersonalSidebar(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      Placeholders placeholders,
      ScoreboardModule host) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.placeholders = placeholders;
    this.host = host;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  /** Reads the catalog again; a catalog that cannot be read keeps the previous one. */
  void reload() {
    try {
      catalog = PersonalCatalog.read(plugin.getConfig());
    } catch (RuntimeException error) {
      plugin.getLogger().warning("scoreboard catalog not reloaded: " + error.getMessage());
    }
  }

  PersonalCatalog catalog() {
    return catalog;
  }

  boolean ready(UUID player) {
    return slots.containsKey(player);
  }

  // -------------------------------------------------------------- storage

  void load(Player p) {
    UUID id = p.getUniqueId();
    if (slots.containsKey(id) || !loading.add(id)) return;
    store
        .get(KIND, id.toString(), PersonalState.Stored.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      loading.remove(id);
                      if (closed || !p.isOnline()) return;
                      if (error != null) {
                        plugin.getLogger().warning("scoreboard: choice not loaded for " + p.getName());
                        Bukkit.getScheduler().runTaskLater(plugin, () -> load(p), 200L);
                        return;
                      }
                      slots.putIfAbsent(
                          id,
                          saved
                              .map(d -> new Slot(d.revision(), new PersonalState(d.value())))
                              .orElseGet(() -> new Slot(0, defaults(p))));
                    }));
  }

  void unload(UUID id) {
    slots.remove(id);
    loading.remove(id);
    views.remove(id);
  }

  /** A new player starts with the default preset, minus the lines that are unusable for them. */
  private PersonalState defaults(Player p) {
    var state = new PersonalState();
    var c = catalog;
    var preset = c.presets().get(c.defaultPreset());
    if (preset != null) state.replaceWith(resolve(preset, p));
    return state;
  }

  /** Writes the latest choice; saves run one after another so revisions never clash. */
  private void persist(Player p, Slot slot) {
    UUID id = p.getUniqueId();
    slot.tail =
        slot.tail
            .handle((value, error) -> null)
            .thenCompose(
                ignored -> {
                  long expected;
                  PersonalState.Stored snapshot;
                  synchronized (slot) {
                    expected = slot.revision;
                    snapshot = slot.state.stored();
                  }
                  return store
                      .save(KIND, id.toString(), expected, snapshot, id.toString())
                      .thenAccept(
                          revision -> {
                            synchronized (slot) {
                              slot.revision = revision;
                            }
                          });
                })
            .whenComplete(
                (value, error) -> {
                  if (error != null && !closed)
                    plugin.getLogger().warning("scoreboard: choice not saved for " + p.getName());
                });
  }

  // --------------------------------------------------------- availability

  Status status(Player p, Item item) {
    if (!installed(item)) return Status.MISSING_PLUGIN;
    var req = item.requirement();
    if (req != null) {
      String value = placeholders.papi(p, p, req.placeholder()).strip();
      boolean met =
          req.equals() != null && !req.equals().isBlank()
              ? value.equalsIgnoreCase(req.equals())
              : !value.isBlank();
      if (!met) return Status.REQUIREMENT;
    }
    return Status.AVAILABLE;
  }

  /** Every PlaceholderAPI expansion the line needs is registered; without PlaceholderAPI nothing is blocked. */
  private boolean installed(Item item) {
    if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return true;
    for (String expansion : item.expansions()) if (!PlaceholderAPI.isRegistered(expansion)) return false;
    return true;
  }

  private List<Entry> resolve(PersonalCatalog.Preset preset, Player p) {
    var c = catalog;
    return PersonalCatalog.resolve(
        preset.entries(),
        id -> c.item(id) != null && status(p, c.item(id)) == Status.AVAILABLE,
        id -> c.item(id) == null ? 0 : c.item(id).variants().size(),
        c.max());
  }

  // -------------------------------------------------------------- content

  /** The player's board, or nothing when they chose no usable line (the module then falls back). */
  Optional<ScoreboardModule.Content> content(Player p) {
    var c = catalog;
    var slot = slots.get(p.getUniqueId());
    if (c.isEmpty() || slot == null || c.disabledWorlds().contains(p.getWorld().getName()))
      return Optional.empty();
    slot.state.clampTo(c.max());
    var rows = new ArrayList<Component>();
    for (String id : slot.state.ids()) {
      if (rows.size() >= c.max()) break;
      Item item = c.item(id);
      // A line whose expansion is missing or whose requirement is unmet is skipped, never printed raw.
      if (item == null || status(p, item) != Status.AVAILABLE) continue;
      Component row = placeholders.renderRaw(item.template(slot.state.variant(id)), p, p);
      if (PlainTextComponentSerializer.plainText().serialize(row).isBlank()) continue;
      rows.add(row);
    }
    if (rows.isEmpty()) return Optional.empty();
    var title = placeholders.renderRaw(c.title(), p, p);
    var lines =
        PersonalBoard.assemble(
            title,
            placeholders.renderRaw(c.top(), p, p),
            placeholders.renderRaw(c.separator(), p, p),
            placeholders.renderRaw(c.bottom(), p, p),
            rows);
    return Optional.of(new ScoreboardModule.Content(title, lines));
  }

  /** A player's chosen lines, or null before they have loaded. */
  PersonalState state(UUID player) {
    var slot = slots.get(player);
    return slot == null ? null : slot.state;
  }

  // ----------------------------------------------------------------- menu

  /** The main menu; the player must have loaded their choice. */
  void open(Player p) {
    if (catalog.isEmpty()) throw new IllegalArgumentException("errors.input");
    if (!slots.containsKey(p.getUniqueId())) throw new IllegalArgumentException("errors.starting");
    show(new Menu(p, Kind.MAIN, MENU, -1), PersonalLayout.SIZE);
  }

  /** The line catalog; {@code target} is the slot to replace, or -1 to add a line. */
  private void openCatalog(Player p, int target) {
    views.computeIfAbsent(p.getUniqueId(), id -> new View());
    show(new Menu(p, Kind.CATALOG, CATALOG_MENU, target), PersonalLayout.SIZE);
  }

  private void openPresets(Player p) {
    int count = catalog.presets().size();
    show(new Menu(p, Kind.PRESETS, PRESETS_MENU, -1), PersonalLayout.submenuSize(Math.max(1, count)));
  }

  private void show(Menu menu, int size) {
    menu.inventory = Bukkit.createInventory(menu, size, MenuStyle.title(lang, menu.id, Map.of()));
    draw(menu);
    MenuStyle.open(menu.player, menu.inventory);
  }

  /** Fills the inventory of a menu from the current state; used for opening and for every refresh. */
  private void draw(Menu menu) {
    Player p = menu.player;
    var slot = slots.get(p.getUniqueId());
    menu.clicks.clear();
    MenuStyle.frame(menu.inventory);
    if (slot == null) return;
    // Slot 0 stays the frame glass: the layout menus have no badge head.
    switch (menu.kind) {
      case MAIN -> drawMain(menu, p, slot.state);
      case CATALOG -> drawCatalog(menu, p, slot.state);
      case PRESETS -> drawPresets(menu, p, slot.state);
    }
  }

  private void refresh(Menu menu) {
    draw(menu);
    MenuStyle.apply(menu.player, menu.inventory);
  }

  private void set(Menu menu, int slot, ItemStack item, Consumer<ClickType> click) {
    menu.inventory.setItem(slot, item);
    if (click != null) menu.clicks.put(slot, click);
  }

  private void drawMain(Menu menu, Player p, PersonalState state) {
    var c = catalog;
    if (!c.presets().isEmpty())
      set(menu, 1, presetsButton(), click -> {
        if (click != ClickType.LEFT) return;
        feedback(p, true);
        openPresets(p);
      });
    // The on/off switch sits in the footer's extra slot (size - 4 is moved to size - 3 by the menu style).
    set(menu, menu.inventory.getSize() - 4, switchButton(p, state), click -> toggleBoard(menu, p, state, click));
    int cards = PersonalLayout.cardCount(c.max());
    for (int i = 0; i < cards; i++) {
      int index = i, slot = PersonalLayout.CARD_SLOTS[i];
      Item item = i < state.ids().size() ? c.item(state.ids().get(i)) : null;
      if (item == null) {
        set(
            menu,
            slot,
            MenuStyle.item(
                Material.GRAY_DYE,
                lang.render("sidebar.slot-empty-name", Map.of("n", Integer.toString(i + 1))),
                lines("sidebar.slot-empty-lore", Map.of())),
            click -> {
              // Left click opens the catalog too, for clients without a middle button.
              if (click != ClickType.MIDDLE && click != ClickType.LEFT) return;
              feedback(p, true);
              openCatalog(p, -1);
            });
        continue;
      }
      set(menu, slot, card(p, item, index, state), click -> {
        if (click == ClickType.MIDDLE) {
          feedback(p, true);
          openCatalog(p, index);
          return;
        }
        boolean changed =
            switch (click) {
              case LEFT -> state.moveUp(item.id());
              case RIGHT -> state.moveDown(item.id());
              case SHIFT_RIGHT -> state.disable(item.id());
              case SHIFT_LEFT -> cycle(state, item);
              default -> false;
            };
        changed(menu, p, changed);
      });
    }
  }

  private void toggleBoard(Menu menu, Player p, PersonalState state, ClickType click) {
    if (!simple(click)) return;
    if (state.ids().isEmpty()) {
      feedback(p, false);
      return;
    }
    host.setEnabled(p, !host.enabledFor(p.getUniqueId()))
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      if (!p.isOnline()) return;
                      if (error != null) lang.send(p, "errors.storage");
                      else {
                        feedback(p, true);
                        if (p.getOpenInventory().getTopInventory() == menu.inventory) refresh(menu);
                      }
                    }));
  }

  /** The quick layouts as small buttons in one centered row. */
  private void drawPresets(Menu menu, Player p, PersonalState state) {
    var presets = new ArrayList<>(catalog.presets().values());
    int[] positions = PersonalLayout.submenuSlots(menu.inventory.getSize(), presets.size());
    for (int i = 0; i < positions.length; i++) {
      var preset = presets.get(i);
      var resolved = resolve(preset, p);
      set(menu, positions[i], presetButton(preset, resolved, state), click -> {
        if (click != ClickType.LEFT) return;
        boolean changed = !resolved.isEmpty() && !PersonalCatalog.sameSet(state.entries(), resolved);
        if (changed) {
          state.replaceWith(resolved);
          saved(p);
        }
        feedback(p, changed);
        if (changed) open(p);
      });
    }
  }

  /** The catalog: filter and sort in the header, the lines in the work zone, 21 per page. */
  private void drawCatalog(Menu menu, Player p, PersonalState state) {
    var c = catalog;
    var view = views.computeIfAbsent(p.getUniqueId(), id -> new View());
    var categoryIds = new ArrayList<String>();
    categoryIds.add("");
    categoryIds.addAll(c.categories().keySet());
    if (!categoryIds.contains(view.filter)) view.filter = "";
    var labels = new ArrayList<String>();
    for (String id : categoryIds)
      labels.add(id.isEmpty() ? lang.text("sidebar.filter-all") : plain(c.categories().get(id).name()));
    int filterIndex = categoryIds.indexOf(view.filter);
    set(menu, 1, cycleButton("filter", Material.HOPPER, labels, filterIndex), click -> {
      if (!simple(click)) return;
      view.filter = categoryIds.get(CatalogView.step(filterIndex, categoryIds.size(), click == ClickType.LEFT));
      menu.page = 0;
      feedback(p, true);
      refresh(menu);
    });
    var sorts = CatalogView.Sort.values();
    var sortLabels = new ArrayList<String>();
    for (var sort : sorts) sortLabels.add(lang.text("sidebar.sort-" + sort.name().toLowerCase(Locale.ROOT)));
    set(menu, 2, cycleButton("sort", Material.COMPARATOR, sortLabels, view.sort.ordinal()), click -> {
      if (!simple(click)) return;
      view.sort = CatalogView.step(view.sort, sorts, click == ClickType.LEFT);
      menu.page = 0;
      feedback(p, true);
      refresh(menu);
    });

    var categoryOrder = new ArrayList<>(c.categories().keySet());
    var rows = new ArrayList<CatalogView.Row>();
    for (Item item : c.items().values())
      rows.add(
          new CatalogView.Row(
              item.id(),
              plain(item.name()),
              item.category(),
              categoryOrder.indexOf(item.category()),
              state.ids().indexOf(item.id()),
              status(p, item) == Status.AVAILABLE));
    var sorted = CatalogView.apply(rows, view.filter, view.sort);
    menu.page = PersonalLayout.clampPage(menu.page, sorted.size());
    int pages = PersonalLayout.pageCount(sorted.size());
    int[] range = PersonalLayout.pageRange(sorted.size(), menu.page);
    for (int i = range[0]; i < range[1]; i++) {
      Item item = c.item(sorted.get(i));
      if (item == null) continue;
      set(menu, PersonalLayout.FLAT_SLOTS[i - range[0]], catalogItem(p, item, state, menu.target), click -> {
        boolean changed;
        if (click == ClickType.SHIFT_LEFT) changed = cycle(state, item);
        else if (click == ClickType.LEFT) {
          changed = choose(p, state, item, menu.target);
          if (changed) {
            feedback(p, true);
            saved(p);
            open(p);
            return;
          }
        } else changed = false;
        changed(menu, p, changed);
      });
    }
    if (menu.page > 0)
      set(
          menu,
          PersonalLayout.PAGE_PREV,
          MenuStyle.button(lang, Material.ARROW, "gui.previous", "gui.click.open"),
          click -> page(menu, p, click, -1));
    if (menu.page < pages - 1)
      set(
          menu,
          PersonalLayout.PAGE_NEXT,
          MenuStyle.button(lang, Material.ARROW, "common.next", "gui.click.open"),
          click -> page(menu, p, click, 1));
  }

  /**
   * Applies a pick from the catalog. Replacing a slot puts the line there (a line that already stands
   * in another slot swaps places with it); adding appends, and picking a line that is already on the
   * board removes it.
   */
  private boolean choose(Player p, PersonalState state, Item item, int target) {
    if (target >= 0 && target < state.ids().size()) {
      int at = state.ids().indexOf(item.id());
      if (at == target) return false;
      if (status(p, item) != Status.AVAILABLE) return false;
      return at >= 0 ? state.swap(at, target) : state.replaceAt(target, item.id());
    }
    return state.has(item.id()) ? state.disable(item.id()) : enable(p, state, item);
  }

  private void page(Menu menu, Player p, ClickType click, int delta) {
    if (!simple(click)) return;
    menu.page += delta;
    feedback(p, true);
    refresh(menu);
  }

  private boolean enable(Player p, PersonalState state, Item item) {
    return status(p, item) == Status.AVAILABLE && state.enable(item.id(), catalog.max());
  }

  private boolean cycle(PersonalState state, Item item) {
    if (item.variants().size() <= 1 || !state.has(item.id())) return false;
    state.cycleVariant(item.id(), item.variants().size());
    return true;
  }

  private void changed(Menu menu, Player p, boolean changed) {
    feedback(p, changed);
    if (!changed) return;
    saved(p);
    refresh(menu);
  }

  private void saved(Player p) {
    var slot = slots.get(p.getUniqueId());
    if (slot != null) persist(p, slot);
  }

  private static boolean simple(ClickType click) {
    return click == ClickType.LEFT || click == ClickType.RIGHT;
  }

  /** Every click gets a sound; a silent no-op reads as a broken button. */
  private void feedback(Player p, boolean changed) {
    if (changed) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.4f);
    else p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
  }

  // ----------------------------------------------------------------- items

  /** Config text in the legacy {@code &} style as a component. */
  private Component legacy(String text) {
    try {
      return mini.deserialize(TabSyntax.legacy(text)).decoration(
          net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    } catch (RuntimeException error) {
      return Component.text(text);
    }
  }

  private String plain(String legacyText) {
    return PlainTextComponentSerializer.plainText().serialize(legacy(legacyText));
  }

  private Component line(String key, Map<String, String> vars) {
    return lang.render(key, vars);
  }

  private List<Component> lines(String key, Map<String, String> vars) {
    return lang.lines(key).stream().map(text -> lang.renderText(text, vars)).toList();
  }

  private ItemStack switchButton(Player p, PersonalState state) {
    String key =
        state.ids().isEmpty()
            ? "empty"
            : host.enabledFor(p.getUniqueId()) ? "on" : "off";
    return MenuStyle.item(
        key.equals("on") ? Material.LIME_DYE : key.equals("off") ? Material.RED_DYE : Material.BARRIER,
        line("sidebar.toggle-" + key, Map.of()),
        lines("sidebar.toggle-" + key + "-lore", Map.of()));
  }

  private ItemStack presetButton(PersonalCatalog.Preset preset, List<Entry> resolved, PersonalState state) {
    var c = catalog;
    var lore = new ArrayList<Component>();
    for (String text : preset.lore()) lore.add(legacy(text));
    if (!preset.lore().isEmpty()) lore.add(Component.empty());
    lore.add(line("sidebar.preset-header", Map.of()));
    for (Entry entry : preset.entries()) {
      Item item = c.item(entry.id());
      if (item == null) continue;
      boolean used = resolved.stream().anyMatch(r -> r.id().equals(entry.id()));
      lore.add(
          line(
              used ? "sidebar.preset-line" : "sidebar.preset-line-skipped",
              Map.of("name", plain(item.name()))));
    }
    lore.add(lang.render("gui.separator"));
    String name = plain(preset.name());
    if (resolved.isEmpty()) {
      lore.add(line("sidebar.preset-unavailable", Map.of()));
      lore.add(line("sidebar.preset-unavailable-reason", Map.of()));
      return MenuStyle.item(Material.BARRIER, Component.text(name, NamedTextColor.DARK_GRAY), lore);
    }
    if (PersonalCatalog.sameSet(state.entries(), resolved)) {
      lore.add(line("sidebar.preset-active", Map.of()));
      return MenuStyle.item(Material.LIME_DYE, Component.text(name, NamedTextColor.GREEN), lore);
    }
    lore.add(line("sidebar.preset-click", Map.of()));
    return MenuStyle.item(Material.BOOK, Component.text(name, NamedTextColor.YELLOW), lore);
  }

  private List<Component> description(Item item) {
    var out = new ArrayList<Component>();
    for (String text : item.lore()) out.add(legacy(text));
    return out;
  }

  /** Name and description of a line that cannot be used, with the reason and the trailing actions. */
  private ItemStack locked(Item item, Status status, List<Component> trailing) {
    var lore = description(item);
    lore.add(lang.render("gui.separator"));
    lore.add(line("sidebar.unavailable", Map.of()));
    if (status == Status.MISSING_PLUGIN) lore.add(line("sidebar.reason-plugin", Map.of()));
    else if (item.requirement() != null && !item.requirement().reason().isBlank())
      lore.add(legacy(item.requirement().reason()));
    lore.addAll(trailing);
    return MenuStyle.item(Material.BARRIER, Component.text(plain(item.name()), NamedTextColor.DARK_GRAY), lore);
  }


  private ItemStack presetsButton() {
    return MenuStyle.item(
        Material.BOOK,
        line("sidebar.presets-title-button", Map.of()),
        lines("sidebar.presets-lore", Map.of("count", Integer.toString(catalog.presets().size()))));
  }

  /** A filter or sort switch: the current option rides in the name, all options are listed in the lore. */
  private ItemStack cycleButton(String kind, Material icon, List<String> labels, int current) {
    var lore = new ArrayList<Component>();
    lore.add(line("sidebar.cycle-subtitle", Map.of()));
    lore.add(Component.empty());
    lore.add(line("sidebar.cycle-options", Map.of()));
    for (int i = 0; i < labels.size(); i++)
      lore.add(line(i == current ? "sidebar.cycle-current" : "sidebar.cycle-other", Map.of("name", labels.get(i))));
    lore.add(lang.render("gui.separator"));
    lore.add(line("sidebar.cycle-next", Map.of()));
    lore.add(line("sidebar.cycle-previous", Map.of()));
    return MenuStyle.item(
        icon,
        line("sidebar.cycle-name", Map.of("name", lang.text("sidebar." + kind + "-title"), "current", labels.get(current))),
        lore);
  }

  /** A line of the catalog: its state, where it stands on the board and what a click does. */
  private ItemStack catalogItem(Player p, Item item, PersonalState state, int target) {
    var category = catalog.categories().get(item.category());
    boolean active = state.has(item.id());
    var status = status(p, item);
    var info = new ArrayList<Component>();
    if (category != null)
      info.add(line("sidebar.catalog-category", Map.of("category", plain(category.name()))));
    if (active)
      info.add(line("sidebar.catalog-slot", Map.of("n", Integer.toString(state.ids().indexOf(item.id()) + 1))));
    var actions = new ArrayList<Component>();
    boolean replacing = target >= 0 && target < state.ids().size();
    boolean full = !active && !replacing && state.ids().size() >= catalog.max();
    if (status == Status.AVAILABLE && !full) {
      int at = state.ids().indexOf(item.id());
      if (replacing && at == target) actions.add(line("sidebar.catalog-here", Map.of()));
      else if (replacing && at >= 0)
        actions.add(line("sidebar.catalog-swap", Map.of("n", Integer.toString(at + 1))));
      else if (replacing) actions.add(line("sidebar.catalog-replace", Map.of("n", Integer.toString(target + 1))));
      else actions.add(line(active ? "sidebar.catalog-remove" : "sidebar.catalog-add", Map.of()));
      if (active && item.variants().size() > 1)
        actions.add(
            line("sidebar.slot-variant", Map.of("variant", plain(item.variantName(state.variant(item.id()))))));
    }
    if (status != Status.AVAILABLE) {
      var locked = new ArrayList<Component>(info);
      locked.addAll(actions);
      return locked(item, status, locked);
    }
    var lore = description(item);
    lore.add(lang.render("gui.separator"));
    lore.addAll(info);
    if (full) {
      lore.add(line("sidebar.unavailable", Map.of()));
      lore.add(line("sidebar.reason-full", Map.of("max", Integer.toString(catalog.max()))));
    }
    lore.addAll(actions);
    return MenuStyle.item(
        active ? Material.LIME_DYE : full ? Material.BARRIER : Material.GRAY_DYE,
        Component.text(
            plain(item.name()), active ? NamedTextColor.GREEN : full ? NamedTextColor.DARK_GRAY : NamedTextColor.GRAY),
        lore);
  }

  private ItemStack card(Player p, Item item, int index, PersonalState state) {
    var actions = new ArrayList<Component>();
    if (index > 0) actions.add(line("sidebar.slot-up", Map.of()));
    if (index < state.ids().size() - 1) actions.add(line("sidebar.slot-down", Map.of()));
    if (item.variants().size() > 1)
      actions.add(
          line("sidebar.slot-variant", Map.of("variant", plain(item.variantName(state.variant(item.id()))))));
    actions.add(line("sidebar.slot-catalog", Map.of()));
    actions.add(line("sidebar.slot-remove", Map.of()));
    var status = status(p, item);
    if (status != Status.AVAILABLE) return locked(item, status, actions);
    var lore = description(item);
    lore.add(lang.render("gui.separator"));
    lore.add(line("sidebar.slot-label", Map.of("n", Integer.toString(index + 1))));
    lore.addAll(actions);
    return MenuStyle.item(Material.LIME_DYE, Component.text(plain(item.name()), NamedTextColor.GREEN), lore);
  }

  // ---------------------------------------------------------------- events

  @EventHandler
  public void click(InventoryClickEvent event) {
    if (!(event.getInventory().getHolder() instanceof Menu menu)) return;
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player p)
        || event.getRawSlot() < 0
        || event.getRawSlot() >= menu.inventory.getSize()) return;
    var action = menu.clicks.get(event.getRawSlot());
    if (action == null) return;
    ClickType click = event.getClick();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (p.getOpenInventory().getTopInventory() != menu.inventory) return;
              try {
                MenuStyle.action(p, () -> action.accept(click));
              } catch (RuntimeException error) {
                lang.error(p, error);
              }
            });
  }

  @EventHandler
  public void drag(InventoryDragEvent event) {
    if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
  }

  @Override
  public void close() {
    closed = true;
    org.bukkit.event.HandlerList.unregisterAll(this);
    slots.clear();
    loading.clear();
  }
}
