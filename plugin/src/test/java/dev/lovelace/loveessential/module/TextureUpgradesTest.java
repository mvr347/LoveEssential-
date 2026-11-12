package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TextureUpgradesTest {
  private static String base64(String hash) {
    String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
    return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private static final String OLD_COMMON = "55288ddc911a75f77c3a5d336365a8f8b139fa53930b4b6ee139875c80ce366c";
  private static final String NEW_COMMON = "f5b5a9959cc2de4320304e8e0a26aaedd11fb79b6a69b2de8b89dcec2d80eb1d";

  @Test
  void anOldShippedTextureIsReplaced() {
    assertEquals(NEW_COMMON, TextureUpgrades.upgrade("achievements.rarity-heads.COMMON", base64(OLD_COMMON)));
    assertEquals(NEW_COMMON, TextureUpgrades.upgrade("achievements.rarity-heads.COMMON", OLD_COMMON));
  }

  @Test
  void aTextureTheOwnerChoseStays() {
    String own = base64("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    assertEquals(own, TextureUpgrades.upgrade("achievements.rarity-heads.COMMON", own));
  }

  @Test
  void otherKeysAndBlankValuesAreUntouched() {
    assertEquals(base64(OLD_COMMON), TextureUpgrades.upgrade("achievements.rarity-heads.LEGENDARY", base64(OLD_COMMON)));
    assertEquals("", TextureUpgrades.upgrade("achievements.rarity-heads.COMMON", ""));
    assertNull(TextureUpgrades.upgrade("achievements.rarity-heads.COMMON", null));
  }

  @Test
  void theOldProfileAchievementsButtonIsReplaced() {
    String old = "ee30480ed2834f5b7cc81dcb1dc7e4766792430c49dad4dbcd10a93c5e1457bc";
    assertEquals(
        "f97e2c8b8276276e38deca8850872d59ecc9c1f38f2adce40858edb9e634d7ba",
        TextureUpgrades.upgrade("profile.gui.textures.profile-menu.achievements", base64(old)));
  }
}
