package dev.lovelace.loveessential;

import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.condition.*;
import dev.lovelace.loveessential.npc.NpcService;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class EssentialApi implements LoveEssentialService {
  private final dev.lovelace.loveessential.cache.ProfileCache profiles;

  public void close() {
    profiles.close();
  }

  private final NpcService npcs;
  private final ConditionEngine conditions;
  private final ActionEngine actions;
  private final SqliteStore store;

  public EssentialApi(NpcService n, ConditionEngine c, ActionEngine a, SqliteStore s) {
    this(n, c, a, s, 16L * 1024 * 1024);
  }

  public EssentialApi(
      NpcService n, ConditionEngine c, ActionEngine a, SqliteStore s, long cacheBytes) {
    profiles = new dev.lovelace.loveessential.cache.ProfileCache(cacheBytes);
    npcs = n;
    conditions = c;
    actions = a;
    store = s;
    // Internal writers (vanish, skin, traces) bypass this class, so the cache listens to the store.
    s.onProfileWritten(
        (player, key) ->
            profiles.invalidate(new dev.lovelace.loveessential.cache.ProfileCache.Key(player, key)));
  }

  public Optional<NpcDefinition> npc(String id) {
    return npcs.get(id);
  }

  public Collection<NpcDefinition> npcs() {
    return npcs.all();
  }

  public CompletableFuture<NpcDefinition> save(NpcDefinition n, long expected) {
    return npcs.save(n, expected);
  }

  public CompletableFuture<Void> delete(String id, long expected) {
    return npcs.delete(id, expected);
  }

  private static void id(String id) {
    if (!id.matches("[a-z0-9_-]+:[a-z0-9_/-]+"))
      throw new IllegalArgumentException("namespaced-id-required");
  }

  public Registration registerCondition(String id, ConditionProvider p) {
    id(id);
    return conditions.register(id, p);
  }

  public Registration registerAction(String id, ActionProvider p) {
    id(id);
    return actions.register(id, p);
  }

  public Registration registerInteraction(String id, int priority, InteractionProvider p) {
    id(id);
    return npcs.interaction(id, priority, p);
  }

  private static void key(String key) {
    if (!key.matches("[a-z0-9_-]+[.:][a-zA-Z0-9_.:-]{1,120}"))
      throw new IllegalArgumentException("namespaced-key-required");
  }

  public CompletableFuture<Optional<String>> profile(UUID player, String key) {
    key(key);
    var cacheKey = new dev.lovelace.loveessential.cache.ProfileCache.Key(player, key);
    return profiles.get(cacheKey, () -> store.profile(player, key));
  }

  public CompletableFuture<Void> profile(UUID player, String key, String value) {
    key(key);
    if (value.length() > 65536) throw new IllegalArgumentException("profile-value-too-large");
    return store
        .profile(player, key, value)
        .thenRun(
            () ->
                profiles.invalidate(
                    new dev.lovelace.loveessential.cache.ProfileCache.Key(player, key)));
  }
}
