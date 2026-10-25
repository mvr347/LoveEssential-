package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.api.NpcDefinition;
import dev.lovelace.loveessential.api.Position;
import dev.lovelace.loveessential.command.NpcCommandSelection;
import dev.lovelace.loveessential.npc.NpcService;
import dev.lovelace.loveessential.wizard.NpcDraft;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class NpcSelectedCommandsTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  NpcService npcs;
  PlayerMock admin;
  NpcDefinition first, second;

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
    var field = LoveEssentialPlugin.class.getDeclaredField("npcs");
    field.setAccessible(true);
    npcs = (NpcService) field.get(plugin);
    admin = server.addPlayer("Builder");
    admin.setOp(true);
    first = create("guard");
    // An internal numeric ID must never be resolved a second time as a public number.
    second = create("1");
    drain(admin);
  }

  @AfterEach
  void end() {
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

  <T> T await(CompletableFuture<T> future) throws Exception {
    waitFor(future::isDone);
    return future.join();
  }

  NpcDefinition create(String id) throws Exception {
    var n = NpcDraft.create(admin.getUniqueId(), Position.of(admin.getLocation())).npc();
    return await(npcs.save(new NpcDefinition(id, 0, n.owner(), "@inline:<gold>Страж",
        n.entityType(), n.spawn(), n.settings(), n.visibility(), n.access(), n.route(),
        n.dialog(), n.actions(), true), 0, "test"));
  }

  void select(NpcDefinition npc) {
    server.dispatchCommand(admin, "npc sel " + npcs.number(npc.id()));
    drain(admin);
  }

  static List<Component> drain(PlayerMock player) {
    var result = new ArrayList<Component>();
    Component next;
    while ((next = player.nextComponentMessage()) != null) result.add(next);
    return result;
  }

  static List<String> commands(List<Component> messages) {
    var result = new ArrayList<String>();
    for (var component : messages) collect(component, result);
    return result;
  }

  static void collect(Component component, List<String> commands) {
    if (component.clickEvent() != null && component.clickEvent().action() == ClickEvent.Action.RUN_COMMAND
        && component.clickEvent().payload() instanceof ClickEvent.Payload.Text text)
      commands.add(text.value());
    component.children().forEach(child -> collect(child, commands));
  }

  String requestDelete() {
    drain(admin);
    server.dispatchCommand(admin, "npc delete");
    var messages = drain(admin);
    String text = messages.stream().map(PlainTextComponentSerializer.plainText()::serialize)
        .reduce("", String::concat);
    assertTrue(text.contains("Страж"), text);
    assertTrue(text.contains("[Отмена]"), text);
    return commands(messages).stream().filter(c -> c.startsWith("/npc delete confirm ")).findFirst().orElseThrow();
  }

  @Test
  void deleteWithoutIdUsesTheSelectionAndAWorkingConfirmationButton() throws Exception {
    select(first);
    String confirm = requestDelete();
    assertTrue(npcs.get(first.id()).isPresent(), "the prompt must not delete anything");
    server.dispatchCommand(admin, confirm.substring(1));
    waitFor(() -> npcs.get(first.id()).isEmpty());
    assertTrue(npcs.get(second.id()).isPresent());
    server.dispatchCommand(admin, "npc info");
    assertTrue(drain(admin).stream().anyMatch(c -> PlainTextComponentSerializer.plainText()
        .serialize(c).contains("Сначала выберите")), "deletion clears selection");
  }

  @Test
  void changingSelectionInvalidatesOldButtonsInsteadOfDeletingTheNewTarget() {
    select(first);
    String confirm = requestDelete();
    select(second);
    server.dispatchCommand(admin, confirm.substring(1));
    assertEquals(2, npcs.all().size());
    assertTrue(drain(admin).stream().anyMatch(c -> PlainTextComponentSerializer.plainText()
        .serialize(c).contains("недействительно")));
  }

  @Test
  void aChangedNpcNeedsANewConfirmation() throws Exception {
    select(first);
    String confirm = requestDelete();
    await(npcs.save(first.withNameKey("@inline:Изменён"), first.revision(), "test"));
    server.dispatchCommand(admin, confirm.substring(1));
    assertEquals(2, npcs.all().size());
  }

  @Test
  void confirmationBelongsToTheRequestingPlayerAndCannotBeUsedTwice() throws Exception {
    select(first);
    String confirm = requestDelete();
    var other = server.addPlayer("Other");
    other.setOp(true);
    server.dispatchCommand(other, confirm.substring(1));
    assertEquals(2, npcs.all().size());
    server.dispatchCommand(admin, confirm.substring(1));
    waitFor(() -> npcs.get(first.id()).isEmpty());
    server.dispatchCommand(admin, confirm.substring(1));
    assertTrue(npcs.get(second.id()).isPresent());
  }

  @Test
  void cancellationAndPermissionChecksPreserveTheNpc() {
    select(first);
    String confirm = requestDelete();
    server.dispatchCommand(admin, confirm.replace("delete confirm", "delete cancel").substring(1));
    server.dispatchCommand(admin, confirm.substring(1));
    assertEquals(2, npcs.all().size());
    confirm = requestDelete();
    admin.addAttachment(plugin, "loveessential.npc.delete", false);
    server.dispatchCommand(admin, confirm.substring(1));
    assertEquals(2, npcs.all().size());
  }

  @Test
  void publicNumbersAreResolvedOnlyOnceForSelectionAndDeletion() throws Exception {
    select(second);
    String confirm = requestDelete();
    server.dispatchCommand(admin, confirm.substring(1));
    waitFor(() -> npcs.get(second.id()).filter(n -> n.id().equals(second.id())).isEmpty());
    assertTrue(npcs.get(first.id()).isPresent());
  }

  @Test
  void selectedEnableDisableAndRussianAliasesWorkWithoutAnId() throws Exception {
    select(first);
    server.dispatchCommand(admin, "нпс выключить");
    waitFor(() -> !npcs.get(first.id()).orElseThrow().enabled());
    server.getScheduler().performTicks(2);
    assertTrue(commands(drain(admin)).contains("/npc enable " + npcs.number(first.id())));
    server.dispatchCommand(admin, "npc enable");
    waitFor(() -> npcs.get(first.id()).orElseThrow().enabled());
    server.getScheduler().performTicks(2);
    assertTrue(commands(drain(admin)).contains("/npc disable " + npcs.number(first.id())));
    server.dispatchCommand(admin, "npc снять");
    server.dispatchCommand(admin, "npc delete");
    assertTrue(commands(drain(admin)).contains("/npc sel"));
    assertEquals(2, npcs.all().size());
  }

  @Test
  void selectedEditorSectionsKeepTheSameTargetWithoutRepeatingItsNumber() throws Exception {
    select(second);
    var field = LoveEssentialPlugin.class.getDeclaredField("wizard");
    field.setAccessible(true);
    var wizard = (dev.lovelace.loveessential.wizard.NpcWizard) field.get(plugin);
    server.dispatchCommand(admin, "npc edit");
    waitFor(() -> wizard.editing(admin));
    for (String command : List.of("skin", "schedule", "path", "dialog")) {
      server.dispatchCommand(admin, "npc " + command);
      var sessions = wizard.getClass().getDeclaredField("sessions");
      sessions.setAccessible(true);
      var working = ((java.util.Map<?, ?>) sessions.get(wizard)).get(admin.getUniqueId());
      var draft = working.getClass().getDeclaredField("draft");
      draft.setAccessible(true);
      assertEquals(second.id(), ((NpcDraft) draft.get(working)).npc().id());
      assertEquals(java.util.Map.of("skin", 1, "schedule", 6, "path", 4, "dialog", 5).get(command),
          ((NpcDraft) draft.get(working)).step());
    }
  }

  @Test
  void cloneWithoutAnIdCreatesANewDraftWithoutChangingTheSource() throws Exception {
    select(first);
    server.dispatchCommand(admin, "npc clone");
    var field = LoveEssentialPlugin.class.getDeclaredField("wizard");
    field.setAccessible(true);
    var wizard = (dev.lovelace.loveessential.wizard.NpcWizard) field.get(plugin);
    waitFor(() -> wizard.editing(admin));
    var sessions = wizard.getClass().getDeclaredField("sessions");
    sessions.setAccessible(true);
    var working = ((java.util.Map<?, ?>) sessions.get(wizard)).get(admin.getUniqueId());
    var draft = working.getClass().getDeclaredField("draft");
    draft.setAccessible(true);
    assertNotEquals(first.id(), ((NpcDraft) draft.get(working)).npc().id());
    assertEquals(2, npcs.all().size(), "the copy stays a draft until published");
  }

  @Test
  void expiredConfirmationsDoNotDeleteAnything() throws Exception {
    select(first);
    String confirm = requestDelete();
    var selection = selection();
    var field = NpcCommandSelection.class.getDeclaredField("pending");
    field.setAccessible(true);
    var pending = (com.github.benmanes.caffeine.cache.Cache<?, ?>) field.get(selection);
    pending.policy().expireAfterWrite().orElseThrow().setExpiresAfter(Duration.ZERO);
    pending.cleanUp();
    server.dispatchCommand(admin, confirm.substring(1));
    assertEquals(2, npcs.all().size());
  }

  @Test
  void summaryButtonsRespectPermissionsAndCompletionIncludesSelectionAndConfirmation() {
    var completion = plugin.getCommand("npc").tabComplete(admin, "npc", new String[]{"sel", ""});
    assertTrue(completion.contains(Integer.toString(npcs.number(first.id()))));
    assertTrue(plugin.getCommand("npc").tabComplete(admin, "npc", new String[]{"delete", ""}).contains("confirm"));
    admin.setOp(false);
    admin.addAttachment(plugin, "loveessential.npc.view", true);
    server.dispatchCommand(admin, "npc sel " + npcs.number(first.id()));
    var buttons = commands(drain(admin));
    assertTrue(buttons.contains("/npc deselect"));
    assertFalse(buttons.stream().anyMatch(c -> c.startsWith("/npc delete")));
    assertFalse(buttons.stream().anyMatch(c -> c.startsWith("/npc edit")));
  }

  NpcCommandSelection selection() throws ReflectiveOperationException {
    var executor = plugin.getCommand("npc").getExecutor();
    var field = executor.getClass().getDeclaredField("npcSelection");
    field.setAccessible(true);
    return (NpcCommandSelection) field.get(executor);
  }

  @Test
  void leavingClearsSelectionAndPendingDelete() {
    select(first);
    requestDelete();
    try {
      var selection = selection();
      admin.disconnect();
      assertThrows(IllegalArgumentException.class, () -> selection.target(admin, null));
      assertThrows(IllegalArgumentException.class, () -> selection.confirm(admin, null));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
