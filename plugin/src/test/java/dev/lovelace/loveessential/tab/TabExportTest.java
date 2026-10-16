package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TabExportTest {
  @TempDir Path dir;

  @Test
  void translatesNestedLanguageReferencesWithoutChangingEngineSettings() {
    var result =
        TabExport.transform(
            Map.of("fixed-slots", List.of("1|@lang(tab.header)"), "enabled", true),
            k -> k.equals("tab.header"));
    assertEquals(
        Map.of("fixed-slots", List.of("1|%loveessential_text_tab.header%"), "enabled", true),
        result);
  }

  @Test
  void missingLanguageReferenceFailsBeforeExport() {
    assertThrows(
        IllegalArgumentException.class, () -> TabExport.transform("@lang(absent.key)", k -> false));
  }

  @Test
  void exportPreservesUnmanagedFieldsAndBacksUpOldFile() throws Exception {
    Files.writeString(dir.resolve("config.yml"), "custom-unknown: 7\nlayout:\n  enabled: false\n");
    TabExport.write(dir, Map.of("config", Map.of("layout", Map.of("enabled", true))));
    var yaml = YamlConfiguration.loadConfiguration(dir.resolve("config.yml").toFile());
    assertEquals(7, yaml.getInt("custom-unknown"));
    assertTrue(yaml.getBoolean("layout.enabled"));
    assertTrue(
        Files.readString(dir.resolve("config.yml.loveessential.bak")).contains("enabled: false"));
  }
}
