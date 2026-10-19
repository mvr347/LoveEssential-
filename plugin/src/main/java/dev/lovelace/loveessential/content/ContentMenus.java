package dev.lovelace.loveessential.content;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.condition.ActionEngine;
import dev.lovelace.loveessential.gui.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;

/**
 * Editors copy the cursor/hand item. No inventory movement, including shift/number/drag, is
 * allowed.
 */
public final class ContentMenus implements Listener, AutoCloseable {
  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final Lang lang;
  private final LoveBridge love;
  private final ContentLibrary library;
  private final StarterItems starter;
  private final ControlMenus controls;
  private final ActionEngine actions;
  private final dev.lovelace.loveessential.tab.Placeholders placeholders;
  private final Map<UUID, Object> drafts = new HashMap<>();
  private final List<LoveEssentialService.Registration> registrations = new ArrayList<>();
  private static final int[] GRID = {19, 20, 21, 28, 29, 30, 37, 38, 39};

  private static final class View implements InventoryHolder {
    Inventory inventory;
    final Predicate<Player> allowed;
    final Map<Integer, Consumer<InventoryClickEvent>> clicks = new HashMap<>();

    View(Predicate<Player> allowed) {
      this.allowed = allowed;
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  private static final class MenuDraft {
    final String id, token = UUID.randomUUID().toString();
    long revision;
    String title, permission;
    final Map<Integer, ContentLibrary.Button> buttons = new HashMap<>();
    final Map<String, String> texts = new HashMap<>();
    boolean saving;

    MenuDraft(ContentLibrary.Menu m, long rev) {
      id = m.id();
      title = m.titleKey();
      permission = m.permission();
      buttons.putAll(m.buttons());
      revision = rev;
    }

    ContentLibrary.Menu value() {
      return new ContentLibrary.Menu(id, title, permission, buttons);
    }
  }

  private static final class CraftDraft {
    final String id, token = UUID.randomUUID().toString();
    long revision;
    boolean enabled = true, shapeless, exact = true, saving;
    String permission = "";
    final List<ContentItems> ingredients = new ArrayList<>(Collections.nCopies(9, null));
    ContentItems result;
    final Map<String, String> texts = new HashMap<>();

    CraftDraft(String id) {
      this.id = id;
    }

    CraftDraft(ContentLibrary.Craft c, long rev) {
      this(c.id());
      revision = rev;
      enabled = c.enabled();
      shapeless = c.shapeless();
      exact = c.exact();
      permission = c.permission();
      ingredients.clear();
      ingredients.addAll(c.ingredients());
      result = c.result();
    }

    ContentLibrary.Craft value() {
      return new ContentLibrary.Craft(
          id, enabled, shapeless, exact, permission, ingredients, result);
    }
  }

  private static final class KitDraft {
    final String token = UUID.randomUUID().toString();
    final long revision;
    final String source;
    boolean enabled, saving;
    final List<StarterItems.Entry> items = new ArrayList<>();
    final Map<String, String> texts = new HashMap<>();

    KitDraft(SqliteStore.Document<StarterItems.Kit> d) {
      revision = d.revision();
      source = d.value().source();
      enabled = d.value().enabled();
      items.addAll(d.value().items());
    }
  }

  public ContentMenus(
      LoveEssentialPlugin plugin,
      MainThread main,
      Lang lang,
      LoveBridge love,
      ContentLibrary library,
      StarterItems starter,
      ControlMenus controls,
      ActionEngine actions,
      dev.lovelace.loveessential.tab.Placeholders placeholders) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.love = love;
    this.library = library;
    this.starter = starter;
    this.controls = controls;
    this.actions = actions;
    this.placeholders = placeholders;
    Bukkit.getPluginManager().registerEvents(this, plugin);
    registrations.add(
        actions.register(
            "menu",
            (p, a) -> {
              open(p, a.get("id"));
              return true;
            }));
    registrations.add(
        actions.register(
            "starter",
            (p, a) -> {
              starter.claim(p, true);
              return true;
            }));
    registrations.add(
        actions.register(
            "recipes",
            (p, a) -> {
              list(p, true, 0);
              return true;
            }));
  }

  private boolean editor(Player p, String module) {
    return plugin.module(module) && p.hasPermission("loveessential.content.edit");
  }

  private void requireEditor(Player p, String module) {
    if (!editor(p, module)) throw new IllegalArgumentException("errors.permission");
  }

