package dev.lovelace.loveessential.tab;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Personal TAB toggles ({@code /le tab nametag toggle}, {@code /le tab bossbar toggle}), remembered
 * in SQLite. Reads come from a cache filled when the player joins; before that the defaults apply.
 * Main thread only.
 */
public final class TabPreferences {
  static final String KIND = "tab-player";

  /** Both flags default to false: everything is shown until the player asks otherwise. */
  public record Prefs(boolean nametagsHidden, boolean bossbarsHidden) {
    static final Prefs DEFAULT = new Prefs(false, false);
  }

  private final MainThread main;
  private final SqliteStore store;
  private final Map<UUID, SqliteStore.Document<Prefs>> documents = new HashMap<>();
  private final Set<UUID> loading = new HashSet<>();

  public TabPreferences(MainThread main, SqliteStore store) {
    this.main = main;
    this.store = store;
  }

  /** Whether the stored flags have arrived; toggles are refused before that. */
  public boolean loaded(UUID player) {
    return documents.containsKey(player);
  }

  public Prefs get(UUID player) {
    var document = documents.get(player);
    return document == null ? Prefs.DEFAULT : document.value();
  }

  /** Loads the stored flags once; a second call while loading is a no-op. */
  public void load(UUID player) {
    if (documents.containsKey(player) || !loading.add(player)) return;
    store
        .get(KIND, player.toString(), Prefs.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      loading.remove(player);
                      if (error != null) return;
                      documents.put(
                          player,
                          saved.orElseGet(
                              () -> new SqliteStore.Document<>(player.toString(), 0, Prefs.DEFAULT)));
                    }));
  }

  /** Stores the new flags; the cache changes only after the write succeeded. */
  public CompletableFuture<Void> save(UUID player, Prefs value) {
    var current = documents.get(player);
    long revision = current == null ? 0 : current.revision();
    return store
        .save(KIND, player.toString(), revision, value, player.toString())
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      documents.put(player, new SqliteStore.Document<>(player.toString(), rev, value));
                      return null;
                    }));
  }

  public void forget(UUID player) {
    documents.remove(player);
    loading.remove(player);
  }
}
