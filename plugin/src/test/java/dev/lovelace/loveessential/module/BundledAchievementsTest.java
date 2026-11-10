package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Checks the bundled catalogue and join/quit styles against each other, with no server. */
class BundledAchievementsTest {

  private static YamlConfiguration load(String path) {
    var in = BundledAchievementsTest.class.getResourceAsStream(path);
    assertNotNull(in, path);
    return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
  }

  private static AchievementCatalog catalog(List<String> problems) {
    return AchievementCatalog.parse(
        load("/modules/achievements/achievements.yml").getConfigurationSection("catalog"),
        problems::add);
  }

  @Test
  void theBundledCatalogueParsesCleanlyAndIsBig() {
    List<String> problems = new ArrayList<>();
    AchievementCatalog catalog = catalog(problems);
    assertTrue(problems.isEmpty(), problems.toString());
    assertTrue(catalog.all().size() >= 70, "catalogue size: " + catalog.all().size());
    for (var def : catalog.all()) {
      assertFalse(def.id().contains("."), "dotted id " + def.id());
      assertTrue(
          catalog.categories().contains(def.category()), def.id() + " in unknown " + def.category());
    }
  }

  @Test
  void allOfEntriesPointAtExistingAchievements() {
    AchievementCatalog catalog = catalog(new ArrayList<>());
    for (var def : catalog.all()) {
      if (def.trigger() instanceof AchievementCatalog.Trigger.AllOf all) {
        for (String id : all.ids()) {
          assertTrue(catalog.contains(id), def.id() + " needs missing " + id);
        }
      }
    }
  }

  @Test
  void everyStyleAchievementExistsAndSaysItInRewardText() {
    AchievementCatalog catalog = catalog(new ArrayList<>());
    ConfigurationSection styles = load("/modules/joinquit/joinquit.yml").getConfigurationSection("styles");
    assertNotNull(styles);
    for (String id : styles.getKeys(false)) {
      String achievement = styles.getString(id + ".achievement", "");
      if (achievement.isEmpty()) continue;
      var def = catalog.find(achievement);
      assertTrue(def.isPresent(), "style " + id + " needs missing achievement " + achievement);
      if (def.get().rewardText() != null && !def.get().rewardText().isEmpty()) {
        assertTrue(
            def.get().rewardText().contains(styles.getString(id + ".name")),
            "reward-text of " + achievement + " must name style " + id);
      }
      assertNotNull(styles.getString(id + ".join"), id);
      assertNotNull(styles.getString(id + ".quit"), id);
    }
  }

  @Test
  void theBrewSetMatchesLoveBrewBeverageTypes() {
    AchievementCatalog catalog = catalog(new ArrayList<>());
    var trigger = (AchievementCatalog.Trigger.Collected) catalog.find("brew_all_types").orElseThrow().trigger();
    assertEquals("brew.types", trigger.key());
    assertEquals(
        Set.of("BEER", "WINE", "MEAD", "CIDER", "SPECIAL", "DISTILLATE"), Set.copyOf(trigger.values()));
  }

  @Test
  void hookFedAchievementsAreInTheCatalogue() {
    AchievementCatalog catalog = catalog(new ArrayList<>());
    for (String id : List.of("hunt_reaper", "clan_founder", "clan_recruiter", "clan_war_won_1")) {
      assertTrue(catalog.contains(id), id);
    }
  }
}