  private View view(Player p, String title, Map<String, String> vars, Predicate<Player> allowed) {
    if (!plugin.ready() || !love.authenticated(p) || !allowed.test(p))
      throw new IllegalArgumentException("errors.permission");
    controls.clearInput(p);
    var v = new View(allowed);
    v.inventory = Bukkit.createInventory(v, 54, lang.render(title, vars));
    MenuStyle.frame(v.inventory);
    put(v, 52, Material.BARRIER, "gui.close", e -> p.closeInventory());
    return v;
  }

  private void put(
      View v, int slot, Material icon, String key, Consumer<InventoryClickEvent> click) {
    v.inventory.setItem(
        slot,
        MenuStyle.button(
            lang, icon, key, lang.contains(key + "-help") ? key + "-help" : "gui.click.open"));
    v.clicks.put(slot, click);
  }

  private void item(View v, int slot, ItemStack item, Consumer<InventoryClickEvent> click) {
    v.inventory.setItem(slot, item.clone());
    v.clicks.put(slot, click);
  }

  private void nav(View v, Player p, int page, int count, IntConsumer pages, Runnable back) {
    if (page > 0) put(v, 36, Material.ARROW, "gui.previous", e -> pages.accept(page - 1));
    if ((page + 1) * 21 < count)
      put(v, 44, Material.ARROW, "common.next", e -> pages.accept(page + 1));
    if (back != null) put(v, 51, Material.ARROW, "common.back", e -> back.run());
  }

  private void ask(
      Player p,
      String field,
      String example,
      String current,
      Object draft,
      Consumer<String> input,
      Runnable back) {
    controls.request(
        p,
        "content.prompts." + field,
        example,
        current,
        who ->
            editor(
                    who,
                    draft instanceof CraftDraft
                        ? "crafting"
                        : draft instanceof KitDraft ? "starter" : "customgui")
                && (draft == null || drafts.get(who.getUniqueId()) == draft),
        value -> {
          if (value.length() > 2048) throw new IllegalArgumentException("errors.input");
          input.accept(value);
        },
        back);
  }

  private String prefix(String type, String id) {
    return "content-text." + type + "." + id + "." + UUID.randomUUID();
  }

  private ItemStack selected(Player p, InventoryClickEvent e, boolean one) {
    var i = e.getCursor();
    if (i == null || i.getType().isAir()) i = p.getInventory().getItemInMainHand();
    if (i == null || i.getType().isAir()) throw new IllegalArgumentException("content.hold-item");
    var copy = i.clone();
    if (one) copy.setAmount(1);
    return copy;
  }

  private void error(Player p, Throwable failure) {
    while (failure.getCause() != null) failure = failure.getCause();
    String key = failure.getMessage();
    lang.send(
        p,
        failure instanceof SqliteStore.Conflict
            ? "errors.conflict"
            : lang.contains(key) ? key : "errors.storage");
  }

  private void complete(Player p, CompletableFuture<Void> future, Runnable success, Runnable done) {
    future.whenComplete(
        (v, ex) ->
            main.execute(
                () -> {
                  done.run();
                  if (!p.isOnline()) return;
                  if (ex != null) error(p, ex);
                  else {
                    lang.send(p, "common.saved");
                    success.run();
                  }
                }));
  }

  private void confirm(Player p, Runnable action, Runnable back, Predicate<Player> allowed) {
    var v = view(p, "content.confirm-delete", Map.of(), allowed);
    put(
        v,
        20,
        Material.LIME_CONCRETE,
        "gui.confirm",
        e -> {
          p.closeInventory();
          action.run();
        });
    put(v, 24, Material.RED_CONCRETE, "gui.cancel", e -> back.run());
    p.openInventory(v.inventory);
  }

