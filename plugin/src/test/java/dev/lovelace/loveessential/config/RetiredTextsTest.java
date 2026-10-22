package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RetiredTextsTest {
  private static final String PATH = "messages.comfort.ride-consent";

  private static YamlConfiguration with(String value) {
    var yaml = new YamlConfiguration();
    yaml.set(PATH, value);
    return yaml;
  }

  @Test
  void aKnownWrongShippedTextIsReplacedByTheCurrentDefault() {
    var installed =
        with(
            "<yellow>Игрок должен разрешить посадку правом loveessential.ride.accept и находиться"
                + " вне боя.");
    ModuleConfigs.refreshRetiredTexts("sitride", installed, with("new text"));
    assertEquals("new text", installed.getString(PATH));
  }

  @Test
  void anAdminsOwnWordingIsNeverOverwritten() {
    var installed = with("<red>Нельзя.");
    ModuleConfigs.refreshRetiredTexts("sitride", installed, with("new text"));
    assertEquals("<red>Нельзя.", installed.getString(PATH));
  }

  @Test
  void otherModulesAndMissingValuesAreIgnored() {
    var installed = with("<yellow>Игрок должен разрешить посадку правом loveessential.ride.accept и находиться вне боя.");
    ModuleConfigs.refreshRetiredTexts("home", installed, with("new text"));
    assertTrue(installed.getString(PATH).contains("ride.accept"));
    var empty = new YamlConfiguration();
    ModuleConfigs.refreshRetiredTexts("sitride", empty, with("new text"));
    assertNull(empty.getString(PATH));
  }
}
