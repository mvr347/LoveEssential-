package dev.lovelace.loveessential.gui;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

/** Presentation only: configured buttons retain the original checked callback. */
public final class GuiSettings {
  private final LoveEssentialPlugin plugin;
  private final Lang lang;

  public GuiSettings(LoveEssentialPlugin plugin, Lang lang) {
    this.plugin = plugin;
    this.lang = lang;
  }

  public static String module(String id) {
    if (id.startsWith("tab-") || id.startsWith("tab.")) return "tab";
    if (id.startsWith("portal-")) return "portal";
    if (id.startsWith("sidebar")) return "scoreboard";
    if (id.startsWith("scenario")) return "scenarios";
    if (id.startsWith("world")) return "worlds";
    if (id.startsWith("windows") || id.startsWith("dialog")) return "dialogs";
    if (id.startsWith("motd")) return "motd";
    if (id.startsWith("comfort")) return "sitride";
    if (id.startsWith("inventory")) return "inventory";
    if (id.startsWith("profile") || id.startsWith("public-profile") || id.startsWith("players"))
      return "profile";
    if (id.startsWith("messages")) return "messages";
    if (id.startsWith("starter")
        || id.startsWith("content.starter")
        || id.startsWith("content-text.starter")
        || id.startsWith("content.kit")) return "starter";
    if (id.startsWith("content")) return "crafting";
    if (id.contains("nick")) return "nickname";
    if (id.startsWith("vanish")) return "vanish";
    if (id.contains("module") || id.startsWith("gui.settings")) return "npc";
    if (id.startsWith("holo")) return "hologram";
    return "npc";
  }

  public String path(String id) {
    return module(id) + ".gui.menus." + id.replace('.', '-');
  }

  public net.kyori.adventure.text.Component title(String id, Map<String, String> vars) {
    String inline = plugin.getConfig().getString(path(id) + ".title", "");
    return inline.isBlank()
        ? lang.render(plugin.getConfig().getString(path(id) + ".title-key", id), vars)
        : lang.renderText(inline, vars);
  }

  public Material frameMaterial(String id) {
    String name =
        plugin.getConfig().getString(module(id) + ".gui.frame.material", "GRAY_STAINED_GLASS_PANE");
    Material material = Material.matchMaterial(name);
    if (material == null || !material.name().endsWith("STAINED_GLASS_PANE"))
      throw new IllegalArgumentException("gui-layout.invalid");
    return material;
  }

  public ItemStack styleButton(String key, ItemStack item) {
    String root = module(key) + ".gui.components." + key.replace('.', '-');
    String texture = plugin.getConfig().getString(root + ".texture", "");
    var meta = item.getItemMeta();
    String title = plugin.getConfig().getString(root + ".title", "");
    if (!title.isBlank()) meta.displayName(lang.renderText(title, Map.of()));
    else {
      String replacement = plugin.getConfig().getString(root + ".title-key", key);
      if (!replacement.equals(key)) meta.displayName(lang.render(replacement));
    }
    if (plugin.getConfig().isList(root + ".lore"))
      meta.lore(
          plugin.getConfig().getStringList(root + ".lore").stream()
              .map(text -> lang.renderText(text, Map.of()))
              .toList());
    item.setItemMeta(meta);
    return texture.isBlank()
        ? item
        : MenuStyle.head(
            texture, meta.displayName(), Objects.requireNonNullElse(meta.lore(), List.of()));
  }

  public int refresh(String module, int fallback) {
    return Math.clamp(plugin.getConfig().getInt(module + ".gui.refresh-ticks", fallback), 1, 1200);
  }

