package dev.lovelace.loveessential.tab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Decoration of the clan tag in the tab list, the same idea as {@code chat.display.clan.format} in LoveChat:
 * {@code "🏰 <gray>[</gray><aqua>%tag%</aqua><gray>]</gray> "}. The whole decoration, emoji and brackets
 * included, disappears when the player has no clan, so there is never a bare emoji without a tag.
 */
final class ClanFormat {
  static final String DEFAULT = "%tag%";
  private static final String PLACEHOLDER = "clan_tag_value";

  private ClanFormat() {}

  /**
   * @param format admin-written MiniMessage template with {@code %tag%}; blank means just the tag
   * @param tag the clan tag as typed by players, inserted as plain text (never parsed as markup)
   * @return empty when the tag is blank
   */
  static Component apply(MiniMessage mm, String format, String tag) {
    if (tag == null || tag.isBlank()) return Component.empty();
    String pattern = format == null || format.isBlank() ? DEFAULT : format;
    return mm.deserialize(
        pattern.replace("%tag%", "<" + PLACEHOLDER + ">"), Placeholder.unparsed(PLACEHOLDER, tag));
  }
}
