package dev.lovelace.loveessential.travel;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.api.Position;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WaystoneSizeTest {
  static final Position AT = new Position(UUID.randomUUID(), 0, 64, 0, 0, 0);

  static Waystone stone(double size) {
    return new Waystone("1", "A", AT, AT, "stone", "", List.of(), size);
  }

  @Test
  void legacyRecordsWithoutSizeKeepTheirLook() {
    // Gson leaves the missing component at 0.0 and never runs the constructor.
    var legacy = new com.google.gson.Gson().fromJson(
        new com.google.gson.Gson().toJson(stone(1)).replace(",\"size\":1.0", ""), Waystone.class);
    assertEquals(0, legacy.size());
    assertEquals(1, legacy.factor());
  }

  @Test
  void sevenArgumentConstructorMeansNormalSize() {
    assertEquals(1, new Waystone("1", "A", AT, AT, "stone", "", List.of()).factor());
  }

  @Test
  void acceptsTheDocumentedRangeOnly() {
    assertEquals(2.5, stone(2.5).factor());
    assertEquals(.25, stone(.25).factor());
    assertEquals(5, stone(5).factor());
    assertThrows(IllegalArgumentException.class, () -> stone(.2));
    assertThrows(IllegalArgumentException.class, () -> stone(5.1));
    assertThrows(IllegalArgumentException.class, () -> stone(Double.NaN));
  }

  @Test
  void copiesKeepTheSize() {
    var n = stone(2);
    assertEquals(2, n.withArrival(AT).factor());
    assertEquals(2, n.withAnchor(AT).factor());
    assertEquals(2, n.withRoutes(List.of()).factor());
    assertEquals(3, n.withAppearance("B", "wood", "", 3, AT).factor());
  }

  @Test
  void scalingGrowsGeometryButNotTheLabelText() {
    var base = WaystoneRender.defaults("stone", "");
    var big = base.scaled(2);
    assertEquals(base.scale(), big.scale(), "shared profile scale is untouched");
    assertEquals(base.effective() * 2, big.effective());
    assertEquals(base.textScale(), big.textScale());
    assertEquals(base.gap(), big.gap());
    var at = new org.bukkit.Location(null, 0, 64, 0, 0, 0);
    assertEquals(
        (base.label(at).getY() - 64 - base.gap()) * 2,
        big.label(at).getY() - 64 - big.gap(),
        1e-9);
  }

  @Test
  void oldProfilesWithoutAFactorBehaveAsBefore() {
    var legacy = new WaystoneRender(0, 1, 0, 1.5, 3, 2, 0, 0, .25, .65, true, "NONE", 0);
    assertEquals(1.5, legacy.effective());
  }

  @Test
  void profileScaleStillCapsAtFour() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WaystoneRender(0, 1, 0, 5, 3, 2, 0, 0, .25, .65, true, "NONE"));
  }
}
