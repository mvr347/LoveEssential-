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

  @Test
  void theOldLegendaryAndTodoHeadsAreReplaced() {
    assertEquals(
        "27da07fc0e4ae101cd3d6b6080b04aa75381ef8074e02ed5397594f49b71cb0c",
        TextureUpgrades.upgrade(
            "achievements.rarity-heads.LEGENDARY",
            base64("ee30480ed2834f5b7cc81dcb1dc7e4766792430c49dad4dbcd10a93c5e1457bc")));
    assertEquals(
        "93a5c336794f33e551bb9677c1120a51d3c0131f005890ceb0208d0a699a993d",
        TextureUpgrades.upgrade(
            "achievements.state-heads.todo",
            base64("df1365ecf11bdfaf15dab2ea80f1359d274ec6d425257be29095dd2c300e992e")));
  }
}
