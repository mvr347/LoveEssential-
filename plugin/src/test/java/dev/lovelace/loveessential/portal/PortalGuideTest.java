package dev.lovelace.loveessential.portal;

import static org.junit.jupiter.api.Assertions.*;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.storage.SqliteStore;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import java.util.*;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.chat.SignedMessage;
import org.bukkit.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PortalGuideTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  PlayerMock admin;
  Portals portals;
  final Map<String, String> buttons = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    var stop = plugin.module("scoreboard", false);
    waitFor(stop::isDone);
    stop.join();
    var f = LoveEssentialPlugin.class.getDeclaredField("gameplay");
    f.setAccessible(true);
    portals = ((Gameplay) f.get(plugin)).portals();
    admin = server.addPlayer("PortalAdmin");
    admin.setOp(true);
    portals.command(admin, new String[] {"wizard"});
    waitFor(() -> !portals.busy(admin));
    readButtons();
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  void waitFor(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      server.getScheduler().performOneTick();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean());
  }

  void collect(Component component) {
    if (component.clickEvent() != null
        && component.clickEvent().action() == ClickEvent.Action.RUN_COMMAND
        && component.clickEvent().payload() instanceof ClickEvent.Payload.Text text) {
      String command = text.value();
      buttons.put(command.substring(command.lastIndexOf(' ') + 1), command);
    }
    component.children().forEach(this::collect);
  }

  void readButtons() {
    Component c;
    while ((c = admin.nextComponentMessage()) != null) collect(c);
  }

  void click(String key) throws Exception {
    String command = buttons.get(key);
    assertNotNull(command, "Missing chat control " + key);
    assertTrue(server.dispatchCommand(admin, command.substring(1)));
    waitFor(() -> !portals.busy(admin));
    readButtons();
  }

  void text(String value) throws Exception {
    var c = Component.text(value);
    var event =
        new AsyncChatEvent(
            false,
            admin,
            Set.of(admin),
            ChatRenderer.defaultRenderer(),
            c,
            c,
            SignedMessage.system(value, c));
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
    server.getScheduler().performTicks(2);
    waitFor(() -> !portals.busy(admin));
    readButtons();
  }

  void region() throws Exception {
    click("next");
    assertEquals(1, portals.draft(admin).value().step());
    click("here");
    admin.teleport(admin.getLocation().add(2, 2, 0));
    click("here");
    assertEquals(3, portals.draft(admin).value().step());
  }

  @Test
  void chatStepsAreDurableAndOldButtonsCannotAdvanceANewerStep() throws Exception {
    assertTrue(portals.draft(admin).revision() > 0, "The initial draft is saved before any edit");
    String stale = buttons.get("next");
    region();
    long revision = portals.draft(admin).revision();
    server.dispatchCommand(admin, stale.substring(1));
    assertEquals(revision, portals.draft(admin).revision());
    click("destination");
    click("next");
    assertEquals(4, portals.draft(admin).value().step());
    click("pause");
    portals.command(admin, new String[] {"wizard"});
    waitFor(() -> !portals.busy(admin));
    assertEquals(4, portals.draft(admin).value().step());
    var field = LoveEssentialPlugin.class.getDeclaredField("store");
    field.setAccessible(true);
    var store = (SqliteStore) field.get(plugin);
    var saved =
        store
            .get("portal-draft", admin.getUniqueId().toString(), Portals.Draft.class)
            .join()
            .orElseThrow();
    assertEquals(4, saved.value().step());
    assertEquals(3, saved.value().corners());
  }

  @Test
  void slashCommandInputIsSavedWithoutExecutingAndPublishedOnlyAtTheEnd() throws Exception {
    var calls = new AtomicInteger();
    server
        .getCommandMap()
        .register(
            "test",
            new org.bukkit.command.Command("portalprobe") {
              public boolean execute(
                  org.bukkit.command.CommandSender sender, String label, String[] args) {
                calls.incrementAndGet();
                return true;
              }
            });
    plugin.getConfig().set("portal.allowed-player-commands", List.of("portalprobe"));
    region();
    click("command-player");
    var event = new PlayerCommandPreprocessEvent(admin, "/portalprobe {player}");
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
    waitFor(() -> !portals.busy(admin));
    readButtons();
    assertEquals(0, calls.get());
    assertEquals(
        "portalprobe {player}",
        portals.draft(admin).value().value().actions().getFirst().args().get("value"));
    assertTrue(portals.all().isEmpty());
    click("next");
    click("next");
    click("next");
    click("publish");
    assertEquals(1, portals.all().size());
    assertEquals(0, calls.get());
  }

  @Test
  void invalidInputRetainsTheStepAndCancelButtonKeepsTheDraft() throws Exception {
    click("name");
    text("Дорога домой");
    assertEquals(1, portals.draft(admin).value().step());
    click("here");
    admin.teleport(admin.getLocation().add(1, 2, 0));
    click("here");
    click("destination");
    click("next");
    click("color");
    text("wrong-color");
    assertEquals("B68CFF", portals.draft(admin).value().value().color());
    click("input-back");
    assertEquals(4, portals.draft(admin).value().step());
    click("discard");
    assertNotNull(buttons.get("confirm-discard"));
    click("input-back");
    assertEquals(4, portals.draft(admin).value().step());
  }

  @Test
  void lostPermissionAndForeignButtonsCannotModifyTheDraft() throws Exception {
    String next = buttons.get("next");
    long revision = portals.draft(admin).revision();
    var other = server.addPlayer("Other");
    other.setOp(true);
    server.dispatchCommand(other, next.substring(1));
    assertEquals(revision, portals.draft(admin).revision());
    admin.setOp(false);
    server.dispatchCommand(admin, next.substring(1));
    assertEquals(revision, portals.draft(admin).revision());
  }

  @Test
  void everyRegisteredParticleHasARussianNameAndAValidPayload() throws Exception {
    var lang = new dev.lovelace.loveessential.lang.Lang(plugin);
    for (var particle : Particle.values()) {
      assertTrue(PortalParticles.supported(particle), particle.name());
      assertTrue(
          lang.contains("portal-particles.names." + particle.name().toLowerCase(Locale.ROOT)),
          particle.name());
      Object data = PortalParticles.data(particle, "B68CFF", admin.getLocation());
      if (particle.getDataType() == Void.class) assertNull(data);
      else assertInstanceOf(particle.getDataType(), data, particle.name());
    }
  }

  @Test
  void particleCatalogHasPagesAndSelectsTypedParticlesWithoutDroppingTheGuide() throws Exception {
    region();
    click("destination");
    click("next");
    click("particles");
    var menu = admin.getOpenInventory().getTopInventory();
    assertEquals(54, menu.getSize());
    assertNotNull(menu.getItem(44));
    portals.particleMenu(admin, "all", "гейзер", 0);
    menu = admin.getOpenInventory().getTopInventory();
    server
        .getPluginManager()
        .callEvent(
            new InventoryClickEvent(
                admin.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                19,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL));
    server.getScheduler().performTicks(2);
    waitFor(() -> !portals.busy(admin));
    readButtons();
    assertTrue(portals.draft(admin).value().value().particle().startsWith("GEYSER"));
    assertEquals(4, portals.draft(admin).value().step());
  }

  @Test
  void anInvalidBlockSelectionDoesNotAdvanceOrCorruptTheDraft() throws Exception {
    plugin.getConfig().set("portal.max-volume", 1);
    click("next");
    click("here");
    click("select");
    var old = portals.draft(admin);
    var at = old.value().value().first();
    var event =
        new org.bukkit.event.player.PlayerInteractEvent(
            admin,
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
            null,
            admin.getWorld().getBlockAt((int) at.x() + 5, (int) at.y() + 2, (int) at.z()),
            org.bukkit.block.BlockFace.UP,
            org.bukkit.inventory.EquipmentSlot.HAND);
    assertDoesNotThrow(() -> server.getPluginManager().callEvent(event));
    assertTrue(event.isCancelled());
    assertEquals(old.revision(), portals.draft(admin).revision());
    assertEquals(2, portals.draft(admin).value().step());
  }

  @Test
  void actualAsynchronousChatInputIsAppliedOnTheMainThread() throws Exception {
    click("name");
    String value = "Асинхронный портал";
    var c = Component.text(value);
    var event =
        new AsyncChatEvent(
            true,
            admin,
            Set.of(admin),
            ChatRenderer.defaultRenderer(),
            c,
            c,
            SignedMessage.system(value, c));
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    server.getPluginManager().callEvent(event);
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });
    thread.join(2000);
    assertFalse(thread.isAlive());
    assertNull(failure.get());
    waitFor(() -> portals.draft(admin).value().step() == 1);
    assertTrue(event.isCancelled());
    assertEquals(1, portals.draft(admin).value().step());
  }

  @Test
  void quittingDuringNameSaveKeepsTheDurableDraftWithoutReopeningAMenu() throws Exception {
    click("name");
    var id = admin.getUniqueId();
    var c = Component.text("Сохранено при выходе");
    var event =
        new AsyncChatEvent(
            false,
            admin,
            Set.of(admin),
            ChatRenderer.defaultRenderer(),
            c,
            c,
            SignedMessage.system("Сохранено при выходе", c));
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
    admin.disconnect();
    var field = LoveEssentialPlugin.class.getDeclaredField("store");
    field.setAccessible(true);
    var store = (SqliteStore) field.get(plugin);
    waitFor(
        () ->
            store
                .get("portal-draft", id.toString(), Portals.Draft.class)
                .join()
                .map(
                    doc ->
                        doc.value().step() == 1
                            && doc.value().value().nameKey().startsWith("portal-text."))
                .orElse(false));
    assertFalse(admin.isOnline());
  }

  @Test
  void aCommandOutsideTheAllowlistIsNotStoredOrExecuted() throws Exception {
    region();
    click("command-console");
    admin.addAttachment(plugin, "loveessential.admin", true);
    admin.setOp(false);
    var event = new PlayerCommandPreprocessEvent(admin, "/op PortalAdmin");
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
    assertFalse(admin.isOp());
    assertTrue(portals.draft(admin).value().value().actions().isEmpty());
    assertEquals(3, portals.draft(admin).value().step());
  }

  @Test
  void anOlderWizardCannotCancelOrOverwriteAnotherPromptsInput() throws Exception {
    String old = buttons.get("next");
    var lang = new dev.lovelace.loveessential.lang.Lang(plugin);
    dev.lovelace.loveessential.gui.ChatPrompt.send(
        admin,
        lang,
        "other-module-token",
        Component.text("Other input"),
        Component.text("Private"),
        "",
        "");
    server.dispatchCommand(admin, old.substring(1));
    assertEquals(0, portals.draft(admin).value().step());
    assertTrue(
        dev.lovelace.loveessential.gui.ChatPrompt.owns(admin.getUniqueId(), "other-module-token"));
    portals.command(admin, new String[] {"wizard"});
    waitFor(() -> !portals.busy(admin));
    readButtons();
    click("next");
    assertEquals(1, portals.draft(admin).value().step());
  }

  @Test
  void queuedSettingsInputCannotBeAppliedAfterSwitchingToThePortalGuide() throws Exception {
    var field = LoveEssentialPlugin.class.getDeclaredField("menus");
    field.setAccessible(true);
    Object menus = field.get(plugin);
    // Exercise the real chat fallback; MockBukkit does not implement Paper's dialog registry.
    ((dev.lovelace.loveessential.gui.ControlMenus) menus).nativeDialogs(null);
    var ask =
        menus
            .getClass()
            .getDeclaredMethod(
                "ask",
                org.bukkit.entity.Player.class,
                String.class,
                java.util.function.Predicate.class,
                java.util.function.Consumer.class,
                Runnable.class);
    ask.setAccessible(true);
    var called = new AtomicInteger();
    ask.invoke(
        menus,
        admin,
        "gui.prompt-value",
        (java.util.function.Predicate<org.bukkit.entity.Player>) p -> true,
        (java.util.function.Consumer<String>) value -> called.incrementAndGet(),
        (Runnable) () -> {});
    var c = Component.text("старое значение");
    var event =
        new AsyncChatEvent(
            true,
            admin,
            Set.of(admin),
            ChatRenderer.defaultRenderer(),
            c,
            c,
            SignedMessage.system("старое значение", c));
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread old =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    server.getPluginManager().callEvent(event);
                  } catch (Throwable e) {
                    failure.set(e);
                  }
                });
    old.join(2000);
    assertFalse(old.isAlive());
    assertNull(failure.get());
    assertTrue(event.isCancelled());
    portals.command(admin, new String[] {"wizard"});
    waitFor(() -> !portals.busy(admin));
    readButtons();
    click("name");
    server.getScheduler().performTicks(2);
    assertEquals(0, called.get());
    text("Новый портал");
    assertEquals(1, portals.draft(admin).value().step());
    assertEquals(0, called.get());
  }

  @Test
  void queuedPortalInputCannotOverwriteANewerModulesPrompt() throws Exception {
    click("name");
    long revision = portals.draft(admin).revision();
    var c = Component.text("Старое имя");
    var event =
        new AsyncChatEvent(
            true,
            admin,
            Set.of(admin),
            ChatRenderer.defaultRenderer(),
            c,
            c,
            SignedMessage.system("Старое имя", c));
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    server.getPluginManager().callEvent(event);
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });
    thread.join(2000);
    assertFalse(thread.isAlive());
    assertNull(failure.get());
    assertTrue(event.isCancelled());
    dev.lovelace.loveessential.gui.ChatPrompt.claim(admin.getUniqueId(), "new-module-token");
    server.getScheduler().performTicks(2);
    assertEquals(revision, portals.draft(admin).revision());
    assertEquals(0, portals.draft(admin).value().step());
    assertTrue(
        dev.lovelace.loveessential.gui.ChatPrompt.owns(admin.getUniqueId(), "new-module-token"));
  }
}
