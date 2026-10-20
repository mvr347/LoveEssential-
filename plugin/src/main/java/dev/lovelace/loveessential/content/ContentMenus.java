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
  private final Map<UUID, Object> drafts = new HashMap<>();
  private final List<LoveEssentialService.Registration> registrations = new ArrayList<>();
  private static final int[] GRID = {19, 20, 21, 28, 29, 30, 37, 38, 39};

  private static final class View implements dev.lovelace.loveessential.gui.GuiLayout {
    Inventory inventory;
    String menuId = "gui.menu";

    public String guiId() {
      return menuId;
    }

    public Map<Integer, ?> guiActions() {
      return clicks;
    }

    public void guiPut(int slot, Runnable action) {
      clicks.put(slot, click -> action.run());
    }

    final Predicate<Player> allowed;
    final Map<Integer, Consumer<InventoryClickEvent>> clicks = new HashMap<>();

    View(Predicate<Player> allowed) {
      this.allowed = allowed;
    }

    public Inventory getInventory() {
      return inventory;
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
      ActionEngine actions) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.love = love;
    this.library = library;
    this.starter = starter;
    this.controls = controls;
    Bukkit.getPluginManager().registerEvents(this, plugin);
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
              list(p, 0);
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
    v.menuId = title;
    v.inventory = Bukkit.createInventory(v, 54, MenuStyle.title(lang, title, vars));
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
            editor(who, draft instanceof KitDraft ? "starter" : "crafting")
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
                    lang.send(p, "chat.saved");
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
    MenuStyle.open(p, v.inventory);
  }

  public void command(Player p, String command, String[] args) {
    if (command.equals("starter")) {
      if (!plugin.module("starter")) throw new IllegalArgumentException("errors.module-disabled");
      requireEditor(p, "starter");
      if (args.length == 1 && args[0].equals("preview")) previewKit(p, 0);
      else if (args.length == 1 && args[0].equals("claim")) starter.claim(p, true);
      else if (args.length == 0 || args.length == 1 && args[0].equals("edit")) {
        requireEditor(p, "starter");
        var d = new KitDraft(starter.kit());
        drafts.put(p.getUniqueId(), d);
        kitEditor(p, d, 0);
      } else throw new IllegalArgumentException("content.recipe-usage");
      return;
    }
    if (!command.equals("recipes")) throw new IllegalArgumentException("content.recipe-usage");
    if (!plugin.module("crafting")) throw new IllegalArgumentException("errors.module-disabled");
    if (args.length == 0 || args.length == 1 && args[0].equals("list")) {
      list(p, 0);
      return;
    }
    if (args.length == 1 && args[0].equals("create"))
      args =
          new String[] {
            "create", "craft_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
          };
    if (args.length != 2) throw new IllegalArgumentException("content.recipe-usage");
    String op = args[0], id = args[1];
    ContentLibrary.id(id);
    if (op.equals("open") || op.equals("preview")) {
      previewRecipe(p, id);
      return;
    }
    requireEditor(p, "crafting");
    var saved = library.craft(id);
    if (op.equals("delete")) {
      var document = saved.orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
      confirm(
          p,
          () ->
              complete(
                  p,
                  library.delete(id, document.revision(), p.getUniqueId().toString()),
                  () -> list(p, 0),
                  () -> {}),
          () -> list(p, 0),
          who -> editor(who, "crafting"));
      return;
    }
    if (!Set.of("create", "edit").contains(op))
      throw new IllegalArgumentException("content.recipe-usage");
    if (op.equals("create") && saved.isPresent())
      throw new IllegalArgumentException("errors.conflict");
    if (op.equals("edit") && saved.isEmpty())
      throw new IllegalArgumentException("errors.not-found");
    var d =
        saved.map(v -> new CraftDraft(v.value(), v.revision())).orElseGet(() -> new CraftDraft(id));
    drafts.put(p.getUniqueId(), d);
    craftEditor(p, d);
  }

  public List<String> suggestions(Player p, String command, String[] args) {
    String module = command.equals("starter") ? "starter" : "crafting";
    if (!plugin.module(module)) return List.of();
    if (command.equals("starter"))
      return args.length == 1
          ? (editor(p, module) ? List.of("preview", "claim", "edit") : List.of())
          : List.of();
    if (args.length == 1) {
      var out = new ArrayList<>(List.of("list", "open"));
      if (editor(p, module)) out.addAll(List.of("create", "edit", "delete"));
      return out;
    }
    if (args.length == 2 && Set.of("open", "edit", "delete", "preview").contains(args[0]))
      return library.ids();
    return List.of();
  }

  public void list(Player p, int page) {
    var ids =
        library.ids().stream()
            .filter(
                id ->
                    library.allowed(p, library.craft(id).orElseThrow().value())
                        || editor(p, "crafting"))
            .toList();
    int actual = Math.max(0, Math.min(page, Math.max(0, (ids.size() - 1) / 21)));
    var v = view(p, "content.recipes-title", Map.of(), who -> plugin.module("crafting"));
    for (int i = actual * 21; i < Math.min(ids.size(), (actual + 1) * 21); i++) {
      String id = ids.get(i);
      var icon =
          MenuStyle.item(
              Material.CRAFTING_TABLE,
              lang.render("content.entry", Map.of("id", id)),
              List.of(
                  lang.render(
                      editor(p, "crafting") ? "content.entry-admin-help" : "content.entry-help")));
      item(
          v,
          MenuStyle.slot(i % 21, Math.min(21, ids.size() - actual * 21)),
          icon,
          e -> {
            if (e.isRightClick() && editor(p, "crafting"))
              command(p, "recipes", new String[] {"edit", id});
            else previewRecipe(p, id);
          });
    }
    if (editor(p, "crafting"))
      put(
          v,
          50,
          Material.EMERALD,
          "content.create",
          e -> command(p, "recipes", new String[] {"create"}));
    nav(v, p, actual, ids.size(), n -> list(p, n), null);
    MenuStyle.open(p, v.inventory);
  }

  private boolean active(Player p, Object draft, String module) {
    return drafts.get(p.getUniqueId()) == draft && editor(p, module);
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
    if (d.revision > 0)
      put(
          v,
          33,
          Material.TNT,
          "content.delete-recipe",
          e ->
              confirm(
                  p,
                  () -> {
                    d.saving = true;
                    complete(
                        p,
                        library.delete(d.id, d.revision, p.getUniqueId().toString()),
                        () -> {
                          drafts.remove(p.getUniqueId(), d);
                          list(p, 0);
                        },
                        () -> d.saving = false);
                  },
                  () -> craftEditor(p, d),
                  who -> active(who, d, "crafting") && !d.saving));
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
                list(p, 0);
              },
              () -> d.saving = false);
        });
    nav(v, p, 0, 0, n -> {}, () -> list(p, 0));
    MenuStyle.open(p, v.inventory);
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
    nav(v, p, 0, 0, n -> {}, () -> list(p, 0));
    MenuStyle.open(p, v.inventory);
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
    MenuStyle.open(p, v.inventory);
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
    MenuStyle.open(p, v.inventory);
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
