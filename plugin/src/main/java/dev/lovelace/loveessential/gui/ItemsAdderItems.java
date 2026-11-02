package dev.lovelace.loveessential.gui;

import dev.lovelace.loveessential.MainThread;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * Optional CustomStack lookup. Callers receive a copy and retain its item model and other metadata.
 */
public final class ItemsAdderItems {
  private ItemsAdderItems() {}

  public static Optional<ItemStack> find(String id) {
    MainThread.require();
    if (id == null || id.isBlank()) return Optional.empty();
    var plugin = Bukkit.getPluginManager().getPlugin("ItemsAdder");
    if (plugin == null || !plugin.isEnabled()) return Optional.empty();
    try {
      var api =
          Class.forName(
              "dev.lone.itemsadder.api.CustomStack", true, plugin.getClass().getClassLoader());
      var custom = api.getMethod("getInstance", String.class).invoke(null, id);
      if (custom == null) return Optional.empty();
      Object value = api.getMethod("getItemStack").invoke(custom);
      return value instanceof ItemStack item && !item.getType().isAir()
          ? Optional.of(item.clone())
          : Optional.empty();
    } catch (ReflectiveOperationException | LinkageError unavailable) {
      return Optional.empty();
    }
  }
}
