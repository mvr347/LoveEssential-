package dev.lovelace.loveessential.scoreboard;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.scoreboard.PersonalState.Entry;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PersonalCatalogTest {
  /** The format of the LoveTweaks configuration, under the runtime prefix {@code scoreboard.}. */
  static YamlConfiguration config(String body) {
    var y = new YamlConfiguration();
    try {
      y.loadFromString("scoreboard:\n" + body.indent(2));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return y;
  }

  static final String SAMPLE =
      """
      title: '&FVOIDCORE'
      top: ''
      bottom: '&c✌ &7V O I D C O R E &c✌'
      separator: ''
      max-placeholders: 12
      disabled-worlds: [lobby]
      default-preset: small
      categories:
        general: {display-name: '&bОбщее', lore: ['&7Координаты']}
        combat: {display-name: '&cБой'}
      presets:
        small: {name: '&6Маленькая', placeholders: [coords, datetime:2, ghost]}
      placeholders:
        coords:
          display-name: '&bКоординаты'
          template: '&6x &b%lovetweaks_x%'
          category: general
        datetime:
          display-name: '&7Дата и время'
          category: general
          variants:
            - {name: 'Дата', template: '&7%server_time_dd.MM.yyyy%'}
            - {name: 'Время', template: '&7%server_time_HH:mm%'}
            - {name: 'Оба', template: '&7%server_time_dd.MM.yyyy HH:mm%'}
        kills:
          display-name: '&cУбийства'
          template: '&c%lovetweaks_kills%'
          sort-group: 30
          requirement: {placeholder: '%clans_in_clan%', equals: 'true', reason: '&7Нужен клан'}
      """;

  @Test
  void readsTheLoveTweaksFormat() {
    var c = PersonalCatalog.read(config(SAMPLE));
    assertEquals("&FVOIDCORE", c.title());
    assertEquals("", c.top());
    assertEquals(8, c.max(), "12 is cut to the 8 cards that fit");
    assertEquals(List.of("lobby"), c.disabledWorlds());
    assertEquals(3, c.items().size());
    assertEquals("&6x &b%lovetweaks_x%", c.item("coords").template(0));
    assertEquals(3, c.item("datetime").variants().size());
    assertEquals("Время", c.item("datetime").variantName(1));
    assertEquals("&7%server_time_dd.MM.yyyy HH:mm%", c.item("datetime").template(2));
    assertEquals("&7%server_time_dd.MM.yyyy HH:mm%", c.item("datetime").template(2));
  }

  @Test
  void anOldSortGroupMapsOntoACategoryAndUnknownCategoriesFallBack() {
    var c = PersonalCatalog.read(config(SAMPLE));
    // sort-group 30 is "combat" in the old table.
    assertEquals("combat", c.item("kills").category());
    assertEquals("general", PersonalCatalog.categoryForSortGroup(0));
    assertEquals("clans", PersonalCatalog.categoryForSortGroup(10));
    assertEquals("reputation", PersonalCatalog.categoryForSortGroup(5));
  }

  @Test
  void requirementsAndExpansionsAreRead() {
    var c = PersonalCatalog.read(config(SAMPLE));
    var requirement = c.item("kills").requirement();
    assertEquals("%clans_in_clan%", requirement.placeholder());
    assertEquals("true", requirement.equals());
    assertEquals(Set.of("lovetweaks", "clans"), c.item("kills").expansions());
    assertEquals(Set.of("server"), c.item("datetime").expansions());
    assertNull(c.item("coords").requirement());
  }

  @Test
  void presetsParseVariantsAndResolveSkippingUnusableLines() {
    var c = PersonalCatalog.read(config(SAMPLE));
    var preset = c.presets().get("small");
    assertEquals(List.of(new Entry("coords", 0), new Entry("datetime", 2), new Entry("ghost", 0)), preset.entries());
    var resolved =
        PersonalCatalog.resolve(
            preset.entries(),
            id -> !id.equals("coords"),
            id -> c.item(id) == null ? 0 : c.item(id).variants().size(),
            8);
    assertEquals(List.of(new Entry("datetime", 2)), resolved, "coords is unusable, ghost is unknown");
    assertTrue(PersonalCatalog.sameSet(List.of(new Entry("a", 0), new Entry("b", 1)), List.of(new Entry("b", 1), new Entry("a", 0))));
    assertFalse(PersonalCatalog.sameSet(List.of(new Entry("a", 0)), List.of(new Entry("a", 1))));
  }

  @Test
  void resolvingCutsAtTheLimitDropsDuplicatesAndClampsVariants() {
    var entries = PersonalCatalog.parse(List.of("a", "b:9", "a:1", "c", "d"));
    var resolved = PersonalCatalog.resolve(entries, id -> true, id -> 2, 3);
    assertEquals(List.of(new Entry("a", 0), new Entry("b", 0), new Entry("c", 0)), resolved);
    assertEquals(new Entry("x", 0), PersonalCatalog.parse(List.of("x:oops")).getFirst());
  }

  @Test
  void aMissingPlaceholdersSectionIsAnEmptyCatalog() {
    assertTrue(PersonalCatalog.read(config("title: x")).isEmpty());
    assertTrue(PersonalCatalog.read(new YamlConfiguration()).isEmpty());
  }

  @Test
  void aBrokenIdIsRejected() {
    var bad = config("placeholders:\n  'bad id!':\n    template: x\n");
    assertThrows(IllegalArgumentException.class, () -> PersonalCatalog.validate(bad));
  }

  @Test
  void expansionIdsComeFromPercentTokens() {
    assertEquals(Set.of("server", "player"), PersonalCatalog.expansions("%server_time_x% и %player_x%"));
    assertEquals(Set.of(), PersonalCatalog.expansions("100% hp, %ping%"));
  }

  @Test
  void theShippedScoreboardYamlReadsAsACatalog() throws Exception {
    var y = new YamlConfiguration();
    try (var in = getClass().getResourceAsStream("/modules/scoreboard/scoreboard.yml")) {
      var module = new YamlConfiguration();
      module.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      y.createSection("scoreboard", module.getValues(true));
      for (String key : module.getKeys(true)) y.set("scoreboard." + key, module.get(key));
    }
    var c = PersonalCatalog.read(y);
    assertFalse(c.isEmpty());
    assertTrue(c.items().size() >= 30);
    assertEquals(8, c.max());
    assertFalse(c.presets().isEmpty());
    assertTrue(c.presets().containsKey(c.defaultPreset()));
    for (var preset : c.presets().values())
      for (var entry : preset.entries())
        assertNotNull(c.item(entry.id()), preset.id() + " names an unknown line " + entry.id());
    for (var item : c.items().values())
      assertTrue(c.categories().containsKey(item.category()), item.id());
  }
}
