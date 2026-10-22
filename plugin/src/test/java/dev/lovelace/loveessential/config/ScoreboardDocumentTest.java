package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ScoreboardDocumentTest {
  @Test
  void migrationNeverTreatsPlaceholderOrPresetIdsAsLanguageReferences() throws Exception {
    var module = new YamlConfiguration();
    module.loadFromString(
        """
        header: '<gold>Сервер'
        placeholders:
          my-key:
            name: '<red>♜'
            variants:
              - template: '<gradient:red:gold>%player_name% | %clans_tag%</gradient>'
        presets:
          my-keys:
            name: '<gold>Особый'
        categories:
          pvp-key: '<red>Бой'
        """);
    var expected = ModuleConfigs.snapshot(module);
    Presentation.migrate(
        "scoreboard",
        module,
        new YamlConfiguration(),
        new YamlConfiguration(),
        new YamlConfiguration(),
        new YamlConfiguration());
    assertEquals(expected, ModuleConfigs.snapshot(module));
    var runtime = new YamlConfiguration();
    runtime.createSection("scoreboard", ScoreboardDocument.read(module));
    ModuleConfigs.setValue(runtime, "scoreboard.placeholders", module.get("placeholders"));
    assertEquals(
        ModuleConfigs.snapshot(module.get("placeholders")),
        ModuleConfigs.snapshot(ModuleConfigs.view(runtime, "scoreboard").get("placeholders")));
  }

  @Test
  void ordersExistingDocumentsWithoutChangingLiteralTemplatesOrComments() throws Exception {
    var yaml = new YamlConfiguration();
    yaml.loadFromString(
        """
        footer: '<gradient:#112233:#abcdef>Длинный подвал | %player_name%</gradient>'
        presets: {}
        placeholders:
          mine:
            name: '<gold>♜ Имя'
            texture: original-texture
            variants:
              - template: '<bold><red>%clans_tag%</red></bold> | <white>%player_name%'
        header: '<gold>Очень длинная шапка'
        custom-rule: [world, world_nether]
        separator: '  <dark_gray>───  '
        enabled: true
        """);
    yaml.setComments("placeholders.mine", List.of("Собственное оформление"));
    var before = ModuleConfigs.snapshot(yaml);
    ScoreboardDocument.order(yaml);
    assertEquals(before, ModuleConfigs.snapshot(yaml));
    assertEquals(
        List.of(
            "custom-rule", "enabled", "header", "separator", "placeholders", "footer", "presets"),
        List.copyOf(yaml.getKeys(false)));
    assertEquals(List.of("Собственное оформление"), yaml.getComments("placeholders.mine"));
    var restored = new YamlConfiguration();
    restored.loadFromString(yaml.saveToString());
    assertEquals(before, ModuleConfigs.snapshot(restored));
    assertEquals(List.of("Собственное оформление"), restored.getComments("placeholders.mine"));
  }

  @Test
  void typedApiUpdatesKeepExtensionIconsButDoNotResurrectDeletedEntries() throws Exception {
    var yaml = new YamlConfiguration();
    yaml.loadFromString(
        """
        header: '<gold>Сервер'
        placeholders:
          kept:
            name: '<red>Имя'
            texture: custom-base64
            icon: '<gold>♜'
            variants:
              - template: '%player_name% | <bold>цвет'
          deleted:
            name: Удалённая
        presets:
          custom:
            name: Особый
            texture: preset-texture
        """);
    var catalog = ScoreboardDocument.read(yaml);
    catalog.put(
        "placeholders",
        Map.of(
            "kept",
            Map.of(
                "name",
                "<red>Имя",
                "variants",
                List.of(Map.of("template", "%player_name% | <bold>цвет")))));
    catalog.put("presets", Map.of("custom", Map.of("name", "Особый")));
    ScoreboardDocument.write(yaml, catalog);
    assertEquals("custom-base64", yaml.getString("placeholders.kept.texture"));
    assertEquals("<gold>♜", yaml.getString("placeholders.kept.icon"));
    assertEquals("preset-texture", yaml.getString("presets.custom.texture"));
    assertFalse(yaml.contains("placeholders.deleted"));
    assertEquals(
        "%player_name% | <bold>цвет",
        yaml.getMapList("placeholders.kept.variants").getFirst().get("template"));
  }
}
