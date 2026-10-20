package dev.lovelace.loveessential.portal;

import java.util.*;
import org.bukkit.util.Vector;

/** Bounded deterministic samples; no chunk loading or per-block entities. */
public final class PortalGeometry {
  private PortalGeometry() {}

  public static List<Vector> points(PortalDefinition p, int budget, double progress, long frame) {
    double x = Math.min(p.first().x(), p.second().x()),
        y = Math.min(p.first().y(), p.second().y()),
        z = Math.min(p.first().z(), p.second().z());
    double w = Math.abs(p.first().x() - p.second().x()) + 1,
        h = Math.abs(p.first().y() - p.second().y()) + 1,
        d = Math.abs(p.first().z() - p.second().z()) + 1;
    boolean alongX = w >= d;
    double width = alongX ? w : d;
    int columns = Math.max(1, (int) Math.ceil(width / p.spacing())),
        rows = Math.max(1, (int) Math.ceil(h / p.spacing()));
    var result = new ArrayList<Vector>();
    int total = (columns + 1) * (rows + 1);
    int stride = Math.max(1, (int) Math.ceil(total / (double) Math.max(1, budget)));
    for (int n = (int) Math.floorMod(frame, stride);
        n < total && result.size() < budget;
        n += stride) {
      int column = n % (columns + 1), row = n / (columns + 1);
      boolean edge = column == 0 || column == columns || row == 0 || row == rows;
      double height = h * row / rows;
      if ((!p.fill() && !edge) || height > h * Math.clamp(progress, 0, 1)) continue;
      double u = width * column / columns;
      result.add(new Vector(alongX ? x + u : x + w / 2, y + height, alongX ? z + d / 2 : z + u));
    }
    return List.copyOf(result);
  }
}
