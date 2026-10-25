package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import java.nio.file.Files;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

/** A tab.yml edited by hand must come out of every reload exactly as it went in. */
class TabFileStabilityTest {
  ServerMock server;
  LoveEssentialPlugin plugin;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    server.addSimpleWorld("world");
    MockBukkit.createMockPlugin("LoveCore");
    plugin = MockBukkit.load(LoveEssentialPlugin.class);
    for (int i = 0; i < 200 && !plugin.ready(); i++) {
      server.getScheduler().performTicks(2);
      Thread.sleep(10);
    }
  }

  @AfterEach
  void teardown() {
    MockBukkit.unmock();
  }

  private String reloadTwice(String content) throws Exception {
    var file = new ModuleConfigs(plugin).configFile("tab");
    var text = Files.readString(file);
    int groups = text.indexOf("\ngroups:");
    int users = text.indexOf("\nusers:");
    Files.writeString(file, text.substring(0, groups + 1) + content + text.substring(users + 1));
    new ModuleConfigs(plugin).load();
    new ModuleConfigs(plugin).load();
    return Files.readString(file);
  }

  @Test
  void handEditedHeaderAndFooterSurviveReloads() throws Exception {
    String groups =
        "groups:\n  _DEFAULT_:\n    tabprefix: ''\n    tabsuffix: ''\n    customtabname: <player>\n"
            + "    tagprefix: ''\n    tagsuffix: ''\n    header:\n    - '<clan>'\n    - ''\n    - '<gray>Онлайн: <visible_online>'\n"
            + "    footer:\n    - '<clan> · <reputation>'\n    per-world: {}\n";
    var after = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
        new java.io.StringReader(reloadTwice(groups)));
    assertEquals(
        java.util.List.of("<clan>", "", "<gray>Онлайн: <visible_online>"),
        after.getStringList("groups._DEFAULT_.header"));
    assertEquals(java.util.List.of("<clan> · <reputation>"), after.getStringList("groups._DEFAULT_.footer"));
  }
}
