package dev.lovelace.loveessential.gui;

import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * A 54-slot list that follows gui_gen v2.1: glass frame, control buttons only in header slots 2-7
 * (centered for their count), content only in the work zone, pagination in slots 36 and 44, Back
 * in slot 52 when the menu has a parent, and Close in the last slot.
 */
public final class ListMenu implements GuiLayout {
  public static final int SIZE = 54, PAGE_SIZE = MenuStyle.CONTENT.length;
  static final int PREVIOUS = 36, NEXT = 44, BACK = 52, CLOSE = 53;

  /** One clickable entry: its item and what happens on a left click. */
  public record Item(ItemStack icon, Runnable action) {}

  private final String guiId;
  private final Map<Integer, Runnable> actions = new HashMap<>();
  private Inventory inventory;

  private ListMenu(String guiId) {
    this.guiId = guiId;
  }

  /** Header control slots for a given number of buttons; at most six fit (gui_gen RULE 4). */
  public static List<Integer> controlSlots(int count) {
    return switch (Math.min(count, 6)) {
      case 0 -> List.of();
      case 1 -> List.of(4);
      case 2 -> List.of(3, 5);
      case 3 -> List.of(2, 4, 6);
      case 4 -> List.of(2, 3, 5, 6);
      case 5 -> List.of(2, 3, 4, 6, 7);
      default -> List.of(2, 3, 4, 5, 6, 7);
    };
  }

  /** Number of pages for {@code items} entries; an empty list still has one page. */
  public static int pages(int items) {
    return Math.max(1, (items + PAGE_SIZE - 1) / PAGE_SIZE);
  }

  /**
   * Builds and shows the menu. {@code controls} go to the header, {@code items} fill the requested
   * page, {@code back} is null for a root menu.
   */
  public static void open(
      Lang lang,
      Player player,
      String guiId,
      List<Item> controls,
      List<Item> items,
      int page,
      Runnable back) {
    var menu = new ListMenu(guiId);
    menu.inventory =
        Bukkit.createInventory(menu, SIZE, MenuStyle.title(lang, guiId, Map.of()));
    MenuStyle.frame(menu.inventory);
    var slots = controlSlots(controls.size());
    for (int i = 0; i < slots.size() && i < controls.size(); i++)
      menu.put(slots.get(i), controls.get(i));
    int last = pages(items.size()) - 1;
    int current = Math.clamp(page, 0, last);
    int from = current * PAGE_SIZE;
    for (int i = 0; i < PAGE_SIZE && from + i < items.size(); i++)
      menu.put(MenuStyle.CONTENT[i], items.get(from + i));
    if (current > 0)
      menu.put(
          PREVIOUS,
          new Item(
              MenuStyle.button(lang, Material.ARROW, "gui.previous", "gui.click.open"),
              () -> ListMenu.open(lang, player, guiId, controls, items, current - 1, back)));
    if (current < last)
      menu.put(
          NEXT,
          new Item(
              MenuStyle.button(lang, Material.ARROW, "common.next", "gui.click.open"),
              () -> ListMenu.open(lang, player, guiId, controls, items, current + 1, back)));
    if (back != null)
      menu.put(
          BACK,
          new Item(
              MenuStyle.button(lang, Material.ARROW, "common.back", "gui.click.open"), back));
    menu.put(
        CLOSE,
        new Item(
            MenuStyle.button(lang, Material.BARRIER, "gui.close", "gui.click.close"),
            player::closeInventory));
    MenuStyle.open(player, menu.inventory);
  }

  /** Puts an item in a slot; the frame glass in that slot is replaced, never left behind. */
  private void put(int slot, Item item) {
    inventory.setItem(slot, item.icon());
    actions.put(slot, item.action());
  }

  /** A plain item for a list entry: a name and optional lore lines. */
  public static ItemStack entry(Material icon, Component title, List<Component> lore) {
    return MenuStyle.item(icon, title, lore);
  }

  @Override
  public String guiId() {
    return guiId;
  }

  @Override
  public void guiPut(int slot, Runnable action) {
    actions.put(slot, action);
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }

  @Override
  public void guiInventory(Inventory value) {
    inventory = value;
  }

  @Override
  public Map<Integer, ?> guiActions() {
    return actions;
  }

  /** Routes clicks on {@link ListMenu} inventories; registered once by the plugin. */
  public static final class Clicks implements Listener {
    private final org.bukkit.plugin.Plugin plugin;
    private final Lang lang;

    public Clicks(org.bukkit.plugin.Plugin plugin, Lang lang) {
      this.plugin = plugin;
      this.lang = lang;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
      if (!(event.getInventory().getHolder() instanceof ListMenu menu)) return;
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player p)
          || !event.getClick().isLeftClick()
          || event.getRawSlot() < 0
          || event.getRawSlot() >= menu.inventory.getSize()) return;
      var action = menu.actions.get(event.getRawSlot());
      if (action == null) return;
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (p.getOpenInventory().getTopInventory() != menu.inventory) return;
                try {
                  MenuStyle.action(p, action);
                } catch (RuntimeException error) {
                  lang.error(p, error);
                }
              });
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
      if (event.getInventory().getHolder() instanceof ListMenu) event.setCancelled(true);
    }
  }
}
