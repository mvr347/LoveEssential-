package dev.lovelace.loveessential.condition;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.auth.AuthOracle;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.api.LoveEssentialService.Verdict;
import dev.lovelace.loveessential.integration.LoveBridge;
import java.util.*;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ConditionEngineTest {
  ServerMock server;
  ConditionEngine engine;
  PlayerMock player;

  @BeforeEach
  void start() {
    server = MockBukkit.mock();
    player = server.addPlayer();
    engine = new ConditionEngine(new LoveBridge());
  }

  @AfterEach
  void end() {
    MockBukkit.unmock();
  }

  @Test
  void missingAuthServiceDoesNotAuthorizeWhenLoveAuthIsInstalled() {
    MockBukkit.createMockPlugin("LoveAuth");
    assertEquals(
        Verdict.UNAVAILABLE,
        engine.test(player, new Condition("authenticated", Map.of(), List.of())));
  }

  @Test
  void lateAuthProviderIsResolvedLive() {
    var plugin = MockBukkit.createMockPlugin("LoveAuth");
    Condition c = new Condition("authenticated", Map.of(), List.of());
    assertEquals(Verdict.UNAVAILABLE, engine.test(player, c));
    server
        .getServicesManager()
        .register(AuthOracle.class, id -> true, plugin, ServicePriority.Normal);
    assertEquals(Verdict.ALLOW, engine.test(player, c));
    server.getServicesManager().unregisterAll(plugin);
    assertEquals(Verdict.UNAVAILABLE, engine.test(player, c));
  }

  @Test
  void unknownConditionRemainsUnavailableUnderNegation() {
    var unknown = new Condition("lovequests:active", Map.of(), List.of());
    assertEquals(
        Verdict.UNAVAILABLE, engine.test(player, new Condition("not", Map.of(), List.of(unknown))));
  }

  @Test
  void conditionRegistrationIsReversibleAndIsolatesProviderFailures() {
    var handle =
        engine.register(
            "test:condition",
            (p, a) -> {
              throw new IllegalStateException();
            });
    var c = new Condition("test:condition", Map.of(), List.of());
    assertEquals(Verdict.UNAVAILABLE, engine.test(player, c));
    handle.close();
    assertFalse(engine.known("test:condition"));
  }
}
