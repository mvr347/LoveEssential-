package dev.lovelace.loveessential.module;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread request identities: a late join read or HTTP reply must not replace a newer skin. */
final class SkinRequests {
  private final Map<UUID, Object> current = new HashMap<>();

  Object begin(UUID player) {
    Object token = new Object();
    current.put(player, token);
    return token;
  }

  boolean current(UUID player, Object token) {
    return current.get(player) == token;
  }

  void forget(UUID player) {
    current.remove(player);
  }

  void clear() {
    current.clear();
  }
}
