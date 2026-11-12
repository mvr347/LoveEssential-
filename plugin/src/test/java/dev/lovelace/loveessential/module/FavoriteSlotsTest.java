package dev.lovelace.loveessential.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FavoriteSlotsTest {
  @Test
  void threeFavouriteSlotsAreCentredOnTheGuiGenTable() {
    assertEquals(List.of(2, 4, 6), List.of(PlayerProfiles.favoriteSlot(0), PlayerProfiles.favoriteSlot(1), PlayerProfiles.favoriteSlot(2)));
  }
}
