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

  @Test
  void theOldDisplayTextsAreReplacedButAnEditedOneIsNot() {
    var installed = new YamlConfiguration();
    installed.set("messages.display.all-on", "<gold>Показать всё <dark_gray>| <white>12 настроек");
    installed.set("messages.display.scope-own", "Моё оформление");
    var defaults = new YamlConfiguration();
    defaults.set("messages.display.all-on", "new all");
    defaults.set("messages.display.scope-own", "new own");
    ModuleConfigs.refreshRetiredTexts("display", installed, defaults);
    assertEquals("new all", installed.getString("messages.display.all-on"));
    assertEquals("Моё оформление", installed.getString("messages.display.scope-own"));
  }

  @Test
  void displayViewerLoreMigratesToOwnerWordingWithoutReplacingCustomLore() throws Exception {
    var defaults = new YamlConfiguration();
    try (var stream = getClass().getResourceAsStream("/modules/display/gui.yml")) {
      assertNotNull(stream);
      defaults.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
    }
    var installed = new YamlConfiguration();
    String path = "messages.display.tile-lore";
    installed.set(path, java.util.List.of(
        "<dark_gray>Только у вас", "", "<dark_gray>▪ <gray>После клика:",
        "  <gray>Состояние: <now> <dark_gray>→ <next>",
        "  <gray>Другие игроки ничего не заметят.",
        "<dark_gray><st>─────────────────────</st>",
        "<green>ЛКМ <dark_gray>— <gray>переключить"));
    ModuleConfigs.refreshRetiredTexts("display", installed, defaults);
    assertEquals(defaults.getStringList(path), installed.getStringList(path));
    installed.set(path, java.util.List.of("<gray>Моё описание"));
    ModuleConfigs.refreshRetiredTexts("display", installed, defaults);
    assertEquals(java.util.List.of("<gray>Моё описание"), installed.getStringList(path));
  }

  @Test
  void theOldMessagePrefixBecomesServer() {
    var installed = new YamlConfiguration();
    installed.set("messages.chat.prefix", "<dark_gray>[<gold>Love<dark_gray>] <reset>");
    var fresh = new YamlConfiguration();
    fresh.set("messages.chat.prefix", "<dark_gray>[<gold>Сервер<dark_gray>] <reset>");
    ModuleConfigs.refreshRetiredTexts("npc", installed, fresh);
    assertTrue(installed.getString("messages.chat.prefix").contains("Сервер"));
    installed.set("messages.chat.prefix", "<red>[Мой] ");
    ModuleConfigs.refreshRetiredTexts("npc", installed, fresh);
    assertEquals("<red>[Мой] ", installed.getString("messages.chat.prefix"));
  }
}
