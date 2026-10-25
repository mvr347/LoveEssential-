package dev.lovelace.loveessential.npc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CitizensImportTest {
  private static final String SAVES =
      """
      npc:
        '0':
          name: '&eБанкир'
          traits:
            type: PLAYER
            location: {bworld: world, x: 10.5, y: 64.0, z: -3.5, yaw: 90.0, pitch: 0.0}
            skintrait: {skinName: Notch, textureRaw: VAL, signature: SIG}
            lookclose: {enabled: true}
            hologramtrait:
              lines:
              - {text: '&7Банк'}
              - 'Кредиты'
        '1':
          name: Овца
          traits:
            type: sheep
            location: {world: nether, x: 1, y: 2, z: 3}
        '2':
          name: Нигде
          traits: {}
        x:
          name: Мусор
      """;

  @Test
  void readsEveryFieldOfAPlayerNpc() throws Exception {
    var yaml = new YamlConfiguration();
    yaml.loadFromString(SAVES);
    var result = CitizensImport.parse(yaml);
    var banker = result.entries().get(0);
    assertEquals(0, banker.id());
    assertEquals("&eБанкир", banker.name());
    assertEquals("PLAYER", banker.type());
    assertEquals("world", banker.world());
    assertEquals(10.5, banker.x());
    assertEquals(-3.5, banker.z());
    assertEquals(90f, banker.yaw());
    assertEquals("Notch", banker.skinName());
    assertEquals("VAL", banker.skinValue());
    assertEquals("SIG", banker.skinSignature());
    assertTrue(banker.lookClose());
    assertEquals(java.util.List.of("&7Банк", "Кредиты"), banker.hologram());
  }

  @Test
  void mobsAndBothWorldKeysWorkAndBrokenRecordsAreListed() throws Exception {
    var yaml = new YamlConfiguration();
    yaml.loadFromString(SAVES);
    var result = CitizensImport.parse(yaml);
    assertEquals(2, result.entries().size());
    var sheep = result.entries().get(1);
    assertEquals("SHEEP", sheep.type());
    assertEquals("nether", sheep.world());
    assertFalse(sheep.lookClose());
    assertEquals(2, result.skipped().size());
  }

  @Test
  void aFileWithoutNpcsIsEmpty() {
    assertTrue(CitizensImport.parse(new YamlConfiguration()).entries().isEmpty());
  }

  @Test
  void onlyCitizensNumbersFromTheTableAreRebound() {
    var table = Map.of(5, "ext-aaaa1111");
    assertEquals("ext-aaaa1111", CitizensRebind.replacement("5", table).orElseThrow());
    assertEquals("ext-aaaa1111", CitizensRebind.replacement(5, table).orElseThrow());
    assertTrue(CitizensRebind.replacement("6", table).isEmpty());
    assertTrue(CitizensRebind.replacement("ext-bbbb2222", table).isEmpty());
    assertTrue(CitizensRebind.replacement("", table).isEmpty());
    assertTrue(CitizensRebind.replacement(null, table).isEmpty());
  }
}