  public void command(Player p, String command, String[] args) {
    if (command.equals("starter")) {
      if (!plugin.module("starter")) throw new IllegalArgumentException("errors.module-disabled");
      if (args.length == 0 || args.length == 1 && args[0].equals("preview")) previewKit(p, 0);
      else if (args.length == 1 && args[0].equals("claim")) starter.claim(p, true);
      else if (args.length == 1 && args[0].equals("edit")) {
        requireEditor(p, "starter");
        var d = new KitDraft(starter.kit());
        drafts.put(p.getUniqueId(), d);
        kitEditor(p, d, 0);
      } else throw new IllegalArgumentException("content.usage");
      return;
    }
    boolean recipes = command.equals("recipes");
    String module = recipes ? "crafting" : "customgui";
    if (!plugin.module(module)) throw new IllegalArgumentException("errors.module-disabled");
    if (args.length == 0 || args.length == 1 && args[0].equals("list")) {
      list(p, recipes, 0);
      return;
    }
    if (!recipes && args.length == 1) {
      open(p, args[0]);
      return;
    }
    if (args.length != 2) throw new IllegalArgumentException("content.usage");
    String op = args[0], id = args[1];
    ContentLibrary.id(id);
    if (op.equals("open") || op.equals("preview")) {
      if (recipes) previewRecipe(p, id);
      else open(p, id);
      return;
    }
    requireEditor(p, module);
    if (op.equals("delete")) {
      long revision =
          recipes
              ? library
                  .craft(id)
                  .orElseThrow(() -> new IllegalArgumentException("errors.not-found"))
                  .revision()
              : library
                  .menu(id)
                  .orElseThrow(() -> new IllegalArgumentException("errors.not-found"))
                  .revision();
      confirm(
          p,
          () ->
              complete(
                  p,
                  library.delete(recipes, id, revision, p.getUniqueId().toString()),
                  () -> list(p, recipes, 0),
                  () -> {}),
          () -> list(p, recipes, 0),
          who -> editor(who, module));
      return;
    }
    if (!Set.of("create", "edit").contains(op)) throw new IllegalArgumentException("content.usage");
    if (recipes) {
      var saved = library.craft(id);
      if (op.equals("create") && saved.isPresent())
        throw new IllegalArgumentException("errors.conflict");
      if (op.equals("edit") && saved.isEmpty())
        throw new IllegalArgumentException("errors.not-found");
      var d =
          saved
              .map(v -> new CraftDraft(v.value(), v.revision()))
              .orElseGet(() -> new CraftDraft(id));
      drafts.put(p.getUniqueId(), d);
      craftEditor(p, d);
    } else {
      var saved = library.menu(id);
      if (op.equals("create") && saved.isPresent())
        throw new IllegalArgumentException("errors.conflict");
      if (op.equals("edit") && saved.isEmpty())
        throw new IllegalArgumentException("errors.not-found");
      var d =
          saved
              .map(v -> new MenuDraft(v.value(), v.revision()))
              .orElseGet(() -> new MenuDraft(new ContentLibrary.Menu(id, "", "", Map.of()), 0));
      if (d.title.isEmpty()) {
        d.title = prefix("menus", id) + ".title";
        d.texts.put(d.title, lang.text("content.new-title"));
      }
      drafts.put(p.getUniqueId(), d);
      menuEditor(p, d);
    }
  }

  public List<String> suggestions(Player p, String command, String[] args) {
    String module =
        command.equals("starter")
            ? "starter"
            : command.equals("recipes") ? "crafting" : "customgui";
    if (!plugin.module(module)) return List.of();
    if (command.equals("starter"))
      return args.length == 1
          ? (editor(p, module) ? List.of("preview", "claim", "edit") : List.of("preview", "claim"))
          : List.of();
    boolean recipes = command.equals("recipes");
    if (args.length == 1) {
      var out = new ArrayList<>(List.of("list", "open"));
      if (editor(p, module)) out.addAll(List.of("create", "edit", "delete"));
      if (!recipes) out.addAll(library.ids(false));
      return out;
    }
    if (args.length == 2 && Set.of("open", "edit", "delete", "preview").contains(args[0]))
      return library.ids(recipes);
    return List.of();
  }

  public void list(Player p, boolean recipes, int page) {
    String module = recipes ? "crafting" : "customgui";
    var ids =
        library.ids(recipes).stream()
            .filter(
                id ->
                    recipes
                        ? library.allowed(p, library.craft(id).orElseThrow().value())
                            || editor(p, module)
                        : library.allowed(p, library.menu(id).orElseThrow().value())
                            || editor(p, module))
            .toList();
    int actual = Math.max(0, Math.min(page, Math.max(0, (ids.size() - 1) / 21)));
    var v =
        view(
            p,
            recipes ? "content.recipes-title" : "content.menus-title",
            Map.of(),
            who -> plugin.module(module));
    for (int i = actual * 21; i < Math.min(ids.size(), (actual + 1) * 21); i++) {
      String id = ids.get(i);
      var icon =
          MenuStyle.item(
              recipes ? Material.CRAFTING_TABLE : Material.CHEST,
              lang.render("content.entry", Map.of("id", id)),
              List.of(
                  lang.render(
                      editor(p, module) ? "content.entry-admin-help" : "content.entry-help")));
      item(
          v,
          MenuStyle.slot(i % 21, Math.min(21, ids.size() - actual * 21)),
          icon,
          e -> {
            if (e.isRightClick() && editor(p, module))
              command(p, recipes ? "recipes" : "customgui", new String[] {"edit", id});
            else if (recipes) previewRecipe(p, id);
            else open(p, id);
          });
    }
    if (editor(p, module))
      put(
          v,
          50,
          Material.EMERALD,
          "content.create",
          e ->
              ask(
                  p,
                  "id",
                  "market",
                  "",
                  null,
                  id -> command(p, recipes ? "recipes" : "customgui", new String[] {"create", id}),
                  () -> list(p, recipes, actual)));
    nav(v, p, actual, ids.size(), n -> list(p, recipes, n), null);
    p.openInventory(v.inventory);
  }