  public void apply(Player p, Inventory inventory, GuiLayout layout) {
    standard(p, inventory, layout);
    for (var nav :
        Map.of("back", inventory.getSize() - 2, "close", inventory.getSize() - 1).entrySet()) {
      var current = inventory.getItem(nav.getValue());
      String texture =
          plugin
              .getConfig()
              .getString(
                  module(layout.guiId()) + ".gui.navigation." + nav.getKey() + ".texture", "");
      if (current != null && current.getType() == Material.PLAYER_HEAD && !texture.isBlank()) {
        var head =
            MenuStyle.head(
                texture,
                current.getItemMeta().displayName(),
                Objects.requireNonNullElse(current.getItemMeta().lore(), List.of()));
        inventory.setItem(
            nav.getValue(),
            nav.getKey().equals("close") || MenuStyle.isClose(current)
                ? MenuStyle.markClose(head)
                : head);
      }
    }
    String root = path(layout.guiId());
    var variables = new HashMap<String, String>(Map.of("player", p.getName()));
    variables.putAll(layout.guiVariables());
    var section = plugin.getConfig().getConfigurationSection(root + ".buttons");
    if (section == null) return;
    var items = new HashMap<Integer, ItemStack>();
    var callbacks = new HashMap<Integer, Object>();
    @SuppressWarnings("unchecked")
    var actions = (Map<Integer, Object>) (Map<?, ?>) layout.guiActions();
    Set<Integer> destinations = new HashSet<>();
    // Validate the whole layout before mutating either items or callbacks.
    for (String source : section.getKeys(false)) {
      int from, to;
      try {
        from = Integer.parseInt(source);
        to = section.getInt(source + ".slot", from);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("gui-layout.invalid");
      }
      if (from < 0
          || from >= inventory.getSize()
          || to < 0
          || to >= inventory.getSize()
          || !legal(from, to, inventory.getSize(), layout.guiId())
          || !destinations.add(to)) throw new IllegalArgumentException("gui-layout.invalid");
      var current = inventory.getItem(from);
      if (current == null
          || current.getType().isAir()
          || current.getType().name().endsWith("STAINED_GLASS_PANE") && !actions.containsKey(from))
        continue;
      if (!section.getBoolean(source + ".enabled", true)) {
        if (from == 0 || from == inventory.getSize() - 1 || from == inventory.getSize() - 2)
          continue;
        items.put(to, null);
        callbacks.put(to, null);
        continue;
      }
      if (from == to
          && section.getString(source + ".title-key", "").isBlank()
          && section.getString(source + ".lore-key", "").isBlank()
          && section.getString(source + ".texture", "").isBlank()
          && !section.contains(source + ".title")
          && !section.contains(source + ".lore")) continue;
      var item = current.clone();
      var meta = item.getItemMeta();
      String title = section.getString(source + ".title-key", "");
      String lore = section.getString(source + ".lore-key", "");
      if (section.isString(source + ".title"))
        meta.displayName(lang.renderText(section.getString(source + ".title"), variables));
      else if (!title.isBlank()) meta.displayName(lang.render(title, variables));
      if (section.isList(source + ".lore"))
        meta.lore(
            section.getStringList(source + ".lore").stream()
                .map(text -> lang.renderText(text, variables))
                .toList());
      else if (!lore.isBlank())
        meta.lore(lang.lines(lore).stream().map(t -> lang.renderText(t, variables)).toList());
      item.setItemMeta(meta);
      String texture = section.getString(source + ".texture", "");
      if (!texture.isBlank() && from != 0)
        item =
            MenuStyle.head(
                texture,
                item.getItemMeta().displayName(),
                Objects.requireNonNullElse(item.getItemMeta().lore(), List.of()));
      items.put(to, item);
      callbacks.put(to, actions.get(from));
    }
    for (String key : section.getKeys(false)) {
      int from = Integer.parseInt(key);
      int to = section.getInt(key + ".slot", from);
      if (!items.containsKey(to)) continue;
      if (from != to && inventory.getItem(to) != null && !section.contains(Integer.toString(to)))
        throw new IllegalArgumentException("gui-layout.occupied");
    }
    for (String key : section.getKeys(false)) {
      int from = Integer.parseInt(key);
      int to = section.getInt(key + ".slot", from);
      if (items.containsKey(to)) {
        inventory.setItem(
            from,
            frameSlot(from, inventory.getSize())
                ? MenuStyle.item(
                    frameMaterial(layout.guiId()),
                    net.kyori.adventure.text.Component.empty(),
                    List.of())
                : null);
        actions.remove(from);
      }
    }
    for (var entry : items.entrySet()) {
      int slot = entry.getKey();
      inventory.setItem(
          slot,
          entry.getValue() == null && frameSlot(slot, inventory.getSize())
              ? MenuStyle.item(
                  frameMaterial(layout.guiId()),
                  net.kyori.adventure.text.Component.empty(),
                  List.of())
              : entry.getValue());
      if (callbacks.get(slot) != null) actions.put(slot, callbacks.get(slot));
    }
  }

