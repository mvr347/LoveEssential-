package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MojangSkinsTest {
  @Test
  void readsTheAccountIdAndIgnoresGarbage() {
    assertEquals("069a79f444e94726a5befca90e38aaf5", MojangSkins.parseId("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}").orElseThrow());
    assertTrue(MojangSkins.parseId("{\"errorMessage\":\"no\"}").isEmpty());
    assertTrue(MojangSkins.parseId("not json").isEmpty());
  }

  @Test
  void picksTheTexturesPropertyWithItsSignature() {
    var json = "{\"id\":\"x\",\"properties\":[{\"name\":\"other\",\"value\":\"a\"},{\"name\":\"textures\",\"value\":\"VAL\",\"signature\":\"SIG\"}]}";
    var textures = MojangSkins.parseTextures(json).orElseThrow();
    assertEquals("VAL", textures.value());
    assertEquals("SIG", textures.signature());
    assertTrue(MojangSkins.parseTextures("{\"properties\":[]}").isEmpty());
    assertTrue(MojangSkins.parseTextures("{").isEmpty());
  }
}
