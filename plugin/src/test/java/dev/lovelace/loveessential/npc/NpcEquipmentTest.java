package dev.lovelace.loveessential.npc;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class NpcEquipmentTest {
  @BeforeEach
  void setup() {
    MockBukkit.mock();
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  NpcDefinition definition() {
    return new NpcDefinition(
        "guard",
        1,
        UUID.randomUUID(),
        "npc.guard",
        "MANNEQUIN",
        new Position(UUID.randomUUID(), 0, 64, 0, 0, 0),
        Map.of("main-hand", "IRON_SWORD", "helmet", "IRON_HELMET"),
        Condition.always(),
        Condition.always(),
        Route.empty(),
        Dialog.empty(),
        List.of(),
        true);
  }

  LivingEntity entity(
      Class<? extends LivingEntity> type,
      Map<EquipmentSlot, ItemStack> items,
      AtomicInteger drops) {
    var equipment =
        (EntityEquipment)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {EntityEquipment.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("setItem"))
                    items.put((EquipmentSlot) args[0], (ItemStack) args[1]);
                  if (method.getName().equals("setDropChance")) {
                    if (!Mob.class.isAssignableFrom(type))
                      throw new IllegalArgumentException(
                          "Cannot set drop chance for non-Mob entity");
                    assertEquals(0f, (float) args[1]);
                    drops.incrementAndGet();
                  }
                  return null;
                });
    return (LivingEntity)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> method.getName().equals("getEquipment") ? equipment : null);
  }

  @Test
  void mannequinAndArmorStandEquipmentWorksWithPaperNonMobRestriction() {
    for (var type : List.of(Mannequin.class, ArmorStand.class)) {
      var items = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
      var drops = new AtomicInteger();
      assertDoesNotThrow(() -> NpcService.equip(entity(type, items, drops), definition()));
      assertEquals(Material.IRON_SWORD, items.get(EquipmentSlot.HAND).getType());
      assertEquals(Material.IRON_HELMET, items.get(EquipmentSlot.HEAD).getType());
      assertEquals(0, drops.get());
    }
  }

  @Test
  void mobsStillHaveEquipmentDropsDisabled() {
    var drops = new AtomicInteger();
    NpcService.equip(entity(Mob.class, new EnumMap<>(EquipmentSlot.class), drops), definition());
    assertEquals(6, drops.get());
  }
}