  private void standard(Player p, Inventory inventory, GuiLayout layout) {
    @SuppressWarnings("unchecked")
    var callbacks = (Map<Integer, Object>) (Map<?, ?>) layout.guiActions();
    var buttons = new ArrayList<ItemStack>();
    var handlers = new ArrayList<Object>();
    for (int slot = 1; slot < 9; slot++) {
      var item = inventory.getItem(slot);
      if (item != null && !item.getType().name().endsWith("STAINED_GLASS_PANE")) {
        buttons.add(item);
        handlers.add(callbacks.get(slot));
      }
      callbacks.remove(slot);
      inventory.setItem(
          slot,
          MenuStyle.item(
              frameMaterial(layout.guiId()),
              net.kyori.adventure.text.Component.empty(),
              List.of()));
    }
    int[] positions =
        switch (buttons.size()) {
          case 0 -> new int[] {};
          case 1 -> new int[] {4};
          case 2 -> new int[] {3, 5};
          case 3 -> new int[] {2, 4, 6};
          case 4 -> new int[] {2, 3, 5, 6};
          case 5 -> new int[] {2, 3, 4, 6, 7};
          default -> new int[] {2, 3, 4, 5, 6, 7};
        };
    if (buttons.size() > 6) throw new IllegalArgumentException("gui-layout.header-limit");
    for (int i = 0; i < buttons.size(); i++) {
      inventory.setItem(positions[i], buttons.get(i));
      if (handlers.get(i) != null) callbacks.put(positions[i], handlers.get(i));
    }
    int size = inventory.getSize();
    int close = size - 1, back = size - 2;
    if (!closeItem(inventory.getItem(close))) {
      var extra = inventory.getItem(size - 4);
      Object extraAction = callbacks.get(size - 4);
      for (int slot = size - 4; slot < size; slot++) {
        inventory.setItem(slot, glass(layout.guiId()));
        callbacks.remove(slot);
      }
      if (extra != null && !extra.getType().name().endsWith("STAINED_GLASS_PANE")) {
        inventory.setItem(size - 3, extra);
        if (extraAction != null) callbacks.put(size - 3, extraAction);
      }
    }
    inventory.setItem(
        close,
        MenuStyle.button(lang, Material.BARRIER, "gui.close", "gui.separator", "gui.click.close"));
    layout.guiPut(close, p::closeInventory);
    inventory.setItem(back, glass(layout.guiId()));
    callbacks.remove(back);
    if (MenuStyle.hasParent(p)) {
      inventory.setItem(
          back,
          MenuStyle.button(lang, Material.ARROW, "common.back", "gui.separator", "gui.click.open"));
      layout.guiPut(back, () -> MenuStyle.back(p));
    }
    if (layout.guiId().equals("sidebar.slots-title")) {
      inventory.setItem(0, glass(layout.guiId()));
      callbacks.remove(0);
    }
  }

  private ItemStack glass(String id) {
    return MenuStyle.item(frameMaterial(id), net.kyori.adventure.text.Component.empty(), List.of());
  }

