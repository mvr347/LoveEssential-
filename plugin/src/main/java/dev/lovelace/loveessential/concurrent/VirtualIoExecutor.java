package dev.lovelace.loveessential.concurrent;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One virtual thread per admitted task, with bounded admission and blocking-I/O concurrency. */
public final class VirtualIoExecutor extends AbstractExecutorService {
  private final ExecutorService threads;
  private final Semaphore admission, running;
  private final Set<Task> tasks = ConcurrentHashMap.newKeySet();
  private final Object gate = new Object();
  private volatile boolean shutdown;

  public VirtualIoExecutor(String name, int concurrency, int waiting) {
    if (concurrency < 1 || waiting < 0) throw new IllegalArgumentException("invalid-io-limits");
    admission = new Semaphore(Math.addExact(concurrency, waiting));
    running = new Semaphore(concurrency, true);
    threads = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name, 0).factory());
  }

  /** Unlike supplyAsync, rejection is reported through the future, not thrown at the caller. */
  public <T> CompletableFuture<T> supply(Supplier<T> work) {
    Objects.requireNonNull(work);
    var result = new CompletableFuture<T>();
    try {
      dispatch(
          () -> {
            if (!result.isDone()) {
              try {
                result.complete(work.get());
              } catch (Throwable failure) {
                result.completeExceptionally(failure);
              }
            }
          },
          result);
    } catch (RejectedExecutionException failure) {
      result.completeExceptionally(failure);
    }
    return result;
  }

  public CompletableFuture<Void> run(Runnable work) {
    Objects.requireNonNull(work);
    return supply(
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public void execute(Runnable work) {
    dispatch(Objects.requireNonNull(work), null);
  }

  private void dispatch(Runnable work, CompletableFuture<?> result) {
    synchronized (gate) {
      if (shutdown || !admission.tryAcquire())
        throw new RejectedExecutionException("io-busy-or-closed");
      var task = new Task(work, result);
      tasks.add(task);
      try {
        threads.execute(task);
      } catch (RuntimeException | Error failure) {
        tasks.remove(task);
        admission.release();
        throw failure;
      }
    }
  }

  private final class Task implements Runnable {
    final Runnable work;
    final CompletableFuture<?> result;
    volatile Thread runner;
    volatile boolean cancelled;
    boolean started;

    Task(Runnable work, CompletableFuture<?> result) {
      this.work = work;
      this.result = result;
      if (result != null)
        result.whenComplete(
            (value, error) -> {
              if (result.isCancelled()) {
                cancelled = true;
                Thread current = runner;
                if (current != null) current.interrupt();
              }
            });
    }

    public void run() {
      boolean acquired = false;
      runner = Thread.currentThread();
      try {
        if (cancelled) return;
        running.acquire();
        acquired = true;
        synchronized (this) {
          if (cancelled) return;
          started = true;
        }
        work.run();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        if (result != null) result.cancel(false);
        else if (work instanceof Future<?> future) future.cancel(false);
      } finally {
        runner = null;
        if (acquired) running.release();
        tasks.remove(this);
        admission.release();
      }
    }

    boolean markCancelled() {
      boolean waiting;
      synchronized (this) {
        cancelled = true;
        waiting = !started;
      }
      return waiting;
    }

    void interruptCancelled() {
      if (result != null) result.cancel(true);
      if (work instanceof Future<?> future) future.cancel(true);
      Thread current = runner;
      if (current != null) current.interrupt();
    }
  }

  @Override
  public void shutdown() {
    synchronized (gate) {
      shutdown = true;
      threads.shutdown();
    }
  }

  @Override
  public List<Runnable> shutdownNow() {
    List<Task> snapshot;
    synchronized (gate) {
      shutdown = true;
      snapshot = List.copyOf(tasks);
    }
    var waiting = new ArrayList<Runnable>();
    // Mark every waiter before interrupting a worker: otherwise releasing its permit can
    // start another queued operation while this loop is still cancelling the snapshot.
    for (Task task : snapshot) if (task.markCancelled()) waiting.add(task.work);
    snapshot.forEach(Task::interruptCancelled);
    threads.shutdownNow();
    return waiting;
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return threads.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return threads.awaitTermination(timeout, unit);
  }

  public int pendingTasks() {
    return tasks.size();
  }

  /** Bounded drain; outstanding owned futures are cancelled when the deadline expires. */
  public void stop(Duration grace) {
    shutdown();
    try {
      if (!awaitTermination(grace.toNanos(), TimeUnit.NANOSECONDS)) shutdownNow();
    } catch (InterruptedException interrupted) {
      shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
