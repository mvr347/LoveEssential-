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
    if (id.startsWith("portal-")) return "portal";
    if (id.startsWith("sidebar")) return "scoreboard";
    if (id.startsWith("profile") || id.startsWith("public-profile") || id.startsWith("players"))
      return "profile";
    if (id.startsWith("messages")) return "messages";
    if (id.contains("starter")) return "starter";
    if (id.startsWith("content")) return "crafting";
    if (id.contains("nick")) return "nickname";
    if (id.contains("module") || id.startsWith("gui.settings")) return "npc";
    if (id.startsWith("holo")) return "hologram";
    return "npc";
  }

  public String path(String id) {
    return module(id) + ".gui.menus." + id.replace('.', '-');
  }

  public net.kyori.adventure.text.Component title(String id, Map<String, String> vars) {
    return lang.render(plugin.getConfig().getString(path(id) + ".title-key", id), vars);
  }

  public int refresh(String module, int fallback) {
    return Math.clamp(plugin.getConfig().getInt(module + ".gui.refresh-ticks", fallback), 1, 1200);
  }

  public void apply(Player p, Inventory inventory, GuiLayout layout) {
    standard(p, inventory, layout);
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
      if (current == null || current.getType().isAir()) continue;
      if (!section.getBoolean(source + ".enabled", true)) {
        if (from == 0 || from == inventory.getSize() - 2 || from == inventory.getSize() - 3)
          continue;
        items.put(to, null);
        callbacks.put(to, null);
        continue;
      }
      var item = current.clone();
      var meta = item.getItemMeta();
      String title = section.getString(source + ".title-key", "");
      String lore = section.getString(source + ".lore-key", "");
      if (!title.isBlank()) meta.displayName(lang.render(title, variables));
      if (!lore.isBlank())
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
                    Material.GRAY_STAINED_GLASS_PANE,
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
                  Material.GRAY_STAINED_GLASS_PANE,
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
              Material.GRAY_STAINED_GLASS_PANE,
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
    if (inventory.getItem(0) == null
        || inventory.getItem(0).getType().name().endsWith("STAINED_GLASS_PANE"))
      inventory.setItem(
          0,
          dev.lovelace.loveessential.module.PlayerProfiles.head(
              p, title(layout.guiId(), Map.of("player", p.getName())), List.of()));
    int back = inventory.getSize() - 3;
    if (!Set.of("module-menu.title", "profile-menu.title", "public-profile.title")
            .contains(layout.guiId())
        && !callbacks.containsKey(back)) {
      inventory.setItem(
          back,
          MenuStyle.button(lang, Material.ARROW, "common.back", "gui.separator", "gui.click.open"));
      layout.guiPut(
          back,
          () -> {
            p.closeInventory();
            if (p.hasPermission("loveessential.admin"))
              plugin.getCommand("loveessential").execute(p, "loveessential", new String[0]);
            else plugin.getCommand("profile").execute(p, "profile", new String[0]);
          });
    }
  }

  private static boolean frameSlot(int slot, int size) {
    return slot < (size >= 45 ? 18 : 9) || slot >= size - 9;
  }

  private static boolean legal(int from, int to, int size, String id) {
    if (from == to) return true;
    if (from == 0 || from == size - 2 || from == size - 3) return false;
    if (from >= 2 && from <= 7) return false;
    if (frameSlot(from, size)) return false;
    return !frameSlot(to, size) && to % 9 != 0 && to % 9 != 8;
  }
}
