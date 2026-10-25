package dev.lovelace.loveessential.module;

import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;

/** Sounds by the name an admin writes in a config file. */
final class SoundNames {
  private SoundNames() {}

  /**
   * {@code BLOCK_NOTE_BLOCK_CHIME} (the Bukkit constant) or {@code block.note_block.chime} /
   * {@code minecraft:block.note_block.chime} (the game key); null when there is no such sound.
   * Turning every underscore into a dot would break keys that contain one ({@code note_block}), so
   * constants are looked up by name and only keys are looked up as written.
   */
  static Sound find(String name) {
    if (name == null || name.isBlank()) return null;
    String text = name.trim();
    if (text.indexOf('.') < 0 && text.indexOf(':') < 0) {
      try {
        if (Sound.class.getField(text.toUpperCase(Locale.ROOT)).get(null) instanceof Sound sound)
          return sound;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // Not a constant: try it as a key below.
      }
    }
    String key = text.toLowerCase(Locale.ROOT);
    if (key.contains(":")) key = key.substring(key.indexOf(':') + 1);
    var parsed = NamespacedKey.fromString("minecraft:" + key);
    return parsed == null ? null : Registry.SOUNDS.get(parsed);
  }
}
