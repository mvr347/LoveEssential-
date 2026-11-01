package dev.lovelace.loveessential.config;
import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
class CalamityMigrationTest {
  @Test void retainsCustomRealDurationWhenConvertingLegacyGameDays() {
    var config = new YamlConfiguration();
    config.set("omen-days", 0.5);
    config.set("types.bugs.duration-days", 4.5);
    config.set("types.plague.duration-minutes", 17);
    config.set("types.plague.duration-days", 3);
    ModuleConfigs.migrateCalamityClock(config);
    assertEquals(10, config.getDouble("omen-minutes"));
    assertEquals(90, config.getDouble("types.bugs.duration-minutes"));
    assertEquals(17, config.getInt("types.plague.duration-minutes"));
    assertFalse(config.contains("omen-days"));
    assertFalse(config.contains("types.bugs.duration-days"));
    var saved = config.saveToString();
    ModuleConfigs.migrateCalamityClock(config);
    assertEquals(saved, config.saveToString());
  }
}