  public void open(Player p, String id) {
    var saved =
        library.menu(id).orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
    var m = saved.value();
    var v =
        view(
            p,
            m.titleKey(),
            Map.of("player", p.getName()),
            who ->
                library
                    .menu(id)
                    .map(d -> d.revision() == saved.revision() && library.allowed(who, d.value()))
                    .orElse(false));
    for (var entry : m.buttons().entrySet()) {
      var button = entry.getValue();
      if (!library.allowed(p, button)) continue;
      item(
          v,
          entry.getKey(),
          personalize(p, button.icon()),
          e -> {
            if (!library.allowed(p, button)) throw new IllegalArgumentException("errors.access");
            actions.execute(p, e.isRightClick() ? button.right() : button.left());
          });
    }
    p.openInventory(v.inventory);
  }

  private ItemStack personalize(Player p, ContentItems data) {
    var item = data.item(lang);
    var meta = item.getItemMeta();
    if (!data.nameKey().isEmpty()) meta.displayName(placeholders.render(data.nameKey(), p, p));
    if (!data.loreKeys().isEmpty())
      meta.lore(data.loreKeys().stream().map(k -> placeholders.render(k, p, p)).toList());
    item.setItemMeta(meta);
    return item;
  }

  private boolean active(Player p, Object draft, String module) {
    return drafts.get(p.getUniqueId()) == draft && editor(p, module);
  }

  private void menuEditor(Player p, MenuDraft d) {
    var v =
        view(
            p,
            "content.menu-editor",
            Map.of("id", d.id),
            who -> active(who, d, "customgui") && !d.saving);
    for (int slot : MenuStyle.CONTENT) {
      var b = d.buttons.get(slot);
      var icon =
          b == null
              ? MenuStyle.button(
                  lang,
                  Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                  "content.empty-button",
                  "content.empty-button-help")
              : preview(b.icon(), d.texts);
      item(
          v,
          slot,
          icon,
          e -> {
            if (e.isRightClick()) {
              d.buttons.remove(slot);
              menuEditor(p, d);
            } else {
              if (!d.buttons.containsKey(slot))
                d.buttons.put(
                    slot,
                    new ContentLibrary.Button(
                        ContentItems.capture(selected(p, e, true), prefix("menus", d.id), d.texts),
                        Condition.always(),
                        List.of(),
                        List.of()));
              buttonEditor(p, d, slot);
            }
          });
    }
    put(
        v,
        1,
        Material.NAME_TAG,
        "content.title",
        e ->
            ask(
                p,
                "title",
                "<gold>Магазин</gold>",
                d.texts.getOrDefault(d.title, lang.text(d.title)),
                d,
                value -> {
                  d.title = prefix("menus", d.id) + ".title";
                  d.texts.put(d.title, value);
                  menuEditor(p, d);
                },
                () -> menuEditor(p, d)));
    put(
        v,
        3,
        Material.TRIPWIRE_HOOK,
        "content.permission",
        e ->
            permission(
                p,
                d,
                d.permission,
                value -> {
                  d.permission = value;
                  menuEditor(p, d);
                },
                () -> menuEditor(p, d)));
    put(v, 5, Material.ENDER_EYE, "content.preview", e -> previewMenu(p, d));
    put(
        v,
        7,
        Material.TNT,
        "content.delete",
        e -> {
          if (d.revision == 0) {
            drafts.remove(p.getUniqueId());
            list(p, false, 0);
          } else
            confirm(
                p,
                () ->
                    complete(
                        p,
                        library.delete(false, d.id, d.revision, p.getUniqueId().toString()),
                        () -> {
                          drafts.remove(p.getUniqueId(), d);
                          list(p, false, 0);
                        },
                        () -> {}),
                () -> menuEditor(p, d),
                who -> active(who, d, "customgui"));
        });
    put(
        v,
        50,
        Material.LIME_DYE,
        "content.save",
        e -> {
          d.saving = true;
          complete(
              p,
              library.save(d.value(), d.revision, p.getUniqueId().toString(), d.texts),
              () -> {
                drafts.remove(p.getUniqueId(), d);
                list(p, false, 0);
              },
              () -> d.saving = false);
        });
    nav(v, p, 0, 0, n -> {}, () -> list(p, false, 0));
    p.openInventory(v.inventory);
  }

