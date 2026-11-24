package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class QuestRowsCatalogMigrationTest {

  private static YamlConfiguration defaults() throws Exception {
    var y = new YamlConfiguration();
    y.loadFromString(
        "placeholders:\n"
            + "  quest_active: {template: a}\n"
            + "  quest_count: {template: c}\n"
            + "  quest_goal: {template: g}\n"
            + "  quest_time_left: {template: t}\n");
    return y;
  }

  private static YamlConfiguration installed(int version) throws Exception {
    var y = new YamlConfiguration();
    y.loadFromString(
        "categories:\n  activities:\n    display-name: A\nplaceholders:\n  custom: {template: x}\n");
    if (version > 0) y.set("catalog-version", version);
    return y;
  }

  @Test
  void aVersionOneCatalogGetsAllFourRowsAndKeepsCustomOnes() throws Exception {
    var module = installed(1);
    assertTrue(ModuleConfigs.migrateQuestRows(module, defaults()));
    for (String row : new String[] {"quest_active", "quest_count", "quest_goal", "quest_time_left"})
      assertTrue(module.contains("placeholders." + row), row);
    assertEquals("x", module.getString("placeholders.custom.template"));
    assertEquals(3, module.getInt("catalog-version"));
  }

  @Test
  void aVersionTwoCatalogGetsOnlyTheNewRowsSoARemovedRowStaysRemoved() throws Exception {
    var module = installed(2);
    assertTrue(ModuleConfigs.migrateQuestRows(module, defaults()));
    assertFalse(module.contains("placeholders.quest_active"));
    assertTrue(module.contains("placeholders.quest_goal"));
    assertTrue(module.contains("placeholders.quest_time_left"));
  }

  @Test
  void runsOnceAndRespectsALaterRemoval() throws Exception {
    var module = installed(2);
    ModuleConfigs.migrateQuestRows(module, defaults());
    module.set("placeholders.quest_goal", null);
    assertFalse(ModuleConfigs.migrateQuestRows(module, defaults()));
    assertFalse(module.contains("placeholders.quest_goal"));
  }

  @Test
  void doesNotReintroduceARemovedActivitiesCategory() throws Exception {
    var module = new YamlConfiguration();
    module.createSection("placeholders");
    module.createSection("categories.general");
    assertFalse(ModuleConfigs.migrateQuestRows(module, defaults()));
    assertFalse(module.contains("placeholders.quest_goal"));
    assertEquals(3, module.getInt("catalog-version"));
  }
}
