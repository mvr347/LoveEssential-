package dev.lovelace.loveessential.module;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkinRequestsTest {
  @Test void lateJoinReadOrOldLookupCannotOverwriteANewerChoice() {
    var requests = new SkinRequests();
    var player = UUID.randomUUID();
    var join = requests.begin(player);
    var first = requests.begin(player);
    var other = UUID.randomUUID();
    var unrelated = requests.begin(other);
    var second = requests.begin(player);
    assertFalse(requests.current(player, join));
    assertFalse(requests.current(player, first));
    assertTrue(requests.current(player, second));
    assertTrue(requests.current(other, unrelated));
  }

  @Test void relogAndStopInvalidateRepliesFromThePreviousSession() {
    var requests = new SkinRequests();
    var player = UUID.randomUUID();
    var old = requests.begin(player);
    requests.forget(player);
    var joined = requests.begin(player);
    assertFalse(requests.current(player, old));
    assertTrue(requests.current(player, joined));
    requests.clear();
    assertFalse(requests.current(player, joined));
  }
}
