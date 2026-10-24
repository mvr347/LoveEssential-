package dev.lovelace.loveessential.npc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LoveEssentialNpcProviderTest {
  @Test
  void answersWithNothingUntilTheDataIsLoaded() {
    var provider = new LoveEssentialNpcProvider(null, null);
    assertFalse(provider.bound());
    assertEquals("loveessential", provider.providerId());
    assertTrue(provider.byKey("anything").isEmpty());
    assertTrue(provider.byEntity(null).isEmpty());
    assertTrue(provider.all().isEmpty());
    assertFalse(provider.remove("anything"));
    assertDoesNotThrow(provider::close);
  }
}