  private void previewMenu(Player p, MenuDraft d) {
    var v =
        view(p, "content.preview-title", Map.of("id", d.id), who -> active(who, d, "customgui"));
    d.buttons.forEach((slot, b) -> item(v, slot, preview(b.icon(), d.texts), e -> {}));
    nav(v, p, 0, 0, n -> {}, () -> menuEditor(p, d));
    p.openInventory(v.inventory);
  }

  private ItemStack preview(ContentItems data, Map<String, String> texts) {
    var item = data.item(lang);
    var meta = item.getItemMeta();
    var mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
    if (texts.containsKey(data.nameKey()))
      meta.displayName(mm.deserialize(texts.get(data.nameKey())));
    if (!data.loreKeys().isEmpty())
      meta.lore(
          data.loreKeys().stream()
              .map(k -> mm.deserialize(texts.getOrDefault(k, lang.text(k))))
              .toList());
    item.setItemMeta(meta);
    return item;
  }

  private void updateButton(
      MenuDraft d,
      int slot,
      ContentItems icon,
      Condition condition,
      List<Action> left,
      List<Action> right) {
    d.buttons.put(slot, new ContentLibrary.Button(icon, condition, left, right));
  }

  private void buttonEditor(Player p, MenuDraft d, int slot) {
    var b = d.buttons.get(slot);
    var v =
        view(
            p,
            "content.button-editor",
            Map.of("id", d.id, "slot", "" + slot),
            who -> active(who, d, "customgui") && !d.saving);
    item(
        v,
        22,
        preview(b.icon(), d.texts),
        e -> {
          var icon = ContentItems.capture(selected(p, e, true), prefix("menus", d.id), d.texts);
          updateButton(d, slot, icon, b.condition(), b.left(), b.right());
          buttonEditor(p, d, slot);
        });
    put(
        v,
        19,
        Material.NAME_TAG,
        "content.button-name",
        e ->
            ask(
                p,
                "button-name",
                "<yellow>Открыть магазин</yellow>",
                d.texts.getOrDefault(b.icon().nameKey(), ""),
                d,
                value -> {
                  String key = prefix("menus", d.id) + ".name";
                  d.texts.put(key, value);
                  updateButton(
                      d,
                      slot,
                      new ContentItems(b.icon().data(), key, b.icon().loreKeys()),
                      b.condition(),
                      b.left(),
                      b.right());
                  buttonEditor(p, d, slot);
                },
                () -> buttonEditor(p, d, slot)));
    put(
        v,
        20,
        Material.WRITABLE_BOOK,
        "content.button-lore",
        e ->
            ask(
                p,
                "lore",
                "<gray>Описание | <green>Нажмите",
                "",
                d,
                value -> {
                  var keys = new ArrayList<String>();
                  String prefix = prefix("menus", d.id);
                  int i = 0;
                  for (String line : value.split("\\|", -1)) {
                    String key = prefix + ".lore." + i++;
                    d.texts.put(key, line.strip());
                    keys.add(key);
                  }
                  updateButton(
                      d,
                      slot,
                      new ContentItems(b.icon().data(), b.icon().nameKey(), keys),
                      b.condition(),
                      b.left(),
                      b.right());
                  buttonEditor(p, d, slot);
                },
                () -> buttonEditor(p, d, slot)));
    put(v, 24, Material.COMPARATOR, "content.left-actions", e -> actionEditor(p, d, slot, false));
    put(v, 25, Material.REPEATER, "content.right-actions", e -> actionEditor(p, d, slot, true));
    put(v, 31, Material.TRIPWIRE_HOOK, "content.condition", e -> conditionEditor(p, d, slot));
    put(
        v,
        33,
        Material.BARRIER,
        "content.clear-actions",
        e -> {
          updateButton(d, slot, b.icon(), Condition.always(), List.of(), List.of());
          buttonEditor(p, d, slot);
        });
    nav(v, p, 0, 0, n -> {}, () -> menuEditor(p, d));
    p.openInventory(v.inventory);
  }

