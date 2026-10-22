package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.scoreboard.ScoreboardModule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Load-time rules for tab.yml and scoreboard.yml: bad values fail the reload, not the server. */
class TabConfigValidationTest {
  YamlConfiguration yaml(String text) throws Exception {
    var y = new YamlConfiguration();
    y.loadFromString(text);
    return y;
  }

  @Test
  void validTabConfigPasses() throws Exception {
    var config =
        yaml(
            """
            tab:
              placeholder-refresh:
                default: 200
                '%player_ping%': 1000
              placeholder-output-replacements:
                '%player_health%':
                  '0-10': low
                  else: ok
              animations:
                kingdom:
                  change-interval: 500
                  texts: [a, b]
              conditions:
                night:
                  type: or
                  checks: ['%ping% > 100', 'permission:x.y']
                  yes: a
                  no: b
              engine-conditions:
                vip: '{"type":"all","args":{},"children":[]}'
              overrides:
                max-seconds: 600
            """);
    assertDoesNotThrow(() -> TabModule.validate(config));
  }

  @Test
  void brokenTabValuesAreRejected() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> TabModule.validate(yaml("tab:\n  placeholder-refresh:\n    default: 5\n")));
    assertThrows(IllegalArgumentException.class, () -> TabModule.validate(yaml("tab:\n  placeholder-output-replacements:\n    '%x%':\n      '10-0': a\n")));
    assertThrows(IllegalArgumentException.class, () -> TabModule.validate(yaml("tab:\n  animations:\n    k:\n      change-interval: 1\n")));
    assertThrows(IllegalArgumentException.class, () -> TabModule.validate(yaml("tab:\n  conditions:\n    c:\n      type: xor\n")));
    assertThrows(IllegalArgumentException.class, () -> TabModule.validate(yaml("tab:\n  conditions:\n    c:\n      checks: ['no operator here']\n")));
    assertThrows(IllegalArgumentException.class, () -> TabModule.validate(yaml("tab:\n  engine-conditions:\n    v: 'not json'\n")));
    assertThrows(IllegalArgumentException.class, () -> TabModule.validate(yaml("tab:\n  overrides:\n    max-seconds: 0\n")));
  }

  @Test
  void namedBoardsNeedTitleAndBoundedLines() throws Exception {
    assertDoesNotThrow(
        () ->
            ScoreboardModule.validate(
                yaml("scoreboard:\n  scoreboards:\n    main:\n      title: T\n      lines: [a, b]\n")));
    var fifteen = new StringBuilder("scoreboard:\n  scoreboards:\n    main:\n      title: T\n      lines:\n");
    for (int i = 0; i < 16; i++) fifteen.append("      - line").append(i).append('\n');
    assertThrows(IllegalArgumentException.class, () -> ScoreboardModule.validate(yaml(fifteen.toString())));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreboardModule.validate(yaml("scoreboard:\n  scoreboards:\n    main:\n      lines: [a]\n")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScoreboardModule.validate(yaml("scoreboard:\n  scoreboards:\n    bad id:\n      title: T\n      lines: [a]\n")));
  }
}
