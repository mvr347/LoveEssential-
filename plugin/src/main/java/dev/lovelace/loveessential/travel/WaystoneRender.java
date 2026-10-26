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
    String context) {
  public WaystoneRender {
    for (double value :
        new double[] {
          modelX, modelY, modelZ, scale, height, width, centerX, centerZ, gap, textScale
        })
      if (!Double.isFinite(value)) throw new IllegalArgumentException("waystones.render-invalid");
    if (Math.abs(modelX) > 8
        || Math.abs(modelY) > 8
        || Math.abs(modelZ) > 8
        || Math.abs(centerX) > 8
        || Math.abs(centerZ) > 8
        || scale < .05
        || scale > 4
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
    return local(anchor, modelX * scale, modelY * scale, modelZ * scale);
  }

  public Location center(Location anchor) {
    return local(anchor, centerX * scale, 0, centerZ * scale);
  }

  public Location label(Location anchor) {
    return center(anchor).add(0, height * scale + gap, 0);
  }

  private static Location local(Location anchor, double x, double y, double z) {
    double angle = Math.toRadians(anchor.getYaw()), c = Math.cos(angle), s = Math.sin(angle);
    return anchor.clone().add(c * x - s * z, y, s * x + c * z);
  }
}
