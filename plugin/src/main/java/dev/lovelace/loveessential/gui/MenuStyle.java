package dev.lovelace.loveessential.gui;

import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.*;

public final class MenuStyle {
  public static final int[] CONTENT = {
    19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43
  };

  private MenuStyle() {}

  public static ItemStack item(Material icon, Component title, List<Component> lore) {
    var item = new ItemStack(icon);
    var meta = item.getItemMeta();
    meta.displayName(title.decoration(TextDecoration.ITALIC, false));
    meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
    item.setItemMeta(meta);
    return item;
  }

  public static ItemStack button(Lang lang, Material icon, String title, String... lore) {
    var item = item(icon, lang.render(title), Arrays.stream(lore).map(lang::render).toList());
    if (title.equals("gui.close")
        || title.equals("common.back")
        || title.equals("gui.confirm")
        || title.equals("gui.cancel")) {
      String texture =
          switch (title) {
            case "gui.close" -> "3ed1aba73f639f4bc42bd48196c715197be2712c3b962c97ebf9e9ed8efa025";
            case "common.back" -> "bd69e06e5dadfd84e5f3d1c21063f2553b2fa945ee1d4d7152fdc5425bc12a9";
            case "gui.confirm" -> "badc048a7ce78f7dad72a07da27d85c0916881e5522eeed1e3daf217a38c1a";
            case "gui.previous" ->
                "84df2cefad48c10063d735399f904faa4608fd466def1de9e55b1a3665e38603";
            case "common.next" ->
                "9c8c2a011be8e26498b036bc407977408873a61a742612c497a225359a30b4cd3";
            default -> "c618731e063379aebf82f1d64c419c90d7f0c1648c548e9b6151b1baba66d723";
          };
      item = head(texture, lang.render(title), Arrays.stream(lore).map(lang::render).toList());
    }
    return item;
  }

  public static ItemStack head(String texture, Component title, List<Component> lore) {
    var item = item(Material.PLAYER_HEAD, title, lore);
    var meta = (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();
    var profile =
        org.bukkit.Bukkit.createPlayerProfile(
            UUID.nameUUIDFromBytes(texture.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var textures = profile.getTextures();
    try {
      textures.setSkin(
          java.net.URI.create("https://textures.minecraft.net/texture/" + texture).toURL());
    } catch (java.net.MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }
    profile.setTextures(textures);
    meta.setOwnerProfile(profile);
    item.setItemMeta(meta);
    return item;
  }

  public static int slot(int index, int count) {
    int row = index / 7, inRow = Math.min(7, count - row * 7);
    int[] columns =
        switch (inRow) {
          case 1 -> new int[] {4};
          case 2 -> new int[] {3, 5};
          case 3 -> new int[] {2, 4, 6};
          case 4 -> new int[] {1, 3, 5, 7};
          case 5 -> new int[] {2, 3, 4, 5, 6};
          case 6 -> new int[] {1, 2, 3, 5, 6, 7};
          default -> new int[] {1, 2, 3, 4, 5, 6, 7};
        };
    return 18 + row * 9 + columns[index % 7];
  }

  public static void frame(Inventory inventory) {
    var filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
    inventory.clear();
    for (int i = 0; i < 18; i++) inventory.setItem(i, filler);
    for (int i = 45; i < 54; i++) inventory.setItem(i, filler);
  }
}
