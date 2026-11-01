package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HuntPassCatalogMigrationTest {
  @Test
  void addsOnlyTheNewTicketAndRespectsLaterRemoval() throws Exception {
    var module = new YamlConfiguration();
    module.loadFromString(
        "categories:\n  hunt:\n    display-name: Охота\nplaceholders:\n  custom:\n    template: '&c%lovehunt_rank% <gradient:red:blue>Текст</gradient>'\n");
    var defaults = new YamlConfiguration();
    defaults.loadFromString(
        "placeholders:\n  hunt_pass:\n    template: '&f%lovehunt_pass%'\n    category: hunt\n");
    assertTrue(ModuleConfigs.migrateHuntPass(module, defaults));
    assertEquals(
        "&c%lovehunt_rank% <gradient:red:blue>Текст</gradient>",
        module.getString("placeholders.custom.template"));
    assertEquals("&f%lovehunt_pass%", module.getString("placeholders.hunt_pass.template"));
    module.set("placeholders.hunt_pass", null);
    assertFalse(ModuleConfigs.migrateHuntPass(module, defaults));
    assertFalse(module.contains("placeholders.hunt_pass"));
  }

  @Test
  void doesNotReintroduceAnAdministratorsRemovedHuntCategory() throws Exception {
    var module = new YamlConfiguration();
    module.createSection("placeholders");
    module.createSection("categories.general");
    var defaults = new YamlConfiguration();
    defaults.set("placeholders.hunt_pass.template", "%lovehunt_pass%");
    assertFalse(ModuleConfigs.migrateHuntPass(module, defaults));
    assertFalse(module.contains("placeholders.hunt_pass"));
  }
}
