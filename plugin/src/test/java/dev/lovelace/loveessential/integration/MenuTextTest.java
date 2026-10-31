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
}
