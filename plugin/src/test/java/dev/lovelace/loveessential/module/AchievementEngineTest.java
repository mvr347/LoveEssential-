package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AchievementEngineTest {

  private static final String YAML =
      """
      catalog:
        first:
          name: Первая
          rarity: COMMON
          trigger: {type: counter, key: bounties.completed, at-least: 1}
        tenth:
          name: Десятая
          rarity: RARE
          trigger: {type: counter, key: bounties.completed, at-least: 10}
        master:
          name: Мастер
          rarity: EPIC
          trigger: {type: counter, key: hunter.rating, at-least: 5}
        all_beer:
          name: Все напитки
          rarity: EPIC
          trigger: {type: set, key: brew.types, values: [BEER, WINE]}
        secret_toast:
          name: Тост
          hidden: true
        hunter_both:
          name: Охотник во всём
          rarity: LEGENDARY
          trigger: {type: all-of, ids: [tenth, master]}
        collector:
          name: Коллекционер
          rarity: LEGENDARY
          trigger: {type: unlocked-count, at-least: 3}
        bad_rarity:
          name: Сломанная
          rarity: SHINY
        no_name:
          rarity: COMMON
      """;

  private final List<String> problems = new ArrayList<>();
  private final AchievementCatalog catalog = catalog();

  private AchievementCatalog catalog() {
    var yaml = YamlConfiguration.loadConfiguration(new StringReader(YAML));
    return AchievementCatalog.parse(yaml.getConfigurationSection("catalog"), problems::add);
  }

  private AchievementEngine.Progress fresh() {
    return new AchievementEngine.Progress();
  }

  @Test
  void brokenEntriesAreSkippedWithAProblemMessageAndTheRestSurvive() {
    assertEquals(7, catalog.all().size());
    assertTrue(catalog.contains("first"));
    assertFalse(catalog.contains("bad_rarity"));
    assertFalse(catalog.contains("no_name"));
    assertEquals(2, problems.size(), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.contains("bad_rarity") && p.contains("rarity")));
  }

  @Test
  void aCounterUnlocksOnceItsThresholdIsReached() {
    var engine = new AchievementEngine(catalog);
    var progress = fresh();
    assertEquals(List.of("first"), engine.add(progress, "bounties.completed", 1, 1L));
    assertEquals(List.of(), engine.add(progress, "bounties.completed", 5, 2L));
    assertEquals(List.of("tenth"), engine.add(progress, "bounties.completed", 4, 3L));
    // Nothing is unlocked twice.
    assertEquals(List.of(), engine.add(progress, "bounties.completed", 100, 4L));
    assertEquals(1L, progress.unlocked.get("first"));
  }

  @Test
  void aSetValueIsAnExactNumberAndMayLaterGoDownWithoutTakingAnythingBack() {
    var engine = new AchievementEngine(catalog);
    var progress = fresh();
    assertEquals(List.of(), engine.set(progress, "hunter.rating", 4, 1L));
    assertEquals(List.of("master"), engine.set(progress, "hunter.rating", 5, 2L));
    assertEquals(List.of(), engine.set(progress, "hunter.rating", 2, 3L));
    assertTrue(progress.unlocked.containsKey("master"));
  }

  @Test
  void aCollectedSetNeedsEveryValueAndIgnoresRepeats() {
    var engine = new AchievementEngine(catalog);
    var progress = fresh();
    assertEquals(List.of(), engine.collect(progress, "brew.types", "BEER", 1L));
    assertEquals(List.of(), engine.collect(progress, "brew.types", "BEER", 2L));
    assertEquals(List.of("all_beer"), engine.collect(progress, "brew.types", "WINE", 3L));
  }

  @Test
  void aMetaAchievementFollowsItsParentsAndTheCountsSkipMetaOnes() {
    var engine = new AchievementEngine(catalog);
    var progress = fresh();
    engine.add(progress, "bounties.completed", 10, 1L);
    var unlocked = engine.set(progress, "hunter.rating", 5, 2L);
    // master unlocks, which completes hunter_both; first+tenth+master are three real ones, so the collector follows.
    assertEquals(List.of("master", "hunter_both", "collector"), unlocked);
  }

  @Test
  void aManualAchievementOnlyComesFromAnExplicitUnlock() {
    var engine = new AchievementEngine(catalog);
    var progress = fresh();
    assertEquals(List.of(), engine.add(progress, "anything", 10, 1L));
    assertEquals(List.of("secret_toast"), engine.unlock(progress, "secret_toast", 2L));
    assertEquals(List.of(), engine.unlock(progress, "secret_toast", 3L));
    assertEquals(List.of(), engine.unlock(progress, "missing", 4L));
  }

  @Test
  void nonsenseNumbersAreIgnored() {
    var engine = new AchievementEngine(catalog);
    var progress = fresh();
    assertEquals(List.of(), engine.add(progress, "bounties.completed", -5, 1L));
    assertEquals(List.of(), engine.add(progress, "bounties.completed", Double.NaN, 1L));
    assertEquals(List.of(), engine.set(progress, "hunter.rating", Double.POSITIVE_INFINITY, 1L));
    assertTrue(progress.counters.isEmpty());
  }
}
