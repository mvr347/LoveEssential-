package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Lore lines that end with a colon are read by YAML as maps; installed files are repaired on load. */
class MappedLinesTest {
  static YamlConfiguration load(String yaml) throws Exception {
    var y = new YamlConfiguration();
    y.loadFromString(yaml);
    return y;
  }

  @Test
  void aLineEndingWithAColonComesBackAsText() throws Exception {
    var y =
        load(
            """
            messages:
              gui:
                toggle-lore:
                - <dark_gray>Переключатель
                - <dark_gray>▪ <gray>После клика:
                - '  <gray>Состояние: <now>'
            """);
    assertTrue(ModuleConfigs.repairMappedLines(y));
    assertEquals(
        List.of("<dark_gray>Переключатель", "<dark_gray>▪ <gray>После клика:", "  <gray>Состояние: <now>"),
        y.getStringList("messages.gui.toggle-lore"));
  }

  @Test
  void aLineWithAColonInTheMiddleKeepsItsTail() throws Exception {
    var y = load("lines:\n- <gray>Личный выбор: показывать ли тег\n- plain\n");
    assertTrue(ModuleConfigs.repairMappedLines(y));
    assertEquals(List.of("<gray>Личный выбор: показывать ли тег", "plain"), y.getStringList("lines"));
  }

  @Test
  void realListsOfMapsAndCleanFilesAreLeftAlone() throws Exception {
    var y =
        load(
            """
            placeholders:
              datetime:
                variants:
                - {name: Дата, template: '&7%server_time_dd%'}
                - {name: Время, template: '&7%server_time_HH%'}
            messages:
              ok:
              - <gray>fine
            """);
    assertFalse(ModuleConfigs.repairMappedLines(y));
    assertEquals(2, y.getList("placeholders.datetime.variants").size());
    assertInstanceOf(java.util.Map.class, y.getList("placeholders.datetime.variants").getFirst());
  }
}
