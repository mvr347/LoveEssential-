package dev.lovelace.loveessential.gui;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Turns {@code %img_<name>%} / {@code %ia_<name>%} in a menu title into the ItemsAdder font glyph, so a
 * drawn background can be the title of the inventory. Reflection only: nothing happens, and the text is
 * returned as written, when ItemsAdder is not installed.
 */
public final class ItemsAdderFonts {
  private static final Logger LOG = Logger.getLogger("LoveEssential");

  private ItemsAdderFonts() {}

  public static String resolve(String text) {
    if (text == null || !(text.contains("%img_") || text.contains("%ia_"))) return text;
    if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return text;
    // The glyph is white-tinted by the client; any colour tag around it would stain the artwork.
    String tagged =
        text.replaceAll("%img_([a-zA-Z0-9_:-]+)%", ":$1:").replaceAll("%ia_([a-zA-Z0-9_:-]+)%", ":$1:");
    try {
      var fonts = Class.forName("dev.lone.itemsadder.api.FontImages");
      Object result = fonts.getMethod("replacePlaceholders", String.class).invoke(null, tagged);
      return result instanceof String resolved ? "<white>" + resolved + "</white>" : text;
    } catch (ReflectiveOperationException | LinkageError failure) {
      LOG.log(Level.FINE, "ItemsAdder font images unavailable: " + failure.getMessage());
      return text;
    }
  }
}
