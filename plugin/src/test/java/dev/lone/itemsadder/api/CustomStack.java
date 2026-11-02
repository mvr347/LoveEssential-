package dev.lone.itemsadder.api;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/** Test registry deliberately returns a shared prototype, like a provider that requires cloning. */
public final class CustomStack {
  private static final Map<String, CustomStack> ITEMS = new HashMap<>();
  private final ItemStack item;

  private CustomStack(ItemStack item) {
    this.item = item;
  }

  public static void register(String id, ItemStack item) {
    ITEMS.put(id, new CustomStack(item));
  }

  public static CustomStack getInstance(String id) {
    return ITEMS.get(id);
  }

  public ItemStack getItemStack() {
    return item;
  }

  public static void clear() {
    ITEMS.clear();
  }
}
