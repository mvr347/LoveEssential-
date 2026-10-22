package dev.lovelace.loveessential.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.gui.GuiLayout;
import dev.lovelace.loveessential.gui.GuiRules;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Opens every reachable menu as an administrator and checks it against the gui_gen layout. */
class GuiLayoutAuditTest {
  static final List<String> MODULES =
      List.of(
          "npc", "tab", "home", "spawn", "vanish", "afk", "skin", "sitride",
          "utilities", "nightskip", "portal", "hologram", "traces", "joinquit", "motd",
          "messages", "inventory", "profile", "nickname", "crafting", "starter", "worlds",
          "scenarios", "dialogs", "scoreboard", "revive", "rtp");

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
    var future = plugin.module("scoreboard", false);
    waitFor(future::isDone);
    future.join();
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

  /** Commands that open a root (parentless) menu; the result of each is checked. */
  List<String> roots() {
    var commands =
        new ArrayList<>(
            List.of(
                "le", "le module", "le help", "le npc", "le hologram", "le portal", "le nick",
                "le recipes", "le starter edit", "le scoreboard", "le scoreboard edit",
                "le reports", "profile", "profile settings", "profile stats", "le msg",
                "le ignore", "le traces", "vanish"));
    for (String module : MODULES) commands.add("le module " + module);
    return commands;
  }

  @Test
  void everyRootMenuFollowsTheLayoutStandard() throws Exception {
    var report = new TreeMap<String, List<String>>();
    var opened = new ArrayList<String>();
    int number = 0;
    for (String command : roots()) {
      // A fresh administrator per menu keeps "opened from a parent" state from leaking over.
      var player = server.addPlayer("Builder" + number++);
      player.setOp(true);
      server.dispatchCommand(player, command);
      server.getScheduler().performTicks(2);
      var top = player.getOpenInventory().getTopInventory();
      if (top == null || !(top.getHolder() instanceof GuiLayout)) continue;
      opened.add(command + "=" + top.getSize());
      var violations = new ArrayList<>(GuiRules.violations(top, false));
      violations.addAll(GuiRules.unresolvedText(top));
      if (!violations.isEmpty()) report.put(command + " (" + top.getSize() + ")", violations);
    }
    var text = new StringBuilder();
    report.forEach((command, list) -> text.append('\n').append(command).append(": ").append(list));
    assertTrue(opened.size() > 10, "Too few menus were reachable: " + opened);
    assertTrue(report.isEmpty(), "Layout violations:" + text + "\nChecked: " + opened);
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
    tick(2);
  }

  /** MockBukkit lacks some scoreboard calls; a tick that hits one must not abort the audit. */
  void tick(int ticks) {
    try {
      server.getScheduler().performTicks(ticks);
    } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException ignored) {
      // Not part of the layout under test.
    }
  }

  org.bukkit.inventory.Inventory replay(PlayerMock player, String root, List<Integer> path) {
    player.closeInventory();
    server.dispatchCommand(player, root);
    tick(2);
    for (int slot : path) {
      var before = player.getOpenInventory().getTopInventory();
      if (before == null || !(before.getHolder() instanceof GuiLayout)) return null;
      click(player, slot);
    }
    return player.getOpenInventory().getTopInventory();
  }

  /** Follows every button two levels deep and checks each menu it reaches (with a parent). */
  @Test
  void menusReachedByButtonsFollowTheLayoutStandard() throws Exception {
    var player = server.addPlayer("Builder");
    player.setOp(true);
    server.dispatchCommand(player, "le hologram create Первая строка");
    tick(40);
    var report = new TreeMap<String, Set<String>>();
    var seen = new HashSet<String>();
    var reached = new ArrayList<String>();
    var queue = new ArrayDeque<Map.Entry<String, List<Integer>>>();
    for (String root : roots()) queue.add(Map.entry(root, List.of()));
    while (!queue.isEmpty() && reached.size() < 600) {
      var next = queue.poll();
      var top = replay(player, next.getKey(), next.getValue());
      if (top == null || !(top.getHolder() instanceof GuiLayout layout)) continue;
      boolean child = !next.getValue().isEmpty();
      String id = layout.guiId() + "#" + top.getSize();
      if (child && seen.add(id)) {
        reached.add(id);
        var violations = new ArrayList<>(GuiRules.violations(top, null));
        violations.addAll(GuiRules.unresolvedText(top));
        if (!violations.isEmpty()) report.put(id + " via " + next, new TreeSet<>(violations));
      }
      if (next.getValue().size() >= Integer.getInteger("gui.audit.depth", 2)) continue;
      for (int slot = 0; slot < top.getSize(); slot++) {
        var item = top.getItem(slot);
        if (item == null || GuiRules.glass(item)) continue;
        String name = GuiRules.name(item);
        if (name.contains("Закрыть") || name.contains("Назад")) continue;
        var path = new ArrayList<>(next.getValue());
        path.add(slot);
        queue.add(Map.entry(next.getKey(), path));
      }
    }
    var text = new StringBuilder();
    report.forEach((menu, list) -> text.append('\n').append(menu).append(": ").append(list));
    assertTrue(reached.size() > 20, "Too few sub-menus were reachable: " + reached);
    assertTrue(report.isEmpty(), "Layout violations:" + text);
  }
}
