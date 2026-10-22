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

  private static final com.github.benmanes.caffeine.cache.Cache<String, ItemStack> HEADS =
      com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
          .maximumSize(256)
          .expireAfterAccess(java.time.Duration.ofMinutes(10))
          .build();
  private static GuiSettings settings;
  private static MenuNavigation navigation;

  public static void navigation(MenuNavigation value) {
    navigation = value;
  }

  public static void command(org.bukkit.entity.Player player) {
    if (navigation != null) navigation.command(player);
  }

  public static void action(org.bukkit.entity.Player player, Runnable callback) {
    if (navigation == null) callback.run();
    else navigation.action(player, callback);
  }

  public static void suspend(org.bukkit.entity.Player player) {
    if (navigation != null) navigation.suspend(player);
  }

  public static void special(org.bukkit.entity.Player player, Inventory inventory) {
    if (navigation != null) navigation.special(player, inventory);
  }

  public static boolean hasParent(org.bukkit.entity.Player player) {
    return navigation != null && navigation.hasParent(player);
  }

  public static void back(org.bukkit.entity.Player player) {
    if (navigation != null) navigation.back(player);
  }

  public static void configure(GuiSettings value) {
    settings = value;
    HEADS.invalidateAll();
  }

  public static net.kyori.adventure.text.Component title(
      Lang lang, String key, Map<String, String> vars) {
    return settings == null ? lang.render(key, vars) : settings.title(key, vars);
  }

  public static org.bukkit.inventory.InventoryView open(
      org.bukkit.entity.Player player, Inventory inventory) {
    if (settings != null && inventory.getHolder() instanceof GuiLayout layout) {
      inventory = settings.compact(inventory, layout);
      if (navigation != null) navigation.attach(player, inventory);
      settings.apply(player, inventory, layout);
    }
    return player.openInventory(inventory);
  }

  public static Inventory prepare(org.bukkit.entity.Player player, Inventory inventory) {
    if (settings != null && inventory.getHolder() instanceof GuiLayout layout) {
      inventory = settings.compact(inventory, layout);
      settings.apply(player, inventory, layout);
    }
    return inventory;
  }

  public static void apply(org.bukkit.entity.Player player, Inventory inventory) {
    if (settings != null && inventory.getHolder() instanceof GuiLayout layout)
      settings.apply(player, inventory, layout);
  }

  private MenuStyle() {}

  public static List<Component> description(Lang lang, String key) {
    var result = new ArrayList<Component>();
    for (String line : lang.text(key).split("\n")) {
      String remaining =
          net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(
                  net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(line));
      while (remaining.length() > 54) {
        int split = remaining.lastIndexOf(' ', 54);
        if (split <= 0) split = 54;
        result.add(
            Component.text(
                remaining.substring(0, split),
                net.kyori.adventure.text.format.NamedTextColor.GRAY));
        remaining = remaining.substring(split).stripLeading();
      }
      result.add(Component.text(remaining, net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }
    return result;
  }

  public static ItemStack item(Material icon, Component title, List<Component> lore) {
    if (icon != Material.PLAYER_HEAD
        && icon != Material.GRAY_STAINED_GLASS_PANE
        && !icon.name().endsWith("STAINED_GLASS_PANE")) {
      String texture =
          switch (icon) {
            case BARRIER, RED_DYE, RED_CONCRETE ->
                "c618731e063379aebf82f1d64c419c90d7f0c1648c548e9b6151b1baba66d723";
            case LIME_DYE, LIME_CONCRETE ->
                "badc048a7ce78f7dad72a07da27d85c0916881e5522eeed1e3daf217a38c1a";
            case CLOCK, COMPASS ->
                "8672085ff5df0ee852136e2d41a769229291d666b28e9fb04d6c92f158e42bb";
            case EMERALD, SUNFLOWER ->
                "fae6344cebacd21d8c6ca0d2e34b3c3492dc79bf443140d12a50887ae3eb7f8f";
            case NAME_TAG, FLOWER_BANNER_PATTERN ->
                "c545c4aa97c19bed7cac1817fc5103fe8dd589fa120835a3e4e12f352230a5a6";
            default -> "d0d5a4eba140cbb34d45e40d62dd3f7e6584b2ab0b4155a0bcc603d5d750f793";
          };
      return head(texture, title, lore);
    }
    var item = new ItemStack(icon);
    var meta = item.getItemMeta();
    meta.displayName(title.decoration(TextDecoration.ITALIC, false));
    meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
    item.setItemMeta(meta);
    return item;
  }

  public static ItemStack button(Lang lang, Material icon, String title, String... lore) {
    var descriptions = new ArrayList<Component>();
    if (lore.length == 0) {
      if (lang.contains(title + "-help"))
        descriptions.addAll(
            lang.lines(title + "-help").stream()
                .map(text -> lang.renderText(text, Map.of()))
                .toList());
      descriptions.add(lang.render("gui.separator"));
      descriptions.add(
          lang.render(
              title.equals("sidebar.on") || title.equals("sidebar.off")
                  ? "gui.click.switch"
                  : "gui.click.open"));
    } else
      for (String key : lore)
        descriptions.addAll(
            lang.lines(key).stream().map(text -> lang.renderText(text, Map.of())).toList());
    var item = item(icon, lang.render(title), descriptions);
    if (title.equals("gui.close")
        || title.equals("common.back")
        || title.equals("gui.confirm")
        || title.equals("gui.cancel")
        || title.equals("gui.previous")
        || title.equals("common.next")) {
      String texture =
          switch (title) {
            case "gui.close" -> "3ed1aba73f639f4bc42bd48196c715197be2712c3b962c97ebf9e9ed8efa025";
            case "common.back" -> "bd69e06e5dadfd84e5f3d1c21063f2553b2fa945ee1d4d7152fdc5425bc12a9";
            case "gui.confirm" -> "badc048a7ce78f7dad72a07da27d85c0916881e5522eeed1e3daf217a38c1a";
            case "gui.previous" ->
                "84df2cefad48c10063d735399f904faa4608fd466def1de9e55b1a3665e38603";
            case "common.next" ->
                "8a6d2f471b54047148b355255c7f556e2187fd4e8338b111dbf3adcd2850c498";
            default -> "c618731e063379aebf82f1d64c419c90d7f0c1648c548e9b6151b1baba66d723";
          };
      item = head(texture, lang.render(title), Arrays.stream(lore).map(lang::render).toList());
    }
    var styled = settings == null ? item : settings.styleButton(title, item);
    return title.equals("gui.close") ? markClose(styled) : styled;
  }

  /** Close is recognised by this marker, so replacing its icon or translating it keeps it valid. */
  public static ItemStack markClose(ItemStack item) {
    var meta = item.getItemMeta();
    meta.getPersistentDataContainer()
        .set(CLOSE, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    item.setItemMeta(meta);
    return item;
  }

  // Close is recognised by this marker rather than by its translated name.
  private static final org.bukkit.NamespacedKey CLOSE =
      java.util.Objects.requireNonNull(org.bukkit.NamespacedKey.fromString("loveessential:close"));

  public static boolean isClose(ItemStack item) {
    return item != null
        && item.hasItemMeta()
        && item.getItemMeta()
            .getPersistentDataContainer()
            .has(CLOSE, org.bukkit.persistence.PersistentDataType.BYTE);
  }

  public static ItemStack head(String texture, Component title, List<Component> lore) {
    var cached = HEADS.getIfPresent(texture);
    if (cached != null) return styledHead(cached, title, lore);
    String cacheKey = texture;

    if (texture.startsWith("basehead-")) texture = texture.substring(9);
    String textureValue = texture;
    if (!texture.matches("[a-fA-F0-9]{32,64}")) {
      try {
        String decoded =
            new String(
                Base64.getDecoder().decode(texture), java.nio.charset.StandardCharsets.UTF_8);
        String url =
            com.google.gson.JsonParser.parseString(decoded)
                .getAsJsonObject()
                .getAsJsonObject("textures")
                .getAsJsonObject("SKIN")
                .get("url")
                .getAsString();
        var uri = java.net.URI.create(url);
        if (!"textures.minecraft.net".equalsIgnoreCase(uri.getHost())
            || !uri.getPath().matches("/texture/[a-fA-F0-9]{32,64}"))
          throw new IllegalArgumentException("errors.input");
        texture = uri.getPath().substring(9);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("errors.input", e);
      }
    } else
      textureValue =
          Base64.getEncoder()
              .encodeToString(
                  ("{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
                          + texture
                          + "\"}}}")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var item = item(Material.PLAYER_HEAD, title, lore);
    var meta = (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();
    var profile =
        org.bukkit.Bukkit.createProfile(
            UUID.nameUUIDFromBytes(texture.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "LoveTexture");
    var textures = profile.getTextures();
    try {
      textures.setSkin(
          java.net.URI.create("https://textures.minecraft.net/texture/" + texture).toURL());
    } catch (java.net.MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }
    profile.setTextures(textures);
    profile.setProperty(
        new com.destroystokyo.paper.profile.ProfileProperty("textures", textureValue));
    meta.setPlayerProfile(profile);
    item.setItemMeta(meta);
    var template = item(Material.PLAYER_HEAD, Component.empty(), List.of());
    var templateMeta = (org.bukkit.inventory.meta.SkullMeta) template.getItemMeta();
    templateMeta.setPlayerProfile(profile);
    template.setItemMeta(templateMeta);
    HEADS.put(cacheKey, template);
    return item;
  }

  private static ItemStack styledHead(ItemStack template, Component title, List<Component> lore) {
    var result = template.clone();
    var meta = (org.bukkit.inventory.meta.SkullMeta) result.getItemMeta();
    // Explicitly copy the profile; implementations may clone SkullMeta without properties.
    meta.setPlayerProfile(
        ((org.bukkit.inventory.meta.SkullMeta) template.getItemMeta()).getPlayerProfile());
    meta.displayName(title.decoration(TextDecoration.ITALIC, false));
    meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
    result.setItemMeta(meta);
    return result;
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
    var filler =
        item(
            settings == null
                ? Material.GRAY_STAINED_GLASS_PANE
                : settings.frameMaterial(
                    inventory.getHolder() instanceof GuiLayout layout
                        ? layout.guiId()
                        : "gui.wizard-title"),
            Component.empty(),
            List.of());
    inventory.clear();
    for (int i = 0; i < (inventory.getSize() >= 45 ? 18 : 9); i++) inventory.setItem(i, filler);
    for (int i = inventory.getSize() - 9; i < inventory.getSize(); i++)
      inventory.setItem(i, filler);
  }
}
