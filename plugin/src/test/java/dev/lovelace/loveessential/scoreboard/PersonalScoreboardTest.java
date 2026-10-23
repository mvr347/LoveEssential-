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

  @Test
  void theCommandOpensTheMenuAndItFollowsTheLayoutStandard() throws Exception {
    var a = join("Alice");
    server.dispatchCommand(a, "scoreboard");
    tick(2);
    var top = a.getOpenInventory().getTopInventory();
    assertInstanceOf(GuiLayout.class, top.getHolder(), "the command must open the player's menu");
    assertEquals(54, top.getSize());
    assertEquals(List.of(), GuiRules.violations(top, false));
    assertEquals(List.of(), GuiRules.unresolvedText(top));
    // Cards 1 and 2 hold the preset lines, card 3 is an empty slot.
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[0])).contains("Имя"));
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[1])).contains("Мир"));
    assertTrue(name(top.getItem(PersonalLayout.CARD_SLOTS[2])).contains("Слот 3"));
  }

  @Test
  void cardClicksMoveAndRemoveLinesAndTheChoiceIsStored() throws Exception {
    var a = join("Alice");
    server.dispatchCommand(a, "scoreboard");
    tick(2);
    click(a, PersonalLayout.CARD_SLOTS[1], ClickType.LEFT);
    assertEquals(List.of("world", "name"), personal.state(a.getUniqueId()).ids(), "LMB moves a card up");
    click(a, PersonalLayout.CARD_SLOTS[0], ClickType.RIGHT);
    assertEquals(List.of("name", "world"), personal.state(a.getUniqueId()).ids(), "RMB moves a card down");
    click(a, PersonalLayout.CARD_SLOTS[0], ClickType.MIDDLE);
    assertEquals(List.of("world"), personal.state(a.getUniqueId()).ids(), "MMB removes a line");
    assertEquals(List.of(), GuiRules.violations(a.getOpenInventory().getTopInventory(), false));

    var store = store();
    waitFor(
        () ->
            store
                .get(PersonalSidebar.KIND, a.getUniqueId().toString(), PersonalState.Stored.class)
                .join()
                .map(d -> d.value().entries().size() == 1)
                .orElse(false));
  }

  dev.lovelace.loveessential.storage.SqliteStore store() throws Exception {
    var field = LoveEssentialPlugin.class.getDeclaredField("store");
    field.setAccessible(true);
    return (dev.lovelace.loveessential.storage.SqliteStore) field.get(plugin);
  }

  @Test
  void aCategoryOpensASubmenuWithBackAndTogglesLines() throws Exception {
    var a = join("Alice");
    server.dispatchCommand(a, "scoreboard");
    tick(2);
    // Two categories sit in the middle of the row.
    int[] positions = PersonalLayout.categorySlots(2);
    click(a, positions[1], ClickType.LEFT);
    var sub = a.getOpenInventory().getTopInventory();
    assertInstanceOf(GuiLayout.class, sub.getHolder());
    assertEquals(List.of(), GuiRules.violations(sub, true));
    assertEquals(List.of(), GuiRules.unresolvedText(sub));
    // The "world" category holds world and online; enable "online" with LMB.
    int target = -1;
    for (int slot = 9; slot < sub.getSize() - 9; slot++)
      if (name(sub.getItem(slot)).contains("Онлайн")) target = slot;
    assertTrue(target >= 0, "the online line is in the submenu");
    click(a, target, ClickType.LEFT);
    assertEquals(List.of("name", "world", "online"), personal.state(a.getUniqueId()).ids());
  }

  @Test
  void aPresetReplacesTheCurrentSet() throws Exception {
    var a = join("Alice");
    personal.state(a.getUniqueId()).replaceWith(List.of(new PersonalState.Entry("online", 0)));
    server.dispatchCommand(a, "scoreboard");
    tick(2);
    var top = a.getOpenInventory().getTopInventory();
    int preset = -1;
    for (int slot = 2; slot <= 7; slot++) if (name(top.getItem(slot)).contains("Пара")) preset = slot;
    assertTrue(preset >= 0, "the preset button is in the header");
    click(a, preset, ClickType.LEFT);
    assertEquals(List.of("name", "world"), personal.state(a.getUniqueId()).ids());
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