  private void actionEditor(Player p, MenuDraft d, int slot, boolean right) {
    var b = d.buttons.get(slot);
    var selected = right ? b.right() : b.left();
    var v =
        view(p, "content.action-editor", Map.of(), who -> active(who, d, "customgui") && !d.saving);
    String[] types = {"menu", "message", "command", "charge", "sound", "starter", "recipes"};
    for (int i = 0; i < types.length; i++) {
      String type = types[i];
      put(
          v,
          MenuStyle.slot(i, types.length),
          Material.BOOK,
          "content.actions." + type,
          e -> {
            if (selected.size() >= 8) throw new IllegalArgumentException("content.limit");
            Consumer<String> add =
                value -> {
                  Map<String, String> args =
                      switch (type) {
                        case "menu" -> Map.of("id", value);
                        case "message" -> {
                          String key = prefix("menus", d.id) + ".message";
                          d.texts.put(key, value);
                          yield Map.of("key", key);
                        }
                        case "command" -> Map.of("value", value);
                        case "charge" -> {
                          long amount = Long.parseLong(value);
                          if (amount <= 0) throw new IllegalArgumentException("errors.input");
                          yield Map.of("amount", value);
                        }
                        case "sound" -> {
                          Sound.valueOf(value.toUpperCase(Locale.ROOT));
                          yield Map.of("sound", value.toUpperCase(Locale.ROOT));
                        }
                        default -> Map.of();
                      };
                  var list = new ArrayList<>(selected);
                  list.add(new Action(type, args));
                  updateButton(
                      d,
                      slot,
                      b.icon(),
                      b.condition(),
                      right ? b.left() : list,
                      right ? list : b.right());
                  buttonEditor(p, d, slot);
                };
            if (type.equals("starter") || type.equals("recipes")) add.accept("");
            else
              ask(
                  p,
                  "action-" + type,
                  lang.text("content.examples." + type),
                  "",
                  d,
                  add,
                  () -> actionEditor(p, d, slot, right));
          });
    }
    var lore =
        selected.stream()
            .map(
                a ->
                    lang.render(
                        "content.action-summary",
                        Map.of("type", a.type(), "args", a.args().toString())))
            .toList();
    item(v, 31, MenuStyle.item(Material.PAPER, lang.render("content.action-order"), lore), e -> {});
    put(
        v,
        50,
        Material.BARRIER,
        "content.clear-actions",
        e -> {
          updateButton(
              d,
              slot,
              b.icon(),
              b.condition(),
              right ? b.left() : List.of(),
              right ? List.of() : b.right());
          buttonEditor(p, d, slot);
        });
    nav(v, p, 0, 0, n -> {}, () -> buttonEditor(p, d, slot));
    p.openInventory(v.inventory);
  }

  private void conditionEditor(Player p, MenuDraft d, int slot) {
    var b = d.buttons.get(slot);
    var v =
        view(
            p,
            "content.condition-editor",
            Map.of(),
            who -> active(who, d, "customgui") && !d.saving);
    put(
        v,
        20,
        Material.LIME_DYE,
        "content.condition-always",
        e -> {
          updateButton(d, slot, b.icon(), Condition.always(), b.left(), b.right());
          buttonEditor(p, d, slot);
        });
    put(
        v,
        22,
        Material.TRIPWIRE_HOOK,
        "content.permission",
        e ->
            permission(
                p,
                d,
                "",
                value -> {
                  updateButton(
                      d,
                      slot,
                      b.icon(),
                      value.isBlank()
                          ? Condition.always()
                          : new Condition("permission", Map.of("value", value), List.of()),
                      b.left(),
                      b.right());
                  buttonEditor(p, d, slot);
                },
                () -> conditionEditor(p, d, slot)));
    put(
        v,
        24,
        Material.GOLD_INGOT,
        "content.condition-balance",
        e ->
            ask(
                p,
                "balance",
                "100",
                "",
                d,
                value -> {
                  long amount = Long.parseLong(value);
                  if (amount < 0) throw new IllegalArgumentException("errors.input");
                  updateButton(
                      d,
                      slot,
                      b.icon(),
                      new Condition("balance", Map.of("amount", value), List.of()),
                      b.left(),
                      b.right());
                  buttonEditor(p, d, slot);
                },
                () -> conditionEditor(p, d, slot)));
    nav(v, p, 0, 0, n -> {}, () -> buttonEditor(p, d, slot));
    p.openInventory(v.inventory);
  }

  private void permission(
      Player p, Object draft, String current, Consumer<String> input, Runnable back) {
    ask(
        p,
        "permission",
        "server.vip / -",
        current,
        draft,
        value -> input.accept(value.equals("-") ? "" : value),
        back);
  }

