package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RestyleScoreboardTest {
  static YamlConfiguration yaml(String text) throws Exception {
    var y = new YamlConfiguration();
    y.loadFromString(text);
    return y;
  }

  static final String OLD =
      """
      placeholders:
        ping:
          template: 'old ping'
        time:
          variants:
            - {name: A, template: 'old a'}
            - {name: B, template: 'old b'}
      """;
  static final String NEW =
      """
      placeholders:
        ping:
          template: 'new ping'
        time:
          variants:
            - {name: A, template: 'new a'}
            - {name: B, template: 'new b'}
      """;

  @Test
  void aLineStillOnTheOldShippedTextGetsTheNewOne() throws Exception {
    var installed = yaml(OLD);
    ModuleConfigs.restyleScoreboard(installed, yaml(NEW), yaml(OLD));
    assertEquals("new ping", installed.getString("placeholders.ping.template"));
    assertEquals("new a", installed.getMapList("placeholders.time.variants").get(0).get("template"));
    assertEquals("B", installed.getMapList("placeholders.time.variants").get(1).get("name"));
  }

  @Test
  void anEditedLineAndTheAdminsOwnLinesAreKept() throws Exception {
    var installed =
        yaml(
            """
            placeholders:
              ping:
                template: 'my ping'
              mine:
                template: 'mine'
              time:
                variants:
                  - {name: A, template: 'my a'}
                  - {name: B, template: 'old b'}
            """);
    ModuleConfigs.restyleScoreboard(installed, yaml(NEW), yaml(OLD));
    assertEquals("my ping", installed.getString("placeholders.ping.template"));
    assertEquals("mine", installed.getString("placeholders.mine.template"));
    assertEquals("my a", installed.getMapList("placeholders.time.variants").get(0).get("template"));
    assertEquals("new b", installed.getMapList("placeholders.time.variants").get(1).get("template"));
  }
}
