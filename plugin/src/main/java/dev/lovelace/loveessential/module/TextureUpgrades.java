package dev.lovelace.loveessential.module;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Swaps a texture that an installed config still holds from an older release for the current one. The config file
 * on disk beats the jar, so without this the owner's new textures (2026-11-12) never reached a running server.
 * Only a value equal to the OLD shipped texture is replaced: a texture the owner set themselves stays.
 */
public final class TextureUpgrades {
  private TextureUpgrades() {}

  /** Config key to {old hash to new hash}. */
  private static final Map<String, Map<String, String>> LEGACY =
      Map.of(
          "achievements.rarity-heads.COMMON",
          Map.of("55288ddc911a75f77c3a5d336365a8f8b139fa53930b4b6ee139875c80ce366c", "f5b5a9959cc2de4320304e8e0a26aaedd11fb79b6a69b2de8b89dcec2d80eb1d"),
          "achievements.rarity-heads.RARE",
          Map.of("163e6646f1c0d41fd3bf5584a1ce044f5c46d598258db46216117859f57af197", "990c5c465bc3b8e5ebf60342fa1e9eccb12563c5ff05abb7a643159478150002"),
          "achievements.rarity-heads.EPIC",
          Map.of("f97012ed6a92b05ea0f194950748544e075baa28781ca373d1b27e28c26953c", "770cacd5973980c1ec4e2164102b50d64d70b3fedfbc4a0f73127b8006cbbf9b"),
          "achievements.rarity-heads.LEGENDARY",
          Map.of("ee30480ed2834f5b7cc81dcb1dc7e4766792430c49dad4dbcd10a93c5e1457bc", "27da07fc0e4ae101cd3d6b6080b04aa75381ef8074e02ed5397594f49b71cb0c"),
          "achievements.state-heads.todo",
          Map.of("df1365ecf11bdfaf15dab2ea80f1359d274ec6d425257be29095dd2c300e992e", "93a5c336794f33e551bb9677c1120a51d3c0131f005890ceb0208d0a699a993d"),
          "profile.gui.textures.profile-menu.achievements",
          Map.of("ee30480ed2834f5b7cc81dcb1dc7e4766792430c49dad4dbcd10a93c5e1457bc", "f97e2c8b8276276e38deca8850872d59ecc9c1f38f2adce40858edb9e634d7ba"));

  private static final Pattern HASH = Pattern.compile("texture/([0-9a-f]+)");

  /** The texture to use for a config key: the new default if the configured one is the old shipped one. */
  public static String upgrade(String key, String configured) {
    if (configured == null || configured.isBlank()) return configured;
    var table = LEGACY.get(key);
    if (table == null) return configured;
    String hash = hashOf(configured);
    String replacement = hash == null ? null : table.get(hash);
    return replacement == null ? configured : replacement;
  }

  /** The Mojang texture hash of a raw hash, a textures URL or a base64 value; null if it is none of those. */
  static String hashOf(String value) {
    String v = value.strip();
    if (v.matches("[0-9a-f]{20,}")) return v;
    var direct = HASH.matcher(v);
    if (direct.find()) return direct.group(1);
    try {
      var decoded = new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8);
      var m = HASH.matcher(decoded);
      return m.find() ? m.group(1) : null;
    } catch (IllegalArgumentException notBase64) {
      return null;
    }
  }
}