  private void craftEditor(Player p, CraftDraft d) {
    var v =
        view(
            p,
            "content.craft-editor",
            Map.of("id", d.id),
            who -> active(who, d, "crafting") && !d.saving);
    for (int i = 0; i < 9; i++) {
      int index = i;
      var data = d.ingredients.get(i);
      item(
          v,
          GRID[i],
          data == null
              ? MenuStyle.button(
                  lang,
                  Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                  "content.ingredient",
                  "content.ingredient-help")
              : preview(data, d.texts),
          e -> {
            d.ingredients.set(
                index,
                e.isRightClick()
                    ? null
                    : ContentItems.capture(selected(p, e, true), prefix("recipes", d.id), d.texts));
            craftEditor(p, d);
          });
    }
    item(
        v,
        24,
        d.result == null
            ? MenuStyle.button(lang, Material.BARRIER, "content.result", "content.result-help")
            : preview(d.result, d.texts),
        e -> {
          d.result =
              e.isRightClick()
                  ? null
                  : ContentItems.capture(selected(p, e, false), prefix("recipes", d.id), d.texts);
          craftEditor(p, d);
        });
    put(
        v,
        1,
        Material.CRAFTING_TABLE,
        d.shapeless ? "content.shapeless" : "content.shaped",
        e -> {
          d.shapeless = !d.shapeless;
          craftEditor(p, d);
        });
    put(
        v,
        3,
        Material.COMPARATOR,
        d.exact ? "content.exact" : "content.material",
        e -> {
          d.exact = !d.exact;
          craftEditor(p, d);
        });
    put(
        v,
        5,
        Material.TRIPWIRE_HOOK,
        "content.permission",
        e ->
            permission(
                p,
                d,
                d.permission,
                value -> {
                  d.permission = value;
                  craftEditor(p, d);
                },
                () -> craftEditor(p, d)));
    put(
        v,
        7,
        Material.LEVER,
        d.enabled ? "gui.enabled" : "gui.disabled",
        e -> {
          d.enabled = !d.enabled;
          craftEditor(p, d);
        });
    put(
        v,
        50,
        Material.LIME_DYE,
        "content.save",
        e -> {
          ContentLibrary.validate(d.value());
          d.saving = true;
          complete(
              p,
              library.save(d.value(), d.revision, p.getUniqueId().toString(), d.texts),
              () -> {
                drafts.remove(p.getUniqueId(), d);
                list(p, true, 0);
              },
              () -> d.saving = false);
        });
    nav(v, p, 0, 0, n -> {}, () -> list(p, true, 0));
    p.openInventory(v.inventory);
  }

  private void previewRecipe(Player p, String id) {
    var c =
        library
            .craft(id)
            .orElseThrow(() -> new IllegalArgumentException("errors.not-found"))
            .value();
    var v =
        view(
            p,
            "content.recipe-preview",
            Map.of("id", id),
            who -> library.allowed(who, c) || editor(who, "crafting"));
    for (int i = 0; i < 9; i++)
      if (c.ingredients().get(i) != null)
        item(v, GRID[i], c.ingredients().get(i).item(lang), e -> {});
    item(v, 24, c.result().item(lang), e -> {});
    put(
        v,
        3,
        Material.CRAFTING_TABLE,
        c.shapeless() ? "content.shapeless" : "content.shaped",
        e -> {});
    if (editor(p, "crafting"))
      put(
          v,
          50,
          Material.COMPARATOR,
          "content.edit",
          e -> command(p, "recipes", new String[] {"edit", id}));
    nav(v, p, 0, 0, n -> {}, () -> list(p, true, 0));
    p.openInventory(v.inventory);
  }

  private void previewKit(Player p, int page) {
    var entries = starter.kit().value().items();
    int actual = Math.max(0, Math.min(page, Math.max(0, (entries.size() - 1) / 21)));
    var v = view(p, "content.starter-title", Map.of(), who -> plugin.module("starter"));
    for (int i = actual * 21; i < Math.min(entries.size(), (actual + 1) * 21); i++) {
      var entry = entries.get(i);
      item(
          v,
          MenuStyle.slot(i % 21, Math.min(21, entries.size() - actual * 21)),
          kitIcon(entry),
          e -> {});
    }
    put(v, 49, Material.CHEST, "content.claim", e -> starter.claim(p, true));
    if (editor(p, "starter"))
      put(
          v,
          50,
          Material.COMPARATOR,
          "content.edit",
          e -> command(p, "starter", new String[] {"edit"}));
    nav(v, p, actual, entries.size(), n -> previewKit(p, n), null);
    p.openInventory(v.inventory);
  }

  private ItemStack kitIcon(StarterItems.Entry e) {
    try {
      return starter.preview(e);
    } catch (IllegalArgumentException ex) {
      return MenuStyle.button(
          lang, Material.PAPER, "content.scroll-unavailable", "content.scroll-unavailable-help");
    }
  }

