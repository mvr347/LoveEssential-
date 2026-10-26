package dev.lovelace.loveessential.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class VirtualIoExecutorTest {
  private static void waitOn(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void eachTaskUsesItsOwnVirtualThread() throws Exception {
    var io = new VirtualIoExecutor("test-io-", 2, 2);
    try {
      var first = io.supply(Thread::currentThread).get(2, TimeUnit.SECONDS);
      var second = io.supply(Thread::currentThread).get(2, TimeUnit.SECONDS);
      assertTrue(first.isVirtual());
      assertTrue(second.isVirtual());
      assertNotSame(first, second);
      assertTrue(first.getName().startsWith("test-io-"));
    } finally {
      io.stop(Duration.ofSeconds(1));
    }
  }

  @Test
  void boundedAdmissionRejectsWithoutBlockingTheSubmittingThread() throws Exception {
    var io = new VirtualIoExecutor("test-limit-", 1, 1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      var first =
          io.run(
              () -> {
                entered.countDown();
                waitOn(release);
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var secondStarted = new AtomicBoolean();
      var second = io.run(() -> secondStarted.set(true));
      var overflow = io.supply(() -> "overflow");
      assertTrue(overflow.isCompletedExceptionally());
      assertInstanceOf(
          RejectedExecutionException.class,
          assertThrows(ExecutionException.class, () -> overflow.get()).getCause());
      assertFalse(secondStarted.get());
      release.countDown();
      first.get(2, TimeUnit.SECONDS);
      second.get(2, TimeUnit.SECONDS);
      assertTrue(secondStarted.get());
    } finally {
      release.countDown();
      io.stop(Duration.ofSeconds(1));
    }
  }

  @Test
  void forcedStopCompletesRunningAndWaitingFuturesAndDoesNotRunWaitingWork() throws Exception {
    for (int attempt = 0; attempt < 20; attempt++) {
      var io = new VirtualIoExecutor("test-stop-", 1, 2);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var first =
          io.run(
              () -> {
                entered.countDown();
                waitOn(release);
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var invoked = new AtomicBoolean();
      var second = io.run(() -> invoked.set(true));
      Future<?> legacy = io.submit(() -> invoked.set(true));
      io.shutdownNow();
      assertTrue(first.isDone());
      assertTrue(second.isCancelled());
      assertTrue(legacy.isCancelled());
      assertTrue(io.awaitTermination(2, TimeUnit.SECONDS));
      assertEquals(0, io.pendingTasks());
      assertFalse(invoked.get());
      assertTrue(io.supply(() -> 1).isCompletedExceptionally());
    }
  }

  @Test
  void gracefulShutdownDrainsAcceptedWork() throws Exception {
    var io = new VirtualIoExecutor("test-drain-", 1, 1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var first =
        io.run(
            () -> {
              entered.countDown();
              waitOn(release);
            });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    var second = io.supply(() -> 42);
    io.shutdown();
    release.countDown();
    assertTrue(io.awaitTermination(2, TimeUnit.SECONDS));
    first.get();
    assertEquals(42, second.get());
    assertEquals(0, io.pendingTasks());
  }

  @Test
  void callerCancellationInterruptsAnOwnedTaskAndReleasesItsSlot() throws Exception {
    var io = new VirtualIoExecutor("test-cancel-", 1, 1);
    var entered = new CountDownLatch(1);
    try {
      var first =
          io.run(
              () -> {
                entered.countDown();
                waitOn(new CountDownLatch(1));
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var second = io.supply(() -> 7);
      assertTrue(first.cancel(true));
      assertEquals(7, second.get(2, TimeUnit.SECONDS));
    } finally {
      io.stop(Duration.ofSeconds(1));
    }
  }

  @Test
  void boundedStopCancelsWorkInsteadOfWaitingForever() throws Exception {
    var io = new VirtualIoExecutor("test-deadline-", 1, 1);
    var entered = new CountDownLatch(1);
    var first =
        io.run(
            () -> {
              entered.countDown();
              waitOn(new CountDownLatch(1));
            });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    var second = io.supply(() -> 1);
    io.stop(Duration.ZERO);
    assertTrue(first.isDone());
    assertTrue(second.isCancelled());
    assertTrue(io.awaitTermination(2, TimeUnit.SECONDS));
  }
}
