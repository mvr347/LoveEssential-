package dev.lovelace.loveessential.command;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.command.NpcCommandLogic.Kind;
import dev.lovelace.loveessential.command.NpcCommandLogic.Row;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcCommandLogicTest {
  static final UUID WORLD = UUID.randomUUID();
  static final UUID OTHER = UUID.randomUUID();

  static Row row(int number, boolean enabled, UUID world, double x, double z) {
    return new Row("npc_" + number, number, "NPC " + number, enabled, world, x, 64, z);
  }

  @Test
  void bareListIsAllOnPageOne() {
    var q = NpcCommandLogic.parseList(new String[0]);
    assertEquals(Kind.ALL, q.kind());
    assertEquals(1, q.page());
  }

  @Test
  void filtersAcceptEnglishAndRussianWords() {
    assertEquals(Kind.ENABLED, NpcCommandLogic.parseList(new String[] {"on"}).kind());
    assertEquals(Kind.ENABLED, NpcCommandLogic.parseList(new String[] {"вкл"}).kind());
    assertEquals(Kind.DISABLED, NpcCommandLogic.parseList(new String[] {"off"}).kind());
    assertEquals(Kind.DISABLED, NpcCommandLogic.parseList(new String[] {"выкл"}).kind());
    assertEquals(Kind.ALL, NpcCommandLogic.parseList(new String[] {"все"}).kind());
    var world = NpcCommandLogic.parseList(new String[] {"мир", "world_nether"});
    assertEquals(Kind.WORLD, world.kind());
    assertEquals("world_nether", world.worldName());
  }

  @Test
  void aBareNumberIsAPageAndANumberAfterNearIsTheRadius() {
    assertEquals(3, NpcCommandLogic.parseList(new String[] {"3"}).page());
    var near = NpcCommandLogic.parseList(new String[] {"near", "50", "2"});
    assertEquals(Kind.NEAR, near.kind());
    assertEquals(50, near.radius());
    assertEquals(2, near.page());
    assertEquals(NpcCommandLogic.DEFAULT_NEAR_RADIUS, NpcCommandLogic.parseList(new String[] {"near"}).radius());
  }

  @Test
  void theRadiusIsClamped() {
    assertEquals(NpcCommandLogic.MAX_NEAR_RADIUS, NpcCommandLogic.radius("100000"));
  }

  @Test
  void wrongWordsAndNumbersAreInputErrors() {
    for (var words : List.of(new String[] {"nonsense"}, new String[] {"0"}, new String[] {"near", "0"},
        new String[] {"world"}, new String[] {"on", "1", "2"}, new String[] {"on", "x"})) {
      var error = assertThrows(IllegalArgumentException.class, () -> NpcCommandLogic.parseList(words));
      assertEquals("errors.input", error.getMessage());
    }
  }

  @Test
  void enabledAndDisabledFiltersSelectByState() {
    var rows = List.of(row(1, true, WORLD, 0, 0), row(2, false, WORLD, 0, 0), row(3, true, OTHER, 0, 0));
    var on = NpcCommandLogic.select(rows, NpcCommandLogic.parseList(new String[] {"on"}), null, WORLD, 0, 64, 0);
    assertEquals(List.of(1, 3), on.stream().map(Row::number).toList());
    var off = NpcCommandLogic.select(rows, NpcCommandLogic.parseList(new String[] {"off"}), null, WORLD, 0, 64, 0);
    assertEquals(List.of(2), off.stream().map(Row::number).toList());
  }

  @Test
  void worldFilterNeedsAKnownWorld() {
    var rows = List.of(row(1, true, WORLD, 0, 0), row(2, true, OTHER, 0, 0));
    var query = NpcCommandLogic.parseList(new String[] {"world", "x"});
    assertEquals(List.of(2), NpcCommandLogic.select(rows, query, OTHER, WORLD, 0, 64, 0).stream().map(Row::number).toList());
    assertTrue(NpcCommandLogic.select(rows, query, null, WORLD, 0, 64, 0).isEmpty());
  }

  @Test
  void nearIsOrderedByDistanceStaysInTheWorldAndRespectsTheRadius() {
    var rows = List.of(row(1, true, WORLD, 30, 0), row(2, true, WORLD, 5, 0), row(3, true, OTHER, 1, 0), row(4, true, WORLD, 100, 0));
    var near = NpcCommandLogic.select(rows, NpcCommandLogic.parseList(new String[] {"near", "40"}), null, WORLD, 0, 64, 0);
    assertEquals(List.of(2, 1), near.stream().map(Row::number).toList());
  }

  @Test
  void distanceIsMinusOneAcrossWorlds() {
    assertEquals(5, NpcCommandLogic.distance(row(1, true, WORLD, 3, 4), WORLD, 0, 64, 0), 1e-9);
    assertEquals(-1, NpcCommandLogic.distance(row(1, true, OTHER, 3, 4), WORLD, 0, 64, 0));
  }

  @Test
  void pagingCutsEightToAPage() {
    var all = java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList();
    assertEquals(3, NpcCommandLogic.pages(all.size()));
    assertEquals(1, NpcCommandLogic.pages(0));
    assertEquals(8, NpcCommandLogic.page(all, 1).size());
    assertEquals(4, NpcCommandLogic.page(all, 3).size());
    assertTrue(NpcCommandLogic.page(all, 9).isEmpty());
  }

  @Test
  void renameTakesALeadingNpcOnlyWhenANameFollows() {
    Set<String> known = Set.of("3", "guard");
    var byNumber = NpcCommandLogic.parseRename(new String[] {"3", "&eБанкир", "Иван"}, known::contains);
    assertEquals("3", byNumber.ref());
    assertEquals("&eБанкир Иван", byNumber.name());
    var selection = NpcCommandLogic.parseRename(new String[] {"Банкир"}, known::contains);
    assertNull(selection.ref());
    assertEquals("Банкир", selection.name());
    var lone = NpcCommandLogic.parseRename(new String[] {"3"}, known::contains);
    assertNull(lone.ref(), "a lone word is the name even when it looks like a number");
    assertEquals("3", lone.name());
  }

  @Test
  void renameWithoutWordsIsAUsageError() {
    var error = assertThrows(IllegalArgumentException.class, () -> NpcCommandLogic.parseRename(new String[0], s -> false));
    assertEquals("npc.rename-usage", error.getMessage());
  }

  @Test
  void newTakesTheNameAndOptionalFlagsAnywhere() {
    var plain = NpcCommandLogic.parseNew(new String[] {"&eБанкир", "Иван"});
    assertEquals("&eБанкир Иван", plain.name());
    assertNull(plain.skin());
    assertNull(plain.type());
    var flags = NpcCommandLogic.parseNew(new String[] {"--skin", "Notch", "Страж", "--type", "villager", "Ворот"});
    assertEquals("Страж Ворот", flags.name());
    assertEquals("Notch", flags.skin());
    assertEquals("villager", flags.type());
    assertEquals("Mojang", NpcCommandLogic.parseNew(new String[] {"Имя", "--скин", "Mojang"}).skin());
  }

  @Test
  void newWithoutANameOrWithADanglingFlagIsAUsageError() {
    for (var words : List.of(new String[0], new String[] {"--skin", "Notch"}, new String[] {"Имя", "--skin"}, new String[] {"Имя", "--type"})) {
      var error = assertThrows(IllegalArgumentException.class, () -> NpcCommandLogic.parseNew(words));
      assertEquals("npc.new-usage", error.getMessage());
    }
  }
}
