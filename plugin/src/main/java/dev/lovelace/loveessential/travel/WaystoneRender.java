package dev.lovelace.loveessential.travel;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;

/** Geometry in blocks at scale 1. Offsets are local to the stone's yaw; gap/font stay readable. */
public record WaystoneRender(
    double modelX,
    double modelY,
    double modelZ,
    double scale,
    double height,
    double width,
    double centerX,
    double centerZ,
    double gap,
    double textScale,
    boolean text,
    String context,
    double factor) {
  /** Shared profile as saved: no per-stone multiplier. */
  public WaystoneRender(
      double modelX,
      double modelY,
      double modelZ,
      double scale,
      double height,
      double width,
      double centerX,
      double centerZ,
      double gap,
      double textScale,
      boolean text,
      String context) {
    this(
        modelX, modelY, modelZ, scale, height, width, centerX, centerZ, gap, textScale, text,
        context, 1);
  }

  /** Scale actually used for geometry: the shared profile scale times the stone's own factor. */
  public double effective() {
    return scale * (factor > 0 ? factor : 1);
  }

  public WaystoneRender {
    for (double value :
        new double[] {
          modelX, modelY, modelZ, scale, height, width, centerX, centerZ, gap, textScale
        })
      if (!Double.isFinite(value) || !Double.isFinite(factor)) throw new IllegalArgumentException("waystones.render-invalid");
    if (Math.abs(modelX) > 8
        || Math.abs(modelY) > 8
        || Math.abs(modelZ) > 8
        || Math.abs(centerX) > 8
        || Math.abs(centerZ) > 8
        || scale < .05
        || scale > 4
        || factor < 0
        || factor > 40
        || height < .1
        || height > 16
        || width < .1
        || width > 8
        || gap < -2
        || gap > 8
        || textScale < .1
        || textScale > 3
        || context == null
        || !Set.of("NONE", "FIXED").contains(context))
      throw new IllegalArgumentException("waystones.render-invalid");
  }

  /** The same geometry grown by one stone's own multiplier; text size stays readable. */
  public WaystoneRender scaled(double factor) {
    return new WaystoneRender(
        modelX, modelY, modelZ, scale, height, width, centerX, centerZ, gap, textScale, text,
        context, factor);
  }

  public static String key(String style, String item) {
    return UUID.nameUUIDFromBytes((style + "\0" + item).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  public static WaystoneRender defaults(String style, String item) {
    double height = 2.1, width = 1.4;
    boolean preset =
        style.equals("itemsadder") && item.matches("(?:voidcore|loveessential):waystone_[1-4]");
    if (preset) {
      int variant = item.charAt(item.length() - 1) - '1';
      height = new double[] {1.70625, 2.01875, 2.33125, 2.70625}[variant];
      width = variant < 2 ? 1.7 : 2;
    }
    return new WaystoneRender(
        0,
        style.equals("itemsadder") ? 1 : 0,
        0,
        1,
        height,
        width,
        0,
        0,
        .25,
        .65,
        true,
        preset ? "FIXED" : "NONE");
  }

  public Location model(Location anchor) {
    return local(anchor, modelX * effective(), modelY * effective(), modelZ * effective());
  }

  public Location center(Location anchor) {
    return local(anchor, (modelX + centerX) * effective(), modelY * effective(), (modelZ + centerZ) * effective());
  }

  /**
   * Items are centred on their display origin; their default +1 lift puts the base on the anchor.
   * Keep that pivot correction separate from the user offset applied to all three entities.
   */
  public Location center(Location anchor, String style) {
    return center(anchor).subtract(0, (style.equals("itemsadder") ? 1 : 0) * effective(), 0);
  }

  public Location label(Location anchor, String style) {
    return center(anchor, style).add(0, height * effective() + gap, 0);
  }

  public Location label(Location anchor) {
    return center(anchor).add(0, height * effective() + gap, 0);
  }

  private static Location local(Location anchor, double x, double y, double z) {
    double angle = Math.toRadians(anchor.getYaw()), c = Math.cos(angle), s = Math.sin(angle);
    return anchor.clone().add(c * x - s * z, y, s * x + c * z);
  }
}
