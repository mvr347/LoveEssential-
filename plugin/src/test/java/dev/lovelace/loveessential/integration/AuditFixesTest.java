package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.condition.ActionEngine;
import dev.lovelace.loveessential.gui.GuiRules;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.portal.PortalDefinition;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.event.inventory.*;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Regression tests for the problems found by the 2026-10-22 audit. */
class AuditFixesTest {
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
    await(plugin.module("tab", false));
    await(plugin.module("scoreboard", false));
    await(plugin.module("starter", false));
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
    assertTrue(test.getAsBoolean(), "async server operation did not finish");
  }

  <T> T await(CompletableFuture<T> future) throws Exception {
    waitFor(future::isDone);
    return future.get(1, TimeUnit.SECONDS);
  }

  <T> T internal(String name, Class<T> type) throws Exception {
    var f = LoveEssentialPlugin.class.getDeclaredField(name);
    f.setAccessible(true);
    return type.cast(f.get(plugin));
  }

  PlayerMock admin() {
    var p = server.addPlayer("Builder");
    p.setOp(true);
    return p;
  }

  // --- startup tolerance ---------------------------------------------------------------

  PortalDefinition portal(String id, Position first, Position second) {
    return new PortalDefinition(
        id, "common.done", first, second, second, true, "", List.of("SURVIVAL"), 0, 0, true, false,
        "DUST", "B68CFF", true, .5, List.of());
  }

  @Test
  void aPortalAboveTheCurrentVolumeLimitIsSkippedInsteadOfDisablingThePlugin() throws Exception {
    var portals = internal("gameplay", Gameplay.class).portals();
    var world = server.getWorld("world");
    var origin = world.getSpawnLocation();
    var big =
        portal(
            "portal_big",
            Position.of(origin.clone()),
            Position.of(origin.clone().add(20, 20, 20)));
    var small =
        portal(
            "portal_small",
            Position.of(origin.clone()),
            Position.of(origin.clone().add(2, 2, 2)));
    await(portals.save(big, 0, "test"));
    await(portals.save(small, 0, "test"));
    // The admin lowers the limit; the stored portal is now invalid but must not be fatal.
    plugin.getConfig().set("portal.max-volume", 100);
    await(portals.load());
    var ids = portals.all().stream().map(d -> d.id()).sorted().toList();
    assertEquals(List.of("portal_small"), ids);
  }

  // --- /home destination ---------------------------------------------------------------

  List<String> messages(PlayerMock p) {
    var out = new ArrayList<String>();
    net.kyori.adventure.text.Component m;
    while ((m = p.nextComponentMessage()) != null)
      out.add(
          net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(m));
    return out;
  }

  @Test
  void homeIsRefusedWhenItsWorldIsDeniedForTheModule() throws Exception {
    var gameplay = internal("gameplay", Gameplay.class);
    var lang = internal("lang", dev.lovelace.loveessential.lang.Lang.class);
    var p = admin();
    server.dispatchCommand(p, "sethome");
    waitFor(() -> !gameplay.names(p, "home").isEmpty());
    var other = server.addSimpleWorld("other");
    p.teleport(other.getSpawnLocation());
    String denial =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(lang.render("module-access.world", Map.of("world", "world")));

    messages(p);
    server.dispatchCommand(p, "home");
    server.getScheduler().performTicks(5);
    assertFalse(
        messages(p).stream().anyMatch(m -> m.contains(denial)),
        "without rules the home world is allowed");

    plugin.getConfig().set("home.access.denied-worlds", List.of("world"));
    server.dispatchCommand(p, "home");
    server.getScheduler().performTicks(5);
    assertTrue(
        messages(p).stream().anyMatch(m -> m.contains(denial)),
        "the denied destination world must be reported to the player");
  }

  // --- charge rollback -----------------------------------------------------------------

  LoveEconomy economy(AtomicLong balance) {
    return (LoveEconomy)
        Proxy.newProxyInstance(
            LoveEconomy.class.getClassLoader(),
            new Class<?>[] {LoveEconomy.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "charge":
                  long amount = (Long) args[1];
                  if (balance.get() < amount) return false;
                  balance.addAndGet(-amount);
                  return true;
                case "give":
                  balance.addAndGet((Long) args[1]);
                  return null;
                case "canFit":
                case "has":
                  return true;
                case "balance":
                  return balance.get();
                case "currencyName":
                  return "coin";
                case "denominations":
                case "allDenominations":
                  return List.of();
                case "valueOf":
                  return 0L;
                default:
                  if (method.isDefault())
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                  return null;
              }
            });
  }

  @Test
  void chargeIsRefundedWhenALaterActionFailsAndNeverTakenWhenOneIsUnknown() throws Exception {
    var balance = new AtomicLong(1000);
    server
        .getServicesManager()
        .register(LoveEconomy.class, economy(balance), plugin, ServicePriority.Normal);
    var actions = internal("actions", ActionEngine.class);
    actions.register("test:fail", (p, a) -> false);
    var p = admin();
    var charge = new Action("charge", Map.of("amount", "100"));

    assertTrue(actions.execute(p, List.of(charge)));
    assertEquals(900, balance.get(), "a plain charge still works");

    assertFalse(actions.execute(p, List.of(charge, new Action("test:fail", Map.of()))));
    assertEquals(900, balance.get(), "money taken before a failing action must come back");

    assertFalse(actions.execute(p, List.of(charge, new Action("test:missing", Map.of()))));
    assertEquals(900, balance.get(), "an unknown action must be rejected before anything is taken");
  }

  // --- raw markup in menus -------------------------------------------------------------

  @Test
  void moduleToggleTilesShowNoRawMarkup() throws Exception {
    var p = admin();
    for (String command : List.of("le module npc", "le module hologram", "le")) {
      server.dispatchCommand(p, command);
      server.getScheduler().performTicks(2);
      var top = p.getOpenInventory().getTopInventory();
      assertNotNull(top, command);
      assertEquals(List.of(), GuiRules.unresolvedText(top), command);
    }
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

  @Test
  void hologramPageManagerHasAResolvedTitleAndNoRawMarkup() throws Exception {
    var holograms = internal("gameplay", Gameplay.class).holograms();
    var p = admin();
    var id = holograms.nextId();
    await(
        holograms.save(
            new HologramService.Hologram(
                id,
                Position.of(server.getWorld("world").getSpawnLocation().add(0, 2, 0)),
                List.of(new HologramService.Page(List.of(new HologramService.Line("common.done", "")), null)),
                0,
                true,
                .3,
                48),
            0));
    server.dispatchCommand(p, "hologram edit " + id);
    server.getScheduler().performTicks(2);
    var editor = p.getOpenInventory().getTopInventory();
    assertEquals(List.of(), GuiRules.unresolvedText(editor), "editor");
    int pages = -1;
    for (int slot = 0; slot < editor.getSize(); slot++)
      if (GuiRules.name(editor.getItem(slot)).contains("Страницы")) pages = slot;
    assertTrue(pages >= 0, "the editor must have a Pages button");
    click(p, pages);
    var manager = p.getOpenInventory().getTopInventory();
    assertNotSame(editor, manager);
    assertEquals(List.of(), GuiRules.unresolvedText(manager), "page manager");
  }

  // --- /skin ---------------------------------------------------------------------------

  @Test
  void aSavedSkinIsAppliedAtLoginWithoutTouchingNameOrUuid() throws Exception {
    var store = internal("store", dev.lovelace.loveessential.storage.SqliteStore.class);
    var id = UUID.randomUUID();
    await(store.profile(id, "loveessential.skin", "Notch\nVALUE\nSIG"));
    var player = new PlayerMock(server, "Skinny", id);
    server.addPlayer(player);
    waitFor(
        () ->
            player.getPlayerProfile().getProperties().stream()
                .anyMatch(property -> property.getName().equals("textures")));
    var textures =
        player.getPlayerProfile().getProperties().stream()
            .filter(property -> property.getName().equals("textures"))
            .findFirst()
            .orElseThrow();
    assertEquals("VALUE", textures.getValue());
    assertEquals("SIG", textures.getSignature());
    assertEquals("Skinny", player.getName());
    assertEquals(id, player.getUniqueId());
  }

  @Test
  void skinChangesAreRateLimited() throws Exception {
    var lang = internal("lang", dev.lovelace.loveessential.lang.Lang.class);
    var p = admin();
    String limited =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(lang.render("skin.cooldown", Map.of("seconds", "10")));
    messages(p);
    server.dispatchCommand(p, "skin Notch");
    assertFalse(messages(p).stream().anyMatch(m -> m.contains(limited)), "first call is allowed");
    server.dispatchCommand(p, "skin Dream");
    var after = messages(p);
    assertTrue(
        after.stream().anyMatch(m -> m.contains(limited)),
        "a second call right away must be refused");
  }

  // --- navigation ----------------------------------------------------------------------

  String menuId(PlayerMock p) {
    return ((dev.lovelace.loveessential.gui.GuiLayout) p.getOpenInventory().getTopInventory().getHolder())
        .guiId();
  }

  @Test
  void backFromThePageManagerReturnsToAFreshEditorEvenAfterPagesChanged() throws Exception {
    var holograms = internal("gameplay", Gameplay.class).holograms();
    var p = admin();
    var id = holograms.nextId();
    await(
        holograms.save(
            new HologramService.Hologram(
                id,
                Position.of(server.getWorld("world").getSpawnLocation().add(0, 2, 0)),
                List.of(new HologramService.Page(List.of(new HologramService.Line("common.done", "")), null)),
                0,
                true,
                .3,
                48),
            0));
    server.dispatchCommand(p, "hologram edit " + id);
    server.getScheduler().performTicks(2);
    assertEquals("holo.title", menuId(p));
    var editor = p.getOpenInventory().getTopInventory();
    int pages = -1;
    for (int slot = 0; slot < editor.getSize(); slot++)
      if (GuiRules.name(editor.getItem(slot)).contains("Страницы")) pages = slot;
    click(p, pages);
    assertEquals("holo.pages-title", menuId(p));
    // Adding a page changes the hologram's revision, which makes the editor underneath stale.
    var manager = p.getOpenInventory().getTopInventory();
    int add = -1;
    for (int slot = 0; slot < manager.getSize(); slot++)
      if (GuiRules.name(manager.getItem(slot)).contains("Добавить страницу")) add = slot;
    assertTrue(add >= 0);
    click(p, add);
    waitFor(() -> holograms.get(id).orElseThrow().value().pages().size() == 2);
    server.getScheduler().performTicks(3);
    var current = p.getOpenInventory().getTopInventory();
    assertEquals("holo.pages-title", menuId(p));
    int back = current.getSize() - 2;
    assertTrue(GuiRules.name(current.getItem(back)).contains("Назад"), "the manager must offer Back");
    click(p, back);
    assertNotNull(p.getOpenInventory().getTopInventory(), "Back must not just close the menu");
    assertEquals("holo.title", menuId(p));
  }

  // --- nicknames -----------------------------------------------------------------------

  @Test
  void aNicknameLookingLikeAnOfflinePlayersRealNameIsRefused() throws Exception {
    var store = internal("store", dev.lovelace.loveessential.storage.SqliteStore.class);
    var lang = internal("lang", dev.lovelace.loveessential.lang.Lang.class);
    var nicknames = internal("nicknames", dev.lovelace.loveessential.module.Nicknames.class);
    await(store.profile(UUID.randomUUID(), "loveessential.player-info", "{\"player\":\"Notch\"}"));
    await(nicknames.refreshDirectory());
    String taken =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(lang.render("nick.taken"));
    var p = admin();
    messages(p);
    // The player's own nickname state loads asynchronously; retry until the command reaches the check.
    boolean refused = false;
    for (int attempt = 0; attempt < 40 && !refused; attempt++) {
      server.dispatchCommand(p, "nick set N\u043etch"); // the "o" is Cyrillic
      server.getScheduler().performTicks(3);
      Thread.sleep(25);
      refused = messages(p).stream().anyMatch(m -> m.contains(taken));
    }
    assertTrue(refused, "the look-alike of an offline player's name must be reported as taken");
    server.dispatchCommand(p, "nick set Quartz");
    server.getScheduler().performTicks(3);
    assertFalse(
        messages(p).stream().anyMatch(m -> m.contains(taken)), "an unrelated name stays allowed");
  }
}
