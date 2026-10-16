package dev.lovelace.loveessential;

import java.util.concurrent.*;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainThread {
  private final JavaPlugin plugin;

  public MainThread(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public void execute(Runnable task) {
    if (!plugin.isEnabled()) return;
    if (Bukkit.isPrimaryThread()) task.run();
    else Bukkit.getScheduler().runTask(plugin, task);
  }

  public <T> CompletableFuture<T> call(Supplier<T> task) {
    var f = new CompletableFuture<T>();
    if (!plugin.isEnabled())
      return CompletableFuture.failedFuture(new IllegalStateException("disabled"));
    execute(
        () -> {
          try {
            f.complete(task.get());
          } catch (Throwable e) {
            f.completeExceptionally(e);
          }
        });
    return f;
  }

  public static void require() {
    if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server-thread-required");
  }
}
