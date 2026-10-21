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

  default void guiInventory(org.bukkit.inventory.Inventory value) {
    throw new UnsupportedOperationException();
  }

  default Map<String, String> guiVariables() {
    return Map.of();
  }

  Map<Integer, ?> guiActions();

  void guiPut(int slot, Runnable action);
}
