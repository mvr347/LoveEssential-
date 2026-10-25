package dev.lovelace.loveessential.npc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bindings that other Love plugins keep in their {@code config.yml} as the number of a Citizens NPC.
 * After an import the number is replaced by the key of the new LoveEssential NPC.
 */
public final class CitizensRebind {
  /** A plugin and the config key holding one NPC binding. */
  public record Target(String plugin, String path) {}

  public static final List<Target> TARGETS =
      List.of(
          new Target("LoveTweaks", "herald.npc-key"),
          new Target("LoveClans", "clans.guildmaster.npc-key"),
          new Target("LoveClans", "clans.banner.npc-key"),
          new Target("LoveHunt", "npc.turn-in-key"),
          new Target("LoveContracts", "npc.key"));

  private CitizensRebind() {}

  /** The new key for a binding that still holds a Citizens number from {@code mapping}; empty otherwise. */
  public static Optional<String> replacement(Object current, Map<Integer, String> mapping) {
    if (current == null) return Optional.empty();
    String text = current.toString().trim();
    if (!text.matches("\\d{1,9}")) return Optional.empty();
    return Optional.ofNullable(mapping.get(Integer.parseInt(text)));
  }
}
