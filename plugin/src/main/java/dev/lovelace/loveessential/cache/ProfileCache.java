package dev.lovelace.loveessential.cache;

import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Weigh completed payloads separately from bounded, coalesced in-flight reads. */
public final class ProfileCache implements AutoCloseable {
  public record Key(UUID player, String name) {}

  private final Cache<Key, Optional<String>> completed;
  private final Map<Key, CompletableFuture<Optional<String>>> pending = new HashMap<>();
  private final Set<CompletableFuture<Optional<String>>> inFlight = new HashSet<>();
  private final Semaphore limit = new Semaphore(64);
  private boolean closed;

  public ProfileCache(long bytes) {
    completed =
        Caffeine.newBuilder()
            .maximumWeight(bytes)
            .weigher(
                (Key key, Optional<String> value) ->
                    Math.toIntExact(
                        Math.min(
                            Integer.MAX_VALUE,
                            256L
                                + key.name().length() * 2L
                                + value.map(String::length).orElse(0) * 2L)))
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();
  }

  public synchronized CompletableFuture<Optional<String>> get(
      Key key, Supplier<CompletableFuture<Optional<String>>> loader) {
    if (closed) return CompletableFuture.failedFuture(new CancellationException("cache-closed"));
    var value = completed.getIfPresent(key);
    if (value != null) return CompletableFuture.completedFuture(value);
    var existing = pending.get(key);
    if (existing != null) return existing.copy();
    if (!limit.tryAcquire())
      return CompletableFuture.failedFuture(new RejectedExecutionException("storage-busy"));
    var result = new CompletableFuture<Optional<String>>();
    pending.put(key, result);
    inFlight.add(result);
    try {
      loader
          .get()
          .whenComplete(
              (loaded, error) -> {
                synchronized (this) {
                  if (pending.remove(key, result) && !closed && error == null)
                    completed.put(key, loaded);
                  inFlight.remove(result);
                  limit.release();
                }
                if (error == null) result.complete(loaded);
                else result.completeExceptionally(error);
              });
    } catch (Throwable error) {
      pending.remove(key, result);
      inFlight.remove(result);
      limit.release();
      result.completeExceptionally(error);
    }
    return result.copy();
  }

  public synchronized void invalidate(Key key) {
    completed.invalidate(key);
    // An older read may complete for its caller, but cannot repopulate the cache after a write.
    pending.remove(key);
  }

  public synchronized void close() {
    closed = true;
    completed.invalidateAll();
    for (var future : List.copyOf(inFlight))
      future.completeExceptionally(new CancellationException("cache-closed"));
    pending.clear();
    inFlight.clear();
  }

  public long weight() {
    completed.cleanUp();
    return completed.policy().eviction().orElseThrow().weightedSize().orElse(0);
  }
}
