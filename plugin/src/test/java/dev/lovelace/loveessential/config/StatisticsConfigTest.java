package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The shipped statistics menu: five categories, sections that never repeat a number, every value known. */
class StatisticsConfigTest {
  private static final String STATS = "menus.profile-menu-statistics";
  private static final Pattern VALUE = Pattern.compile("<([a-z][a-z0-9_]*)>");

  private static YamlConfiguration resource(String name) throws Exception {
    try (var in = StatisticsConfigTest.class.getResourceAsStream("/" + name)) {
      assertNotNull(in, name);
      return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
  }

  private static ConfigurationSection categories() throws Exception {
    return resource("modules/profile/gui.yml").getConfigurationSection(STATS + ".categories");
  }

  /** Variable of every "Label: <value>" line, per section. */
  private static Map<String, List<String>> variablesBySection() throws Exception {
    var result = new LinkedHashMap<String, List<String>>();
    var categories = categories();
    for (String id : categories.getKeys(false))
      for (String detail : categories.getConfigurationSection(id + ".details").getKeys(false)) {
        var variables = new ArrayList<String>();
        for (String line : categories.getStringList(id + ".details." + detail + ".lore")) {
          int colon = line.indexOf(": ");
          if (colon < 0) continue;
          var match = VALUE.matcher(line.substring(colon + 2));
          if (match.find()) variables.add(match.group(1));
        }
        result.put(id + "." + detail, variables);
      }
    return result;
  }

  @Test
  void fiveCategoriesInTheOrderOfTheButtons() throws Exception {
    assertEquals(
        List.of("world", "clans", "activities", "combat", "trade"),
        List.copyOf(categories().getKeys(false)));
    assertEquals(54, resource("modules/profile/gui.yml").getInt(STATS + ".size"));
    assertFalse(resource("modules/profile/gui.yml").getBoolean(STATS + ".frame", true), "drawn background: no glass");
  }

  @Test
  void noNumberAppearsInTwoSections() throws Exception {
    var seen = new HashMap<String, String>();
    for (var section : variablesBySection().entrySet())
      for (String variable : section.getValue()) {
        String earlier = seen.put(variable, section.getKey());
        assertNull(earlier, variable + " is in both " + earlier + " and " + section.getKey());
      }
  }

  @Test
  void everySectionIsReadableInOneLore() throws Exception {
    for (var section : variablesBySection().entrySet())
      assertTrue(
          section.getValue().size() >= 2 && section.getValue().size() <= 10,
          section.getKey() + " has " + section.getValue().size() + " lines");
  }

  @Test
  void everyValueIsCapturedOrAliasedToAnExternalPlaceholder() throws Exception {
    var known = new HashSet<String>();
    // Values the profile itself captures: the literals put into the snapshot and the "no data" fallbacks.
    String source =
        Files.readString(
            Path.of("src/main/java/dev/lovelace/loveessential/module/PlayerProfiles.java"));
    var put = Pattern.compile("m\\.put\\(\\s*\"([a-z0-9_]+)\"").matcher(source);
    while (put.find()) known.add(put.group(1));
    known.addAll(
        resource("modules/profile/profile.yml")
            .getConfigurationSection("statistics.placeholders")
            .getKeys(false));
    var missing = new ArrayList<String>();
    for (var section : variablesBySection().entrySet())
      for (String variable : section.getValue())
        if (!known.contains(variable)) missing.add(section.getKey() + " -> " + variable);
    assertTrue(missing.isEmpty(), "values nobody provides: " + missing);
  }

  @Test
  void sectionsOfAnotherPluginNameThatPlugin() throws Exception {
    var categories = categories();
    assertEquals("LoveClans", categories.getString("clans.plugin"));
    assertEquals("LoveDuels", categories.getString("combat.details.duels.plugin"));
    assertEquals("LoveHunt", categories.getString("combat.details.hunt.plugin"));
    assertEquals("LoveTrades", categories.getString("trade.details.trades.plugin"));
    assertEquals("LoveShops", categories.getString("trade.details.points.plugin"));
    assertEquals("LoveBrewing", categories.getString("activities.details.brewing.plugin"));
  }

  @Test
  void everyNewTextHasABindingSoItCanBeEdited() throws Exception {
    var bindings = resource("presentation-bindings.yml");
    var keys = new HashSet<String>();
    for (var entry : bindings.getMapList("bindings")) keys.add(String.valueOf(entry.get("key")));
    for (String key :
        List.of(
            "section-line", "section-current", "section-other", "section-divider", "metric-line",
            "cycle-hint", "cycle-single"))
      assertTrue(keys.contains("profile-menu." + key), key);
    var gui = resource("modules/profile/gui.yml");
    assertNotNull(gui.get(STATS + ".labels.profile-menu.section-line"));
  }

  @Test
  void unchangedShippedCategoriesAreRecognisedAndEditedOnesAreNot() throws Exception {
    var legacy = resource("legacy/profile-statistics-2026-10-30.yml");
    var installed = new YamlConfiguration();
    installed.set(STATS + ".categories", ModuleConfigs.snapshot(legacy.get(STATS + ".categories")));
    assertTrue(ModuleConfigs.unchangedLegacyStatistics(installed, legacy));

    installed.set(STATS + ".categories.world.details.visits.lore", List.of("<gold>my own line"));
    assertFalse(ModuleConfigs.unchangedLegacyStatistics(installed, legacy), "an admin's edit is kept");

    var current = new YamlConfiguration();
    current.set(STATS + ".categories", ModuleConfigs.snapshot(legacy.get(STATS + ".categories")));
    current.set(STATS + ".sections-version", 1);
    assertFalse(ModuleConfigs.unchangedLegacyStatistics(current, legacy), "already migrated");

    assertFalse(ModuleConfigs.unchangedLegacyStatistics(new YamlConfiguration(), legacy));
  }
}
