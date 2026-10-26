package dev.lovelace.loveessential.travel;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class WaystoneRenderTest {
  @Test
  void eachVoidcoreVariantPlacesTheLabelAboveItsActualTop() {
    double[] tops = {1.70625, 2.01875, 2.33125, 2.70625};
    for (int i = 1; i <= 4; i++) {
      var p = WaystoneRender.defaults("itemsadder", "voidcore:waystone_" + i);
      assertEquals(
          tops[i - 1] + .25, p.label(new Location(null, 0, 0, 0), "itemsadder").getY(), 1e-9);
      assertEquals("FIXED", p.context());
      assertEquals(1, p.model(new Location(null, 0, 0, 0)).getY());
      assertEquals(.65, p.textScale());
    }
  }

  @Test
  void offsetsRotateWithTheModelAndScalingKeepsTheLabelAboveItsTop() {
    var p = new WaystoneRender(1, 1, 2, 2, 3, 1.5, .5, 1, .25, .65, true, "NONE");
    var at = new Location(null, 10.5, 64, 20.5, 90, 0);
    var model = p.model(at);
    assertEquals(6.5, model.getX(), 1e-9);
    assertEquals(66, model.getY(), 1e-9);
    assertEquals(22.5, model.getZ(), 1e-9);
    var label = p.label(at);
    assertEquals(4.5, label.getX(), 1e-9);
    assertEquals(72.25, label.getY(), 1e-9);
    assertEquals(23.5, label.getZ(), 1e-9);
    assertEquals(64, at.getY(), "locations must not mutate the persistent anchor");
    assertEquals(.65, p.textScale(), "font size does not grow with the model");
  }

  @Test
  void movingTheModelMovesItsClickBoxAndLabelTogetherAtEveryCardinalYaw() {
    for (String style : new String[] {"wood", "itemsadder"}) {
      double pivot = style.equals("itemsadder") ? 1 : 0;
      for (int yaw : new int[] {0, 90, 180, 270}) {
        var anchor = new Location(null, -7.5, 72, 8.5, yaw, 0);
        var original = new WaystoneRender(0, pivot, 0, 1.5, 3, 2, 0, 0, .25, .65, true, "FIXED");
        var moved = new WaystoneRender(1, pivot + .5, 1, 1.5, 3, 2, 0, 0, .25, .65, true, "FIXED");
        var modelDelta = moved.model(anchor).toVector().subtract(original.model(anchor).toVector());
        var boxDelta =
            moved
                .center(anchor, style)
                .toVector()
                .subtract(original.center(anchor, style).toVector());
        var textDelta =
            moved
                .label(anchor, style)
                .toVector()
                .subtract(original.label(anchor, style).toVector());
        assertEquals(0, modelDelta.distance(boxDelta), 1e-9);
        assertEquals(0, modelDelta.distance(textDelta), 1e-9);
        assertEquals(72, original.center(anchor, style).getY(), 1e-9);
        assertEquals(72 + 4.5 + .25, original.label(anchor, style).getY(), 1e-9);
      }
    }
  }

  @Test
  void customItemsPreserveTheNoneContextAndSeparateProfilesForDifferentItems() {
    assertEquals("NONE", WaystoneRender.defaults("itemsadder", "example:custom").context());
    assertEquals(0, WaystoneRender.defaults("wood", "").modelY());
    assertNotEquals(
        WaystoneRender.key("itemsadder", "example:first"),
        WaystoneRender.key("itemsadder", "example:second"));
    assertEquals(WaystoneRender.key("wood", ""), WaystoneRender.key("wood", ""));
  }

  @Test
  void nonFiniteGeometryAndInvalidDimensionsAreRejected() {
    for (double scale : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, -1, 5})
      assertThrows(
          IllegalArgumentException.class,
          () -> new WaystoneRender(0, 1, 0, scale, 2, 1, 0, 0, .25, .65, true, "NONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WaystoneRender(0, 1, 0, 1, 0, 1, 0, 0, .25, .65, true, "NONE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WaystoneRender(0, 1, 0, 1, 2, 1, 0, 0, .25, .65, true, "GUI"));
  }
}
