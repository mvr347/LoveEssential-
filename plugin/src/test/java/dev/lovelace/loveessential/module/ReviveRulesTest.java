package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveessential.config.ConfigError;
import dev.lovelace.loveessential.module.ReviveRules.Bar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class ReviveRulesTest {
  @BeforeAll
  static void setup() {
    MockBukkit.mock();
  }

  @AfterAll
  static void cleanup() {
    MockBukkit.unmock();
  }

  @Test
  void holdingSneakWinsOverEverythingThenReviveThenCarry() {
    assertEquals(Bar.SUICIDE, ReviveRules.bar(true, true, 0.3, true));
    assertEquals(Bar.REVIVING, ReviveRules.bar(true, true, 0, true));
    assertEquals(Bar.CARRIED, ReviveRules.bar(false, true, 0, true));
    assertEquals(Bar.GIVE_UP, ReviveRules.bar(false, false, 0, true));
    assertEquals(Bar.WAIT, ReviveRules.bar(false, false, 0, false));
  }

  @Test
  void interruptedReviveGoesBackToTheGiveUpHint() {
    assertEquals(Bar.REVIVING, ReviveRules.bar(true, false, 0, true));
    assertEquals(Bar.GIVE_UP, ReviveRules.bar(false, false, 0, true));
  }

  @Test
  void timerStandsWhileCarriedOrRevivedUnlessSwitchedOff() {
    assertTrue(ReviveRules.paused(true, false, true, true));
    assertTrue(ReviveRules.paused(false, true, true, true));
    assertFalse(ReviveRules.paused(false, false, true, true));
    assertFalse(ReviveRules.paused(false, true, true, false));
    assertFalse(ReviveRules.paused(true, false, false, true));
  }

  @Test
  void soundLineHasDefaultsAndRanges() {
    var cue = ReviveRules.cue("block.note_block.chime");
    assertEquals(new ReviveRules.Cue("block.note_block.chime", 1f, 1f), cue);
    assertEquals(0.6f, ReviveRules.cue("x 0.6 1.5").volume());
    assertNull(ReviveRules.cue(""));
    assertThrows(IllegalArgumentException.class, () -> ReviveRules.cue("x 1 3"));
    assertThrows(IllegalArgumentException.class, () -> ReviveRules.cue("x a"));
    assertThrows(IllegalArgumentException.class, () -> ReviveRules.cue("x 1 1 1"));
  }

  @Test
  void bukkitConstantsAndGameKeysBothResolve() {
    assertNotNull(SoundNames.find("BLOCK_NOTE_BLOCK_CHIME"));
    assertNotNull(SoundNames.find("block.note_block.chime"));
    assertNotNull(SoundNames.find("minecraft:entity.player.levelup"));
    assertNull(SoundNames.find("no.such.sound"));
    assertNull(SoundNames.find(" "));
  }

  @Test
  void configNamesTheBrokenSoundKey() {
    var c = new YamlConfiguration();
    c.set("revive.pose", "SLEEPING");
    c.set("revive.sounds.start", "block.note_block.chime 1.0 1.2");
    assertDoesNotThrow(() -> Revive.validate(c));
    c.set("revive.sounds.stop", "no.such.sound 1 1");
    var e = assertThrows(ConfigError.class, () -> Revive.validate(c));
    assertTrue(e.detail().contains("revive.sounds.stop"));
    c.set("revive.sounds.stop", "");
    c.set("revive.pose", "STANDING");
    assertTrue(assertThrows(ConfigError.class, () -> Revive.validate(c)).detail().contains("revive.pose"));
  }
}