  private boolean closeItem(ItemStack item) {
    return MenuStyle.isClose(item)
        || item != null
        && item.hasItemMeta()
        && item.getItemMeta().hasDisplayName()
        && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(item.getItemMeta().displayName())
            .contains("Закрыть");
  }

  public Inventory compact(Inventory inventory, GuiLayout layout) {
    if (inventory.getSize() != 54
        || module(layout.guiId()).equals("crafting")
        || module(layout.guiId()).equals("starter")
        // The personal scoreboard menu places its cards and categories by slot, and the catalog fills pages by slot, so they keep their size.
        || layout.guiId().equals("sidebar.menu-title")
        || layout.guiId().equals("sidebar.catalog-title")) return inventory;
    @SuppressWarnings("unchecked")
    var actions = (Map<Integer, Object>) (Map<?, ?>) layout.guiActions();
    // Previous/next pages require the full list layout.
    for (int slot : new int[] {36, 44}) {
      var item = inventory.getItem(slot);
      if (item != null
          && !item.getType().isAir()
          && !item.getType().name().endsWith("STAINED_GLASS_PANE")) return inventory;
    }
    var content = new ArrayList<Integer>();
    for (int slot = 1; slot < 9; slot++) {
      var item = inventory.getItem(slot);
      if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
        String name =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(item.getItemMeta().displayName());
        if (name.contains("Создать") || name.contains("Добавить")) {
          for (int target : MenuStyle.CONTENT)
            if (inventory.getItem(target) == null || inventory.getItem(target).getType().isAir()) {
              inventory.setItem(target, item);
              inventory.setItem(slot, glass(layout.guiId()));
              if (actions.containsKey(slot)) actions.put(target, actions.remove(slot));
              break;
            }
        }
      }
    }
    for (int slot = 18; slot < 45; slot++) {
      var item = inventory.getItem(slot);
      if (item != null
          && !item.getType().isAir()
          && !item.getType().name().endsWith("STAINED_GLASS_PANE")) content.add(slot);
    }
    if (content.size() > 14) return inventory;
    int size = content.size() <= 7 ? 27 : 36;
    var title = title(layout.guiId(), layout.guiVariables());
    var next = Bukkit.createInventory(layout, size, title);
    var replacement = new HashMap<Integer, Object>();
    for (int slot = 0; slot < 9; slot++) {
      next.setItem(slot, inventory.getItem(slot));
      if (actions.containsKey(slot)) replacement.put(slot, actions.get(slot));
    }
    for (int i = 0; i < content.size(); i++) {
      int from = content.get(i), to = MenuStyle.slot(i, content.size()) - 9;
      next.setItem(to, inventory.getItem(from));
      if (actions.containsKey(from)) replacement.put(to, actions.get(from));
    }
    for (int i = 0; i < 9; i++) {
      int from = 45 + i, to = size - 9 + i;
      next.setItem(to, inventory.getItem(from));
      if (actions.containsKey(from)) replacement.put(to, actions.get(from));
    }
    for (int slot = 0; slot < 9; slot++)
      if (next.getItem(slot) == null) next.setItem(slot, glass(layout.guiId()));
    for (int slot = size - 9; slot < size; slot++)
      if (next.getItem(slot) == null) next.setItem(slot, glass(layout.guiId()));
    actions.clear();
    actions.putAll(replacement);
    layout.guiInventory(next);
    return next;
  }

  private static boolean frameSlot(int slot, int size) {
    return slot < (size >= 45 ? 18 : 9) || slot >= size - 9;
  }

  private static boolean legal(int from, int to, int size, String id) {
    if (from == to) return true;
    if (from == 0 || from == size - 1 || from == size - 2) return false;
    if (from >= 2 && from <= 7) return false;
    if (frameSlot(from, size)) return false;
    return !frameSlot(to, size) && to % 9 != 0 && to % 9 != 8;
  }
}
