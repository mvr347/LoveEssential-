package dev.lovelace.loveessential.content;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.ItemStack;

/** Paper's versioned item format retains components, skins, PDC and enchantments. */
public record ContentItems(String data, String nameKey, List<String> loreKeys) {
  public ContentItems {
    loreKeys = List.copyOf(loreKeys);
  }

  public static ContentItems capture(ItemStack source, String prefix, Map<String, String> texts) {
    MainThread.require();
    if (source == null || source.getType().isAir())
      throw new IllegalArgumentException("content.hold-item");
    var item = source.clone();
    if (item.getAmount() < 1 || item.getAmount() > item.getMaxStackSize())
      throw new IllegalArgumentException("content.item-amount");
    var meta = item.getItemMeta();
    String name = "";
    var lore = new ArrayList<String>();
    if (meta.hasDisplayName()) {
      name = prefix + ".name";
      texts.put(name, MiniMessage.miniMessage().serialize(meta.displayName()));
      meta.displayName(null);
    }
    if (meta.hasLore()) {
      int i = 0;
      for (var line : meta.lore()) {
        String key = prefix + ".lore." + i++;
        texts.put(key, MiniMessage.miniMessage().serialize(line));
        lore.add(key);
      }
      meta.lore(null);
    }
    item.setItemMeta(meta);
    return new ContentItems(
        Base64.getEncoder().encodeToString(item.serializeAsBytes()), name, lore);
  }

  public ContentItems amount(int amount) {
    MainThread.require();
    var item = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
    if (amount < 1 || amount > item.getMaxStackSize())
      throw new IllegalArgumentException("content.item-amount");
    item.setAmount(amount);
    return new ContentItems(
        Base64.getEncoder().encodeToString(item.serializeAsBytes()), nameKey, loreKeys);
  }

  public ItemStack item(Lang lang) {
    MainThread.require();
    var bytes = Base64.getDecoder().decode(data);
    if (bytes.length > 262144) throw new IllegalArgumentException("errors.input");
    var item = ItemStack.deserializeBytes(bytes);
    var meta = item.getItemMeta();
    if (!nameKey.isEmpty()) meta.displayName(lang.render(nameKey));
    if (!loreKeys.isEmpty()) meta.lore(loreKeys.stream().map(lang::render).toList());
    item.setItemMeta(meta);
    return item;
  }
}
