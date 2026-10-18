package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class InventoryTransferTest {
  @BeforeAll
  static void setup() {
    MockBukkit.mock();
  }

  @AfterAll
  static void cleanup() {
    MockBukkit.unmock();
  }

  static ItemStack diamonds(int n) {
    return n == 0 ? null : new ItemStack(Material.DIAMOND, n);
  }

  static int amount(ItemStack item) {
    return item == null ? 0 : item.getAmount();
  }

  @Test
  void everyLeftAndRightTransferConservesItemsAndRespectsStackLimit() {
    for (boolean right : new boolean[] {false, true})
      for (int a = 0; a <= 64; a++)
        for (int b = 0; b <= 64; b++) {
          var slot = diamonds(a);
          var cursor = diamonds(b);
          var result = InventoryInspector.transfer(slot, cursor, right, 64);
          assertEquals(a + b, amount(result.slot()) + amount(result.cursor()));
          assertTrue(amount(result.slot()) <= 64 && amount(result.cursor()) <= 64);
          assertEquals(a, amount(slot));
          assertEquals(b, amount(cursor));
        }
  }

  @Test
  void rightClickTakesHalfPlacesOneAndArmorAcceptsAtMostOne() {
    var take = InventoryInspector.transfer(diamonds(5), null, true, 64);
    assertEquals(2, amount(take.slot()));
    assertEquals(3, amount(take.cursor()));
    var put = InventoryInspector.transfer(null, diamonds(5), true, 64);
    assertEquals(1, amount(put.slot()));
    assertEquals(4, amount(put.cursor()));
    var armor = InventoryInspector.transfer(null, diamonds(5), false, 1);
    assertEquals(1, amount(armor.slot()));
    assertEquals(4, amount(armor.cursor()));
    var blocked =
        InventoryInspector.transfer(new ItemStack(Material.IRON_HELMET), diamonds(5), false, 1);
    assertEquals(Material.IRON_HELMET, blocked.slot().getType());
    assertEquals(5, amount(blocked.cursor()));
  }

  @Test
  void swappingDifferentItemsPreservesBothAndSlotMappingIsBijective() {
    var result =
        InventoryInspector.transfer(diamonds(12), new ItemStack(Material.GOLD_INGOT, 7), false, 64);
    assertEquals(Material.GOLD_INGOT, result.slot().getType());
    assertEquals(7, amount(result.slot()));
    assertEquals(Material.DIAMOND, result.cursor().getType());
    assertEquals(12, amount(result.cursor()));
    var slots = new java.util.HashSet<Integer>();
    for (int i = 0; i < 41; i++) slots.add(InventoryInspector.targetSlot(i));
    assertEquals(41, slots.size());
    for (int i = 0; i < 41; i++) assertTrue(slots.contains(i));
  }
}
