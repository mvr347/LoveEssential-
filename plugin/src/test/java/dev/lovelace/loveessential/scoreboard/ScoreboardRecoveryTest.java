package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.Extensions;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Reproduces external scoreboard clearing (/scoreboard setdisplay / players reset) and verifies recovery. */
class ScoreboardRecoveryTest {
  ServerMock server;
  LoveEssentialPlugin plugin;
  ScoreboardModule scoreboard;

  @BeforeEach
  void setup() throws Exception {
    server =
        MockBukkit.mock(
            new ServerMock() {
              @Override
              public String getBukkitVersion() {
                return "26.3-R0.1-SNAPSHOT";
              }
            });
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    waitFor(plugin::ready);
    plugin.getConfig().set("npc.max-active", 0);
    plugin.getConfig().set("scoreboard.delay-on-join-ms", 0);
    var field = LoveEssentialPlugin.class.getDeclaredField("extensions");
    field.setAccessible(true);
    scoreboard = ((Extensions) field.get(plugin)).scoreboard;
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

  void tick(int ticks) {
    try {
      server.getScheduler().performTicks(ticks);
    } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException ignored) {
    }
  }

  /** MockBukkit lacks Objective.numberFormat and Score.customName; wrap like ExtensionsIntegrationTest. */
  private static Scoreboard paperBoard(Scoreboard board) {
    var wrappers = new IdentityHashMap<Object, Object>();
    var formats = new IdentityHashMap<Object, Object>();
    var names = new IdentityHashMap<Object, Component>();
    class Adapter {
      Object wrap(Object target, Class<?> type) {
        return wrappers.computeIfAbsent(
            target,
            t ->
                Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    (proxy, method, args) -> {
                      if (method.getName().equals("numberFormat")) {
                        if (args != null && args.length == 1) {
                          formats.put(t, args[0]);
                          return null;
                        }
                        return formats.get(t);
                      }
                      if (method.getName().equals("customName")) {
                        if (args != null && args.length == 1) {
                          names.put(t, (Component) args[0]);
                          return null;
                        }
                        return names.get(t);
                      }
                      try {
                        Object result = method.invoke(t, args);
                        if (result instanceof Objective) return wrap(result, Objective.class);
                        if (result instanceof Score) return wrap(result, Score.class);
                        return result;
                      } catch (InvocationTargetException e) {
                        throw e.getCause();
                      }
                    }));
      }
    }
    return (Scoreboard) new Adapter().wrap(board, Scoreboard.class);
  }

  @Test
  void renderRestoresDisplaySlotAndScoresAfterExternalClear() throws Exception {
    PlayerMock player = server.addPlayer("Alice");
    waitFor(() -> scoreboard.personal().state(player.getUniqueId()) != null);

    Scoreboard rawBoard = Bukkit.getScoreboardManager().getNewScoreboard();
    Scoreboard board = paperBoard(rawBoard);
    scoreboard.render(player, board);

    Objective obj = board.getObjective(ScoreboardModule.OBJECTIVE);
    assertNotNull(obj, "le_sidebar objective should be created");
    assertEquals(DisplaySlot.SIDEBAR, obj.getDisplaySlot(), "sidebar slot should be set initially");
    assertTrue(obj.getScore(ScoreboardModule.ENTRY_PREFIX + 0).getScore() > 0, "first line score must be set");

    // 1. Simulate external `/scoreboard objectives setdisplay sidebar` clearing the display slot.
    rawBoard.clearSlot(DisplaySlot.SIDEBAR);
    assertNull(board.getObjective(DisplaySlot.SIDEBAR), "display slot should be cleared");

    scoreboard.render(player, board);
    Objective restored = board.getObjective(DisplaySlot.SIDEBAR);
    assertNotNull(restored, "render must restore DisplaySlot.SIDEBAR after external clear");
    assertEquals(ScoreboardModule.OBJECTIVE, restored.getName());

    // 2. Simulate external `/scoreboard players reset *` wiping scores while keeping objective and slot.
    obj.getScore(ScoreboardModule.ENTRY_PREFIX + 0).resetScore();
    assertEquals(0, obj.getScore(ScoreboardModule.ENTRY_PREFIX + 0).getScore(), "score should be cleared");

    scoreboard.render(player, board);
    assertTrue(obj.getScore(ScoreboardModule.ENTRY_PREFIX + 0).getScore() > 0, "render must redraw scores after reset");
  }

  @Test
  void renderLeavesForeignObjectiveIntact() throws Exception {
    PlayerMock player = server.addPlayer("Bob");
    waitFor(() -> scoreboard.personal().state(player.getUniqueId()) != null);

    Scoreboard board = paperBoard(Bukkit.getScoreboardManager().getNewScoreboard());
    Objective foreign = board.registerNewObjective("foreign_board", Criteria.DUMMY, Component.text("Other"));
    foreign.setDisplaySlot(DisplaySlot.SIDEBAR);

    scoreboard.render(player, board);
    assertEquals("foreign_board", board.getObjective(DisplaySlot.SIDEBAR).getName());
    assertNull(board.getObjective(ScoreboardModule.OBJECTIVE));
  }
}
