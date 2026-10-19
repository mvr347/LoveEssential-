package dev.lovelace.loveessential.api;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/** Bukkit service. Read/registration calls require the server thread; writes marshal to it. */
public interface HologramService {
  /** Item bytes are ItemStack.serializeAsBytes(), Base64 encoded; text references lang.yml. */
  record Line(String textKey, String item) {
    public Line {
      textKey = Objects.requireNonNullElse(textKey, "");
      item = Objects.requireNonNullElse(item, "");
      if (textKey.isBlank() == item.isBlank() || textKey.length() > 200 || item.length() > 131072)
        throw new IllegalArgumentException("errors.input");
    }
  }

  record Page(List<Line> lines, Action action) {
    public Page {
      lines = List.copyOf(lines);
      if (lines.size() > 32) throw new IllegalArgumentException("errors.input");
    }
  }

  /** Position anchors the bottom row; subsequent layout grows upward. */
  record Hologram(
      String id,
      Position position,
      List<Page> pages,
      int background,
      boolean shadow,
      double spacing,
      double range) {
    public Hologram {
      Objects.requireNonNull(position);
      pages = List.copyOf(pages);
      if (id == null
          || !id.matches("[a-z0-9_-]{1,48}")
          || pages.isEmpty()
          || pages.size() > 16
          || !Double.isFinite(spacing)
          || spacing < .2
          || spacing > 2
          || !Double.isFinite(range)
          || range < 4
          || range > 96) throw new IllegalArgumentException("errors.input");
    }
  }

  record Snapshot(Hologram value, long revision) {}

  @FunctionalInterface
  interface ClickHandler {
    /** Return true to consume this click, e.g. open a shop; false permits normal page switching. */
    boolean click(Player player, String hologramId, int page);
  }

  String nextId();

  Collection<Snapshot> all();

  Optional<Snapshot> get(String id);

  CompletableFuture<Snapshot> save(Hologram hologram, long expectedRevision);

  CompletableFuture<Void> delete(String id, long expectedRevision);

  LoveEssentialService.Registration onClick(String hologramId, ClickHandler handler);
}
