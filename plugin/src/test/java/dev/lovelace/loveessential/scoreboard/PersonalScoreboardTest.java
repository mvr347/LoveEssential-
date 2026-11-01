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
  me.clip.placeholderapi.PlaceholderAPIPlugin papi;
  final java.util.Map<java.util.UUID, String> passes = new java.util.HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    server =
        MockBukkit.mock(
            new ServerMock() {
              @Override
              public String getBukkitVersion() {
                return "26.2-R0.1-SNAPSHOT";
              }
            });
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
  void close() throws Exception {
    if (papi != null) {
      var enabled = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("isEnabled");
      enabled.setAccessible(true);
      enabled.setBoolean(papi, false);
      var instance = me.clip.placeholderapi.PlaceholderAPIPlugin.class.getDeclaredField("instance");
      instance.setAccessible(true);
      instance.set(null, null);
    }
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
    // A command-opened layout has no invented parent.
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
    click(a, PersonalLayout.CARD_SLOTS[1], ClickType.SHIFT_LEFT);
    assertEquals(List.of("world", "name", "online"), personal.state(id).ids(), "Shift+LMB moves a card up");
    assertTrue(name(a.getOpenInventory().getTopInventory().getItem(PersonalLayout.CARD_SLOTS[0])).contains("Мир"));
    click(a, PersonalLayout.CARD_SLOTS[0], ClickType.SHIFT_RIGHT);
    assertEquals(List.of("name", "world", "online"), personal.state(id).ids(), "Shift+RMB moves a card down");
    click(a, PersonalLayout.CARD_SLOTS[1], ClickType.RIGHT);
    assertEquals(List.of("name", "online"), personal.state(id).ids(), "removing the middle one moves the next up");
    var top = a.getOpenInventory().getTopInventory();
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[1])).contains("Онлайн"));
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[2])).contains("Слот 3"));
    // A command-opened layout has no invented parent.
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
  void leftClickOnAnEmptySlotOpensTheCatalogAndPickingAddsTheLine() throws Exception {
    var a = join("Alice");
    openMenu(a);
    click(a, PersonalLayout.CARD_SLOTS[2], ClickType.LEFT);
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
  void leftClickOnACardReplacesThatSlotAndPickingAPlacedLineSwaps() throws Exception {
    var a = join("Alice");
    var id = a.getUniqueId();
    openMenu(a);
    click(a, PersonalLayout.CARD_SLOTS[1], ClickType.LEFT);
    click(a, slotNamed(a.getOpenInventory().getTopInventory(), 9, 45, "Онлайн"), ClickType.LEFT);
    assertEquals(List.of("name", "online"), personal.state(id).ids(), "the world line is replaced");
    click(a, PersonalLayout.CARD_SLOTS[1], ClickType.LEFT);
    click(a, slotNamed(a.getOpenInventory().getTopInventory(), 9, 45, "Имя"), ClickType.LEFT);
    assertEquals(List.of("online", "name"), personal.state(id).ids(), "a line that is already placed swaps");
  }

  @Test
  void theCatalogFiltersByCategoryAndSortsWithLeftAndRightClicks() throws Exception {
    var a = join("Alice");
    openMenu(a);
    click(a, PersonalLayout.CARD_SLOTS[2], ClickType.LEFT);
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

  /** Use the real PAPI parser and registry without its network/metrics startup. */
  void enableHunt() throws Exception {
    MockBukkit.createMockPlugin("PlaceholderAPI");
    var unsafeType = Class.forName("sun.misc.Unsafe");
    var unsafe = unsafeType.getDeclaredField("theUnsafe");
    unsafe.setAccessible(true);
    papi =
        (me.clip.placeholderapi.PlaceholderAPIPlugin)
            unsafeType
                .getMethod("allocateInstance", Class.class)
                .invoke(unsafe.get(null), me.clip.placeholderapi.PlaceholderAPIPlugin.class);
    for (var entry :
        java.util.Map.of(
                "server",
                server,
                "dataFolder",
                new java.io.File(plugin.getDataFolder(), "papi-fixture"),
                "description",
                new org.bukkit.plugin.PluginDescriptionFile(
                    "PlaceholderAPI",
                    "2.11.6",
                    me.clip.placeholderapi.PlaceholderAPIPlugin.class.getName()),
                "logger",
                java.util.logging.Logger.getLogger("PAPI-sidebar-fixture"))
            .entrySet()) {
      var member = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField(entry.getKey());
      member.setAccessible(true);
      member.set(papi, entry.getValue());
    }
    var manager = new me.clip.placeholderapi.expansion.manager.LocalExpansionManager(papi);
    var managerField = papi.getClass().getDeclaredField("localExpansionManager");
    managerField.setAccessible(true);
    managerField.set(papi, manager);
    var field = manager.getClass().getDeclaredField("expansions");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var expansions =
        (java.util.Map<String, me.clip.placeholderapi.expansion.PlaceholderExpansion>)
            field.get(manager);
    expansions.put(
        "lovehunt",
        new me.clip.placeholderapi.expansion.PlaceholderExpansion() {
          public String getIdentifier() {
            return "lovehunt";
          }

          public String getAuthor() {
            return "test";
          }

          public String getVersion() {
            return "1";
          }

          @Override
          public String onPlaceholderRequest(org.bukkit.entity.Player p, String params) {
            return params.equals("has_pass")
                ? passes.getOrDefault(p.getUniqueId(), "false")
                : "Следопыт";
          }
        });
    var enabled = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("isEnabled");
    enabled.setAccessible(true);
    enabled.setBoolean(papi, true);
    var instance = papi.getClass().getDeclaredField("instance");
    instance.setAccessible(true);
    instance.set(null, papi);
  }

  void huntLines() {
    plugin.getConfig().set("scoreboard.categories.hunt.display-name", "&2Охота");
    line("hunter", "&2Ранг охотника", "&7Ранг: %lovehunt_rank%", "hunt");
    line("custom_hunt", "&2Заказы охотника", "&7Заказы: %lovehunt_completed%", "general");
    line("hunt_builtin", "&2Имя охотника", "&7Охотник: %player%", "hunt");
    personal.reload();
  }

  @Test
  void huntRequiresAPurchasedPassForEveryPickIncludingCustomRowsAndOperators() throws Exception {
    huntLines();
    var a = join("Alice");
    assertEquals(
        PersonalSidebar.Status.MISSING_PLUGIN,
        personal.status(a, personal.catalog().item("hunter")));
    enableHunt();
    a.setOp(true);
    for (String response : List.of("false", "", "%lovehunt_has_pass%", "Нет требуется")) {
      passes.put(a.getUniqueId(), response);
      for (String id : List.of("hunter", "custom_hunt", "hunt_builtin"))
        assertEquals(
            PersonalSidebar.Status.HUNTER_PASS, personal.status(a, personal.catalog().item(id)));
    }
    openMenu(a);
    click(a, PersonalLayout.CARD_SLOTS[2], ClickType.LEFT);
    var top = a.getOpenInventory().getTopInventory();
    int row = slotNamed(top, 9, 45, "Ранг охотника");
    assertTrue(row >= 0);
    assertTrue(
        top.getItem(row).getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(text -> text.contains("купленный билет охотника")));
    click(a, row, ClickType.LEFT);
    assertFalse(personal.state(a.getUniqueId()).has("hunter"));
    passes.put(a.getUniqueId(), "true");
    click(a, row, ClickType.LEFT);
    assertTrue(personal.state(a.getUniqueId()).has("hunter"));
    assertEquals(
        PersonalSidebar.Status.AVAILABLE,
        personal.status(a, personal.catalog().item("custom_hunt")));
    var b = join("Bob");
    assertEquals(
        PersonalSidebar.Status.HUNTER_PASS, personal.status(b, personal.catalog().item("hunter")));
  }

  @Test
  void defaultsAndAnAlreadyOpenPresetCannotBypassLivePassOwnership() throws Exception {
    enableHunt();
    huntLines();
    var config = plugin.getConfig();
    config.set("scoreboard.presets.hunting.name", "&2Охотничья");
    config.set("scoreboard.presets.hunting.placeholders", List.of("name", "hunter", "custom_hunt"));
    config.set("scoreboard.default-preset", "hunting");
    personal.reload();
    var a = join("Alice");
    assertEquals(List.of("name"), personal.state(a.getUniqueId()).ids());
    passes.put(a.getUniqueId(), "true");
    personal.state(a.getUniqueId()).replaceWith(List.of(new PersonalState.Entry("world", 0)));
    openMenu(a);
    click(a, slotNamed(a.getOpenInventory().getTopInventory(), 2, 8, "Раскладки"), ClickType.LEFT);
    var top = a.getOpenInventory().getTopInventory();
    int preset = slotNamed(top, 9, top.getSize() - 9, "Охотничья");
    assertTrue(preset >= 0);
    passes.put(a.getUniqueId(), "false");
    click(a, preset, ClickType.LEFT);
    assertEquals(List.of("name"), personal.state(a.getUniqueId()).ids());
  }

  @Test
  void losingThePassHidesHuntRowsWithoutDeletingTheSavedChoice() throws Exception {
    enableHunt();
    huntLines();
    var a = join("Alice");
    passes.put(a.getUniqueId(), "true");
    personal
        .state(a.getUniqueId())
        .replaceWith(
            List.of(
                new PersonalState.Entry("name", 0), new PersonalState.Entry("hunt_builtin", 0)));
    assertTrue(
        personal.content(a).orElseThrow().lines().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(text -> text.contains("Охотник:")));
    passes.put(a.getUniqueId(), "false");
    assertTrue(
        personal.content(a).orElseThrow().lines().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .noneMatch(text -> text.contains("Охотник:")));
    assertTrue(personal.state(a.getUniqueId()).has("hunt_builtin"));
    passes.put(a.getUniqueId(), "true");
    assertTrue(
        personal.content(a).orElseThrow().lines().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(text -> text.contains("Охотник:")));
  }
}
