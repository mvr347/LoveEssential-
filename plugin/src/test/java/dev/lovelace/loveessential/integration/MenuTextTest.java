package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.gui.GuiRules;
import dev.lovelace.loveessential.gui.GuiSettings;
import dev.lovelace.loveessential.lang.Lang;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Descriptions in the editors show readable text: no "null", no raw {@code &}-codes, no unfilled tags. */
class MenuTextTest {
  ServerMock server;
  LoveEssentialPlugin plugin;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    var off = plugin.module("scoreboard", false);
    waitFor(off::isDone);
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  void waitFor(BooleanSupplier test) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!test.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(test.getAsBoolean());
  }

  void click(PlayerMock player, int slot) {
    server
        .getPluginManager()
        .callEvent(
            new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                slot,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL));
    server.getScheduler().performTicks(2);
  }

  static String all(org.bukkit.inventory.Inventory inventory) {
    var out = new StringBuilder();
    for (var item : inventory.getContents()) {
      if (item == null || !item.hasItemMeta()) continue;
      var meta = item.getItemMeta();
      if (meta.hasDisplayName()) out.append(PlainTextComponentSerializer.plainText().serialize(meta.displayName())).append('\n');
      if (meta.hasLore())
        for (var line : meta.lore()) out.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
    }
    return out.toString();
  }

  @Test
  void itemsAdderLegacyGlyphsRenderInStatisticsTitle() throws Exception {
    MockBukkit.createMockPlugin("ItemsAdder");
    var gui = new GuiSettings(plugin, new Lang(plugin));
    String path = gui.path("profile-menu-statistics") + ".title";
    // The API returns the exact mixed MiniMessage/legacy string seen on Paper 26.2.
    plugin.getConfig().set(path, "<white>%img_offset_-8%%img_stats_menu%</white>");
    Component title = gui.title("profile-menu-statistics", Map.of());
    assertEquals("\uF808\uE020", PlainTextComponentSerializer.plainText().serialize(title));
    assertEquals(Component.text("\uF808\uE020", NamedTextColor.WHITE), title.compact());

    // Both aliases work without consuming surrounding MiniMessage tags or literal variables.
    plugin.getConfig().set(path, "%ia_voidcore:stats_menu%<gold><label></gold>");
    title = gui.title("profile-menu-statistics", Map.of("label", "<red>Статистика"));
    assertEquals(
        "\uE020<red>Статистика", PlainTextComponentSerializer.plainText().serialize(title));
    assertEquals(
        Component.text("\uE020", NamedTextColor.WHITE)
            .append(Component.text("<red>Статистика", NamedTextColor.GOLD)),
        title.compact());
  }

  @Test
  void theTabGroupListReadsLegacyColorsInsteadOfPrintingThem() throws Exception {
    plugin.getConfig().set("tab.groups.vip.tabprefix", "&c[Страж] ");
    plugin.getConfig().set("tab.groups.vip.permission", "");
    var admin = server.addPlayer("Builder");
    admin.setOp(true);
    server.dispatchCommand(admin, "le tab");
    server.getScheduler().performTicks(2);
    var main = admin.getOpenInventory().getTopInventory();
    int groups = -1;
    for (int slot = 0; slot < main.getSize(); slot++)
      if (GuiRules.name(main.getItem(slot)).contains("Группы")) groups = slot;
    assertTrue(groups >= 0, "the groups button is in the TAB editor");
    click(admin, groups);
    var list = admin.getOpenInventory().getTopInventory();
    String text = all(list);
    assertTrue(text.contains("[Страж]"), text);
    assertFalse(text.contains("&c"), "raw color codes in the list: " + text);
    assertEquals(java.util.List.of(), GuiRules.unresolvedText(list));
  }

  @Test
  void moduleSettingsShowNoLeftoverOfAYamlMapInTheToggleLore() throws Exception {
    var admin = server.addPlayer("Builder");
    admin.setOp(true);
    server.dispatchCommand(admin, "le module scoreboard");
    server.getScheduler().performTicks(2);
    var top = admin.getOpenInventory().getTopInventory();
    String text = all(top);
    assertFalse(text.contains("null"), text);
    assertFalse(text.contains("{") || text.contains("="), "a map printed as text: " + text);
    assertEquals(java.util.List.of(), GuiRules.unresolvedText(top));
  }
  @Test
  void settingsReachedFromTheProfileKeepTheirBackAfterReturningFromTheSidebar() throws Exception {
    var player = server.addPlayer("ProfileWalker");
    player.setOp(true);
    var enabled = plugin.module("scoreboard", true);
    waitFor(enabled::isDone);
    enabled.join();
    server.getScheduler().performTicks(5);
    server.dispatchCommand(player, "profile");
    server.getScheduler().performTicks(2);
    var root = player.getOpenInventory().getTopInventory();
    int settingsSlot = -1;
    for (int i = 0; i < root.getSize(); i++)
      if (GuiRules.name(root.getItem(i)).contains("Настройки")) settingsSlot = i;
    assertTrue(settingsSlot >= 0);
    click(player, settingsSlot);
    for (int i = 0; i < 4; i++) click(player, 4);
    var settings = player.getOpenInventory().getTopInventory();
    assertTrue(GuiRules.name(settings.getItem(settings.getSize() - 2)).contains("Назад"), "Back from the profile");
    int board = -1;
    for (int i = 0; i < settings.getSize(); i++)
      if (GuiRules.name(settings.getItem(i)).contains("Боковая панель")) board = i;
    assertTrue(board >= 0);
    click(player, board);
    var sidebar = player.getOpenInventory().getTopInventory();
    click(player, sidebar.getSize() - 2);
    var returned = player.getOpenInventory().getTopInventory();
    assertEquals("profile-menu.settings", ((dev.lovelace.loveessential.gui.GuiLayout) returned.getHolder()).guiId());
    assertTrue(GuiRules.name(returned.getItem(returned.getSize() - 2)).contains("Назад"), "Back to the profile after returning");
  }

  @Test
  void nicknameOpenedByAnotherPluginHasBackToIt() throws Exception {
    var player = server.addPlayer("NickReturner");
    player.setOp(true);
    // The nickname data loads asynchronously: the first attempt may say "loading", so try again for a while.
    long end = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
    org.bukkit.inventory.Inventory nick = null;
    while (System.nanoTime() < end) {
      server.dispatchCommand(player, "nick return loveessential:profile");
      server.getScheduler().performTicks(5);
      var top = player.getOpenInventory().getTopInventory();
      if (top != null && top.getHolder() instanceof dev.lovelace.loveessential.gui.GuiLayout layout
          && "nick.title".equals(layout.guiId())) {
        nick = top;
        break;
      }
      Thread.sleep(20);
    }
    assertNotNull(nick, "the nickname menu opens");
    assertTrue(GuiRules.name(nick.getItem(nick.getSize() - 2)).contains("Назад"), "Back is shown");
    // Editing (a colour click redraws the menu) keeps the opener's Back.
    for (int slot = 18; slot < 45; slot++) {
      var item = nick.getItem(slot);
      if (item != null && item.getType() == org.bukkit.Material.PLAYER_HEAD && GuiRules.name(item).contains("цвет")) {
        click(player, slot);
        break;
      }
    }
    var after = player.getOpenInventory().getTopInventory();
    assertTrue(GuiRules.name(after.getItem(after.getSize() - 2)).contains("Назад"), "Back survives an edit");
    click(player, after.getSize() - 2);
    assertEquals("profile-menu.title", ((dev.lovelace.loveessential.gui.GuiLayout) player.getOpenInventory().getTopInventory().getHolder()).guiId());
  }

  @Test
  void settingsReturnThroughTheActualParentKeepBackAndDirectSidebarHasNoBack() throws Exception {
    var player = server.addPlayer("SettingsOwner");
    player.setOp(true);
    var enabled = plugin.module("scoreboard", true);
    waitFor(enabled::isDone);
    enabled.join();
    server.getScheduler().performTicks(5);
    server.dispatchCommand(player, "profile settings");
    server.getScheduler().performTicks(2);
    for (int i=0; i<4; i++) click(player, 4);
    var settings = player.getOpenInventory().getTopInventory();
    assertTrue(GuiRules.name(settings.getItem(4)).contains("Интерфейс"));
    int board = -1;
    for (int i=0; i<settings.getSize(); i++)
      if (GuiRules.name(settings.getItem(i)).contains("Боковая панель")) board=i;
    assertTrue(board>=0);
    click(player, board);
    var sidebar = player.getOpenInventory().getTopInventory();
    assertEquals("sidebar.menu-title", ((dev.lovelace.loveessential.gui.GuiLayout) sidebar.getHolder()).guiId());
    click(player, sidebar.getSize()-2);
    assertEquals("profile-menu.settings", ((dev.lovelace.loveessential.gui.GuiLayout) player.getOpenInventory().getTopInventory().getHolder()).guiId());
    var returned = player.getOpenInventory().getTopInventory();
    assertTrue(GuiRules.name(returned.getItem(4)).contains("Интерфейс"));
    // A menu you came back to is not the start of the way: Back leads on to the profile.
    assertTrue(GuiRules.name(returned.getItem(returned.getSize()-2)).contains("Назад"));
    server.dispatchCommand(player, "le scoreboard edit");
    server.getScheduler().performTicks(2);
    var direct = player.getOpenInventory().getTopInventory();
    assertFalse(GuiRules.name(direct.getItem(direct.getSize()-2)).contains("Назад"));
  }

  @Test
  void smoothNightSkipCancelsOnlyTheVanillaJumpWhileEnabled() throws Exception {
    var sleeper = server.addPlayer("Sleeper");
    sleeper.setOp(true);
    var enabled = plugin.module("nightskip", true);
    waitFor(enabled::isDone);
    enabled.join();
    sleeper.getWorld().setTime(13000);
    var vanilla = new org.bukkit.event.world.TimeSkipEvent(sleeper.getWorld(), org.bukkit.event.world.TimeSkipEvent.SkipReason.NIGHT_SKIP, 10000);
    server.getPluginManager().callEvent(vanilla);
    assertTrue(vanilla.isCancelled());
    var custom = new org.bukkit.event.world.TimeSkipEvent(sleeper.getWorld(), org.bukkit.event.world.TimeSkipEvent.SkipReason.CUSTOM, 10000);
    server.getPluginManager().callEvent(custom);
    assertFalse(custom.isCancelled());
    sleeper.getWorld().setTime(6000);
    var stormSleep = new org.bukkit.event.world.TimeSkipEvent(sleeper.getWorld(), org.bukkit.event.world.TimeSkipEvent.SkipReason.NIGHT_SKIP, 1000);
    server.getPluginManager().callEvent(stormSleep);
    assertFalse(stormSleep.isCancelled(), "daytime thunderstorm sleep must retain its vanilla behavior");
    sleeper.getWorld().setTime(13000);
    var disabled = plugin.module("nightskip", false);
    waitFor(disabled::isDone);
    disabled.join();
    var ordinary = new org.bukkit.event.world.TimeSkipEvent(sleeper.getWorld(), org.bukkit.event.world.TimeSkipEvent.SkipReason.NIGHT_SKIP, 10000);
    server.getPluginManager().callEvent(ordinary);
    assertFalse(ordinary.isCancelled());
  }

}