  private void kitEditor(Player p, KitDraft d, int page) {
    var v =
        view(p, "content.starter-editor", Map.of(), who -> active(who, d, "starter") && !d.saving);
    int actual = Math.max(0, Math.min(page, Math.max(0, (d.items.size() - 1) / 21)));
    for (int i = actual * 21; i < Math.min(d.items.size(), (actual + 1) * 21); i++) {
      int index = i;
      var entry = d.items.get(i);
      ItemStack icon = entry.teleportScroll() ? kitIcon(entry) : preview(entry.item(), d.texts);
      var meta = icon.getItemMeta();
      var lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<Component>();
      lore.add(lang.render("content.kit-entry", Map.of("amount", "" + entry.amount())));
      lore.add(lang.render("content.kit-entry-help"));
      meta.lore(lore);
      icon.setItemMeta(meta);
      item(
          v,
          MenuStyle.slot(i % 21, Math.min(21, d.items.size() - actual * 21)),
          icon,
          e -> {
            if (e.isRightClick()) {
              d.items.remove(index);
              kitEditor(p, d, actual);
            } else
              ask(
                  p,
                  "amount",
                  "16",
                  "" + entry.amount(),
                  d,
                  value -> {
                    int amount = Integer.parseInt(value);
                    if (amount < 1 || amount > 2304)
                      throw new IllegalArgumentException("content.item-amount");
                    d.items.set(
                        index,
                        new StarterItems.Entry(entry.item(), entry.teleportScroll(), amount));
                    kitEditor(p, d, actual);
                  },
                  () -> kitEditor(p, d, actual));
          });
    }
    put(
        v,
        1,
        Material.LEVER,
        d.enabled ? "gui.enabled" : "gui.disabled",
        e -> {
          d.enabled = !d.enabled;
          kitEditor(p, d, actual);
        });
    put(
        v,
        3,
        Material.PAPER,
        "content.add-scroll",
        e -> {
          if (d.items.size() >= 36) throw new IllegalArgumentException("content.limit");
          d.items.add(new StarterItems.Entry(null, true, 1));
          kitEditor(p, d, actual);
        });
    put(
        v,
        5,
        Material.CHEST,
        "content.copy-inventory",
        e ->
            confirm(
                p,
                () -> {
                  d.items.clear();
                  for (var item : p.getInventory().getStorageContents())
                    if (item != null && !item.getType().isAir())
                      d.items.add(
                          new StarterItems.Entry(
                              ContentItems.capture(item, prefix("starter", "default"), d.texts),
                              false,
                              item.getAmount()));
                  kitEditor(p, d, 0);
                },
                () -> kitEditor(p, d, actual),
                who -> active(who, d, "starter")));
    put(
        v,
        7,
        Material.EMERALD,
        "content.add-item",
        e -> {
          if (d.items.size() >= 36) throw new IllegalArgumentException("content.limit");
          var item = selected(p, e, false);
          d.items.add(
              new StarterItems.Entry(
                  ContentItems.capture(item, prefix("starter", "default"), d.texts),
                  false,
                  item.getAmount()));
          kitEditor(p, d, actual);
        });
    put(
        v,
        50,
        Material.LIME_DYE,
        "content.save",
        e -> {
          d.saving = true;
          complete(
              p,
              starter.save(
                  new StarterItems.Kit(d.enabled, d.source, d.items),
                  d.revision,
                  p.getUniqueId().toString(),
                  d.texts),
              () -> {
                drafts.remove(p.getUniqueId(), d);
                previewKit(p, 0);
              },
              () -> d.saving = false);
        });
    nav(v, p, actual, d.items.size(), n -> kitEditor(p, d, n), () -> previewKit(p, 0));
    p.openInventory(v.inventory);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof View v)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)
        || e.getRawSlot() < 0
        || e.getRawSlot() >= 54
        || !(e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)) return;
    var click = v.clicks.get(e.getRawSlot());
    if (click == null) return;
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!p.isOnline()
                  || !plugin.ready()
                  || p.getOpenInventory().getTopInventory().getHolder() != v) return;
              try {
                if (!love.authenticated(p) || !v.allowed.test(p))
                  throw new IllegalArgumentException("errors.access");
                click.accept(e);
              } catch (RuntimeException ex) {
                error(p, ex);
              }
            });
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof View) e.setCancelled(true);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    drafts.remove(e.getPlayer().getUniqueId());
  }

  @Override
  public void close() {
    for (var p : Bukkit.getOnlinePlayers())
      if (p.getOpenInventory().getTopInventory() != null
          && p.getOpenInventory().getTopInventory().getHolder() instanceof View) p.closeInventory();
    HandlerList.unregisterAll(this);
    drafts.clear();
    registrations.forEach(LoveEssentialService.Registration::close);
  }
}
