package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.gui.GuiLayout;
import dev.lovelace.loveessential.gui.GuiRules;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The personal scoreboard: the menu a player gets from /scoreboard, their choice and the board it draws. */
class PersonalScoreboardTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  PersonalSidebar personal;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    // Lines that need no PlaceholderAPI, so the board has content in a test server.
    var config = plugin.getConfig();
    config.set("scoreboard.placeholders", null);
    config.set("scoreboard.presets", null);
    config.set("scoreboard.categories", null);
    config.set("scoreboard.default-preset", "");
    config.set("scoreboard.categories.general.display-name", "&bОбщее");
    config.set("scoreboard.categories.world.display-name", "&aМир");
    line("name", "&bИмя", "&7Имя: &f%player%", "general");
    line("world", "&bМир", "&7Мир: &f%world%", "world");
    line("online", "&bОнлайн", "&7Онлайн: &f%online%", "world");
    config.set("scoreboard.presets.pair.name", "&6Пара");
    config.set("scoreboard.presets.pair.placeholders", List.of("name", "world"));
    config.set("scoreboard.default-preset", "pair");
    var field = LoveEssentialPlugin.class.getDeclaredField("extensions");
    field.setAccessible(true);
    var extensions = (dev.lovelace.loveessential.Extensions) field.get(plugin);
    personal = extensions.scoreboard.personal();
    personal.reload();
  }

  void line(String id, String name, String template, String category) {
    plugin.getConfig().set("scoreboard.placeholders." + id + ".display-name", name);
    plugin.getConfig().set("scoreboard.placeholders." + id + ".template", template);
    plugin.getConfig().set("scoreboard.placeholders." + id + ".category", category);
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  void waitFor(BooleanSupplier test) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!test.getAsBoolean() && System.nanoTime() < end) {
      tick(1);
      Thread.sleep(5);
    }
    assertTrue(test.getAsBoolean(), "async server operation did not finish");
  }

  /** MockBukkit lacks some scoreboard calls; a tick that hits one must not abort the test. */
  void tick(int ticks) {
    try {
      server.getScheduler().performTicks(ticks);
    } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException ignored) {
      // Not part of what is under test.
    }
  }

  PlayerMock join(String name) throws Exception {
    var p = server.addPlayer(name);
    waitFor(() -> personal.state(p.getUniqueId()) != null);
    return p;
  }

  void click(PlayerMock player, int slot, ClickType type) {
    server
        .getPluginManager()
        .callEvent(
            new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                slot,
                type,
                InventoryAction.PICKUP_ALL));
    tick(2);
  }

  static String name(org.bukkit.inventory.ItemStack item) {
    return GuiRules.name(item);
  }

  @Test
  void aNewPlayerStartsWithTheDefaultPreset() throws Exception {
    var a = join("Alice");
    assertEquals(List.of("name", "world"), personal.state(a.getUniqueId()).ids());
  }

  static int slotNamed(org.bukkit.inventory.Inventory inventory, int from, int to, String text) {
    for (int slot = from; slot < to; slot++) if (name(inventory.getItem(slot)).contains(text)) return slot;
    return -1;
  }

  void openMenu(PlayerMock a) {
    server.dispatchCommand(a, "scoreboard");
    tick(2);
  }

  static String idOf(org.bukkit.inventory.Inventory inventory) {
    return inventory == null || !(inventory.getHolder() instanceof GuiLayout layout) ? "" : layout.guiId();
  }

  @Test
  void theCommandOpensTheMenuAndItFollowsTheLayoutStandard() throws Exception {
    var a = join("Alice");
    openMenu(a);
    var top = a.getOpenInventory().getTopInventory();
    assertEquals("sidebar.menu-title", idOf(top));
    assertEquals(54, top.getSize());
    assertEquals(List.of(), GuiRules.violations(top, false));
    assertEquals(List.of(), GuiRules.unresolvedText(top));
    // Cards 1 and 2 hold the preset lines, card 3 is an empty slot.
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[0])).contains("Имя"));
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[1])).contains("Мир"));
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[2])).contains("Слот 3"));
    // One button for the layouts, no row of categories or presets on the main screen.
    int controls = 0;
    for (int slot = 1; slot <= 8; slot++) if (!GuiRules.glass(top.getItem(slot))) controls++;
    assertEquals(1, controls);
    assertTrue(slotNamed(top, 2, 8, "Раскладки") >= 0);
    for (int slot = 36; slot <= 44; slot++) assertNull(top.getItem(slot), "slot " + slot);
  }

  @Test
  void cardClicksMoveLinesAndRemovingOneInTheMiddleMovesTheNextUp() throws Exception {
    var a = join("Alice");
    var id = a.getUniqueId();
    personal.state(id).replaceWith(List.of(new PersonalState.Entry("name", 0), new PersonalState.Entry("world", 0), new PersonalState.Entry("online", 0)));
    openMenu(a);
    click(a, PersonalLayout.CARD_SLOTS[1], ClickType.LEFT);
    assertEquals(List.of("world", "name", "online"), personal.state(id).ids(), "LMB moves a card up");
    assertTrue(name(a.getOpenInventory().getTopInventory().getItem(PersonalLayout.CARD_SLOTS[0])).contains("Мир"));
    click(a, PersonalLayout.CARD_SLOTS[0], ClickType.RIGHT);
    assertEquals(List.of("name", "world", "online"), personal.state(id).ids(), "RMB moves a card down");
    click(a, PersonalLayout.CARD_SLOTS[1], ClickType.SHIFT_RIGHT);
    assertEquals(List.of("name", "online"), personal.state(id).ids(), "removing the middle one moves the next up");
    var top = a.getOpenInventory().getTopInventory();
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[1])).contains("Онлайн"));
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[2])).contains("Слот 3"));
    assertEquals(List.of(), GuiRules.violations(top, false));

    var store = store();
    waitFor(
        () ->
            store
                .get(PersonalSidebar.KIND, id.toString(), PersonalState.Stored.class)
                .join()
                .map(d -> d.value().entries().size() == 2)
                .orElse(false));
  }

  dev.lovelace.loveessential.storage.SqliteStore store() throws Exception {
    var field = LoveEssentialPlugin.class.getDeclaredField("store");
    field.setAccessible(true);
    return (dev.lovelace.loveessential.storage.SqliteStore) field.get(plugin);
  }

  @Test
  void middleClickOnAnEmptySlotOpensTheCatalogAndPickingAddsTheLine() throws Exception {
    var a = join("Alice");
    openMenu(a);
    click(a, PersonalLayout.CARD_SLOTS[2], ClickType.MIDDLE);
    var catalog = a.getOpenInventory().getTopInventory();
    assertEquals("sidebar.catalog-title", idOf(catalog));
    assertEquals(54, catalog.getSize());
    assertEquals(List.of(), GuiRules.violations(catalog, true));
    assertEquals(List.of(), GuiRules.unresolvedText(catalog));
    int online = slotNamed(catalog, 9, 45, "Онлайн");
    assertTrue(online >= 0, "the online line is in the catalog");
    click(a, online, ClickType.LEFT);
    assertEquals(List.of("name", "world", "online"), personal.state(a.getUniqueId()).ids());
    assertEquals("sidebar.menu-title", idOf(a.getOpenInventory().getTopInventory()), "back on the main screen");
  }

  @Test
  void middleClickOnACardReplacesThatSlotAndPickingAPlacedLineSwaps() throws Exception {
    var a = join("Alice");
    var id = a.getUniqueId();
    openMenu(a);
    click(a, PersonalLayout.CARD_SLOTS[1], ClickType.MIDDLE);
    click(a, slotNamed(a.getOpenInventory().getTopInventory(), 9, 45, "Онлайн"), ClickType.LEFT);
    assertEquals(List.of("name", "online"), personal.state(id).ids(), "the world line is replaced");
    click(a, PersonalLayout.CARD_SLOTS[1], ClickType.MIDDLE);
    click(a, slotNamed(a.getOpenInventory().getTopInventory(), 9, 45, "Имя"), ClickType.LEFT);
    assertEquals(List.of("online", "name"), personal.state(id).ids(), "a line that is already placed swaps");
  }

  @Test
  void theCatalogFiltersByCategoryAndSortsWithLeftAndRightClicks() throws Exception {
    var a = join("Alice");
    openMenu(a);
    click(a, PersonalLayout.CARD_SLOTS[2], ClickType.MIDDLE);
    var catalog = a.getOpenInventory().getTopInventory();
    int filter = slotNamed(catalog, 1, 8, "Фильтр");
    int sort = slotNamed(catalog, 1, 8, "Сортировка");
    assertTrue(filter >= 0 && sort >= 0 && filter != sort);
    assertTrue(name(catalog.getItem(filter)).contains("Все"));
    click(a, filter, ClickType.LEFT);
    catalog = a.getOpenInventory().getTopInventory();
    assertTrue(name(catalog.getItem(filter)).contains("Общее"), name(catalog.getItem(filter)));
    assertTrue(slotNamed(catalog, 9, 45, "Имя") >= 0);
    assertEquals(-1, slotNamed(catalog, 9, 45, "Онлайн"), "other categories are hidden by the filter");
    click(a, filter, ClickType.RIGHT);
    assertTrue(name(a.getOpenInventory().getTopInventory().getItem(filter)).contains("Все"));
    click(a, sort, ClickType.LEFT);
    assertTrue(name(a.getOpenInventory().getTopInventory().getItem(sort)).contains("доступные"));
    assertEquals(List.of(), GuiRules.violations(a.getOpenInventory().getTopInventory(), true));
  }

  @Test
  void theLayoutsOpenInASmallMenuAndApplyingOneReturnsToTheMainScreen() throws Exception {
    var a = join("Alice");
    var id = a.getUniqueId();
    personal.state(id).replaceWith(List.of(new PersonalState.Entry("online", 0)));
    openMenu(a);
    click(a, slotNamed(a.getOpenInventory().getTopInventory(), 2, 8, "Раскладки"), ClickType.LEFT);
    var small = a.getOpenInventory().getTopInventory();
    assertEquals("sidebar.presets-title", idOf(small));
    assertTrue(small.getSize() <= 36, "size " + small.getSize());
    assertEquals(List.of(), GuiRules.violations(small, true));
    assertEquals(List.of(), GuiRules.unresolvedText(small));
    click(a, slotNamed(small, 9, small.getSize() - 9, "Пара"), ClickType.LEFT);
    assertEquals(List.of("name", "world"), personal.state(id).ids());
    assertEquals("sidebar.menu-title", idOf(a.getOpenInventory().getTopInventory()));
  }

  @Test
  void theBoardShowsTheChosenLinesTwoPerRowWithTheFrame() throws Exception {
    var a = join("Alice");
    var content = personal.content(a);
    assertTrue(content.isPresent());
    var lines =
        content.get().lines().stream()
            .map(c -> PlainTextComponentSerializer.plainText().serialize(c).replace(' ', ' ').strip())
            .toList();
    // separator, the pair, separator, bottom
    assertEquals(5, lines.size(), lines.toString());
    assertEquals("", lines.get(0));
    assertEquals("Имя: Alice", lines.get(1));
    assertEquals("Мир: world", lines.get(2));
    assertEquals("", lines.get(3));
    assertTrue(lines.getLast().contains("V O I D C O R E"), lines.getLast());
  }

  @Test
  void aLineWithoutAnswerIsSkippedInsteadOfPrintedRaw() throws Exception {
    line("ghost", "&bПризрак", "&7%nosuch_value%", "general");
    personal.reload();
    var a = join("Alice");
    personal.state(a.getUniqueId()).replaceWith(List.of(new PersonalState.Entry("ghost", 0), new PersonalState.Entry("name", 0)));
    var lines =
        personal.content(a).orElseThrow().lines().stream()
            .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
            .toList();
    assertTrue(lines.stream().noneMatch(l -> l.contains("%") || l.contains("nosuch")), lines.toString());
    assertTrue(lines.stream().anyMatch(l -> l.contains("Имя: Alice")));
  }

  @Test
  void disabledWorldsAndAnEmptyChoiceGiveNoBoard() throws Exception {
    var a = join("Alice");
    personal.state(a.getUniqueId()).replaceWith(List.of());
    assertTrue(personal.content(a).isEmpty());
    personal.state(a.getUniqueId()).replaceWith(List.of(new PersonalState.Entry("name", 0)));
    plugin.getConfig().set("scoreboard.disabled-worlds", List.of(a.getWorld().getName()));
    personal.reload();
    assertTrue(personal.content(a).isEmpty());
  }

  @Test
  void withoutACatalogTheCommandStillTogglesTheBoard() throws Exception {
    plugin.getConfig().set("scoreboard.placeholders", null);
    personal.reload();
    var a = join("Alice");
    server.dispatchCommand(a, "scoreboard");
    tick(2);
    var top = a.getOpenInventory().getTopInventory();
    assertTrue(top == null || !(top.getHolder() instanceof GuiLayout), "no menu without a catalog");
  }
}
