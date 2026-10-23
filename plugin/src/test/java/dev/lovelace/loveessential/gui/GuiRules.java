package dev.lovelace.loveessential.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** The gui_gen layout rules (RULE 1-8) as executable checks over an opened inventory. */
public final class GuiRules {
  private static final Set<Integer> SIZES = Set.of(27, 36, 45, 54);

  private GuiRules() {}

  public static boolean glass(ItemStack item) {
    return item != null && item.getType().name().endsWith("STAINED_GLASS_PANE");
  }

  public static String name(ItemStack item) {
    return item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()
        ? ""
        : PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
  }

  /** Header control slots for a given number of buttons (RULE 4). */
  public static List<Integer> controlSlots(int count) {
    return switch (count) {
      case 0 -> List.of();
      case 1 -> List.of(4);
      case 2 -> List.of(3, 5);
      case 3 -> List.of(2, 4, 6);
      case 4 -> List.of(2, 3, 5, 6);
      case 5 -> List.of(2, 3, 4, 6, 7);
      default -> List.of(2, 3, 4, 5, 6, 7);
    };
  }

  /** {@code hasParent} is null when the test cannot know whether Back is expected. */
  public static List<String> violations(Inventory inventory, Boolean hasParent) {
    var out = new ArrayList<String>();
    int size = inventory.getSize();
    if (!SIZES.contains(size)) {
      out.add("size " + size + " is not 27/36/45/54");
      return out;
    }
    int footer = size - 9;
    int rows = size / 9;
    boolean row1 = size >= 45;
    // Header and Footer never have holes.
    for (int slot = 0; slot < 9; slot++)
      if (inventory.getItem(slot) == null) out.add("header slot " + slot + " is empty");
    for (int slot = footer; slot < size; slot++)
      if (inventory.getItem(slot) == null) out.add("footer slot " + slot + " is empty");
    if (row1)
      for (int slot = 9; slot < 18; slot++)
        if (!glass(inventory.getItem(slot))) out.add("row1 slot " + slot + " is not glass");
    for (int slot : new int[] {1, 8})
      if (!glass(inventory.getItem(slot))) out.add("header slot " + slot + " must be glass");
    // Header controls: only slots 2-7, centered for their count.
    var controls = new ArrayList<Integer>();
    for (int slot = 2; slot <= 7; slot++) if (!glass(inventory.getItem(slot))) controls.add(slot);
    if (!controls.equals(controlSlots(controls.size())))
      out.add("header controls at " + controls + ", expected " + controlSlots(controls.size()));
    // Work zone: no glass, empty side walls (except pagination on 54).
    int start = row1 ? 18 : 9;
    for (int slot = start; slot < footer; slot++) {
      var item = inventory.getItem(slot);
      if (glass(item)) out.add("glass in work zone at " + slot);
      int column = slot % 9;
      boolean pagination = size == 54 && (slot == 36 || slot == 44);
      if ((column == 0 || column == 8) && item != null && !pagination)
        out.add("side wall slot " + slot + " is occupied by " + name(item));
    }
    // Footer: glass, then extra / back / close.
    for (int slot = footer; slot < size - 3; slot++)
      if (!glass(inventory.getItem(slot)))
        out.add("footer slot " + slot + " must be glass but is " + name(inventory.getItem(slot)));
    if (!name(inventory.getItem(size - 1)).contains("Закрыть"))
      out.add("slot " + (size - 1) + " must be Close");
    boolean back = name(inventory.getItem(size - 2)).contains("Назад");
    if (back && Boolean.FALSE.equals(hasParent)) out.add("Back shown in a root menu");
    if (!back && Boolean.TRUE.equals(hasParent)) out.add("Back missing although the menu has a parent");
    if (!back && !glass(inventory.getItem(size - 2))) out.add("slot " + (size - 2) + " must be Back or glass");
    if (rows < 3) out.add("menu too small");
    return out;
  }

  private static final java.util.regex.Pattern RAW_TAG =
      java.util.regex.Pattern.compile("</?[a-z_][a-z0-9_]*(:[^>]*)?>");
  private static final java.util.regex.Pattern UNRESOLVED_KEY =
      java.util.regex.Pattern.compile("\\[[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+\\]");

  /** Item names and lore that show raw MiniMessage tags or an unresolved {@code [lang.key]}. */
  public static List<String> unresolvedText(Inventory inventory) {
    var out = new ArrayList<String>();
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      var item = inventory.getItem(slot);
      if (item == null || !item.hasItemMeta()) continue;
      var meta = item.getItemMeta();
      var lines = new ArrayList<String>();
      if (meta.hasDisplayName())
        lines.add(PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
      if (meta.hasLore())
        for (var line : meta.lore())
          lines.add(PlainTextComponentSerializer.plainText().serialize(line));
      for (String line : lines) {
        // Settings menus print the stored template on purpose: the admin edits exactly that text.
        // "Категория:" lines of the settings menus print a stored template as well.
        boolean shownValue =
            line.stripLeading().startsWith("Значение:")
                || line.stripLeading().startsWith("Категория:");
        if (UNRESOLVED_KEY.matcher(line).find() || !shownValue && RAW_TAG.matcher(line).find())
          out.add("slot " + slot + ": " + line);
      }
    }
    return out;
  }
}
