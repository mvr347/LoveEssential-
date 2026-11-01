package dev.lovelace.loveessential.gui;

import java.util.Map;
import org.bukkit.inventory.InventoryHolder;

/** Stable language-key menu ID and coupled callbacks allow safe cosmetic relocation. */
public interface GuiLayout extends InventoryHolder {
  String guiId();

  /** One screen context; changing a page replaces it, opening a nested setting does not. */
  default Object guiRoute() {
    return java.util.List.of(guiId(), guiVariables());
  }

  default boolean guiAllowed(org.bukkit.entity.Player player) {
    return true;
  }

  default void guiResume() {}

  /**
   * Rebuilds this menu from fresh state. Used when Back leads to a parent that went stale (for
   * example its revision changed while a child menu edited the same object).
   */
  default Runnable guiReopen() {
    return null;
  }

  /** A Back action for a menu with no parent in the navigation chain (opened as a root with a way out). */
  default Runnable guiBack() {
    return null;
  }

  default void guiInventory(org.bukkit.inventory.Inventory value) {
    throw new UnsupportedOperationException();
  }

  default Map<String, String> guiVariables() {
    return Map.of();
  }

  Map<Integer, ?> guiActions();

  void guiPut(int slot, Runnable action);
}
