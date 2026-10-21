package dev.lovelace.loveessential.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProfileCacheTest {
  @Test
  void readsCoalesceButCancellationDoesNotCancelOtherCallers() {
    try (var cache = new ProfileCache(1024)) {
      var key = new ProfileCache.Key(UUID.randomUUID(), "test.value");
      var source = new CompletableFuture<Optional<String>>();
      var calls = new AtomicInteger();
      var first =
          cache.get(
              key,
              () -> {
                calls.incrementAndGet();
                return source;
              });
      var second =
          cache.get(
              key,
              () -> {
                fail("duplicate query");
                return source;
              });
      first.cancel(false);
      source.complete(Optional.of("данные"));
      assertEquals("данные", second.join().orElseThrow());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void lateReadCannotRepopulateInvalidatedValue() {
    try (var cache = new ProfileCache(2048)) {
      var key = new ProfileCache.Key(UUID.randomUUID(), "test.value");
      var old = new CompletableFuture<Optional<String>>();
      var first = cache.get(key, () -> old);
      cache.invalidate(key);
      assertEquals(
          "new",
          cache
              .get(key, () -> CompletableFuture.completedFuture(Optional.of("new")))
              .join()
              .orElseThrow());
      old.complete(Optional.of("old"));
      assertEquals("old", first.join().orElseThrow());
      assertEquals(
          "new",
          cache
              .get(
                  key,
                  () -> {
                    fail("lost new cached value");
                    return old;
                  })
              .join()
              .orElseThrow());
    }
  }

  @Test
  void boundsPayloadWeightAndInFlightQueries() {
    try (var cache = new ProfileCache(4096)) {
      for (int i = 0; i < 100; i++)
        cache
            .get(
                new ProfileCache.Key(UUID.randomUUID(), "test.value"),
                () -> CompletableFuture.completedFuture(Optional.of("x".repeat(1024))))
            .join();
      assertTrue(cache.weight() <= 4096);
      var requests = new ArrayList<CompletableFuture<Optional<String>>>();
      for (int i = 0; i < 64; i++)
        requests.add(
            cache.get(
                new ProfileCache.Key(UUID.randomUUID(), "test.value"), CompletableFuture::new));
      assertTrue(
          cache
              .get(new ProfileCache.Key(UUID.randomUUID(), "test.value"), CompletableFuture::new)
              .isCompletedExceptionally());
      cache.close();
      assertTrue(requests.stream().allMatch(CompletableFuture::isDone));
    }
  }
}
