package dev.lovelace.loveessential.gui;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;

/**
 * Turns {@code %img_<name>%} / {@code %ia_<name>%} in a menu title into the ItemsAdder font glyph, so a
 * drawn background can be the title of the inventory. Reflection only: nothing happens, and the text is
 * returned as written, when ItemsAdder is not installed.
 */
public final class ItemsAdderFonts {
  private static final Logger LOG = Logger.getLogger("LoveEssential");
  private static final Pattern TOKEN = Pattern.compile("%(?:img|ia)_([a-zA-Z0-9_:-]+)%");
  // Strict serialization closes every color tag, so an image cannot recolor the following text.
  private static final MiniMessage MINI = MiniMessage.builder().strict(true).build();

  private ItemsAdderFonts() {}

  public static String resolve(String text) {
    if (text == null || !(text.contains("%img_") || text.contains("%ia_"))) return text;
    if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return text;
    // The glyph is white-tinted by the client; any colour tag around it would stain the artwork.
    try {
      // FontImages is a package in the ItemsAdder API; the entry point is its FontImageWrapper class.
      var fonts = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
      var replace = fonts.getMethod("replaceFontImages", String.class);
      var tokens = TOKEN.matcher(text);
      var out = new StringBuilder();
      int from = 0;
      while (tokens.find()) {
        Object result = replace.invoke(null, ":" + tokens.group(1) + ":");
        if (!(result instanceof String resolved)) return text;
        out.append(text, from, tokens.start());
        // Only external glyphs use legacy syntax; keep the surrounding MiniMessage unchanged.
        out.append(MINI.serialize(LegacyComponentSerializer.legacySection().deserialize(resolved)));
        from = tokens.end();
      }
      return "<white>" + out.append(text, from, text.length()) + "</white>";
    } catch (ReflectiveOperationException | LinkageError failure) {
      LOG.log(Level.FINE, "ItemsAdder font images unavailable: " + failure.getMessage());
      return text;
    }
  }
}
