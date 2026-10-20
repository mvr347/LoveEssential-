package dev.lovelace.loveessential.gui;

import java.util.Map;
import org.bukkit.inventory.InventoryHolder;

/** Stable language-key menu ID and coupled callbacks allow safe cosmetic relocation. */
public interface GuiLayout extends InventoryHolder {
  String guiId();

  default Map<String, String> guiVariables() {
    return Map.of();
  }

  Map<Integer, ?> guiActions();

  void guiPut(int slot, Runnable action);
}
