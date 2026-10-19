package dev.lovelace.loveessential.npc;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class NpcLookTest {
  @BeforeEach
  void setup() {
    MockBukkit.mock().addSimpleWorld("world");
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  @Test
  void turnUsesShortestArcAndNeverExceedsStep() {
    assertEquals(-175, NpcLook.turn(175, -175, 30));
    assertEquals(-180, NpcLook.turn(175, -175, 5));
    assertEquals(30, NpcLook.turn(0, 90, 30));
    assertEquals(-30, NpcLook.turn(0, -90, 30));
    assertEquals(40, NpcLook.turn(30, 40, 30));
  }

  @Test
  void rotationTracksHeadAndBodyAndIgnoresInvalidTargets() {
    var world = org.bukkit.Bukkit.getWorld("world");
    var from = new Location(world, 0, 65.6, 0);
    var rotation = new AtomicReference<float[]>();
    var body = new AtomicReference<Float>();
    var entity =
        (LivingEntity)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {LivingEntity.class},
                (proxy, method, args) -> {
                  return switch (method.getName()) {
                    case "getEyeLocation" -> from.clone();
                    case "setRotation" -> {
                      rotation.set(new float[] {(float) args[0], (float) args[1]});
                      yield null;
                    }
                    case "setBodyYaw" -> {
                      body.set((float) args[0]);
                      yield null;
                    }
                    default -> null;
                  };
                });
    NpcLook.update(entity, new Location(world, 4, 65.6, 0), 30);
    assertEquals(-30, rotation.get()[0]);
    assertEquals(0, rotation.get()[1]);
    assertEquals(-30f, body.get());
    rotation.set(null);
    NpcLook.update(entity, new Location(world, Double.NaN, 65, 0), 30);
    assertNull(rotation.get());
    NpcLook.update(entity, from.clone(), 30);
    assertNull(rotation.get());
    NpcLook.update(entity, from.clone().add(0, 5, 0), 30);
    assertEquals(0, rotation.get()[0]);
    assertEquals(-30, rotation.get()[1]);
  }
}
