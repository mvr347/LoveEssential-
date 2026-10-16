package dev.lovelace.loveessential.api;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * Obtain through Bukkit ServicesManager. All registration and gameplay callbacks run on the server
 * thread.
 */
public interface LoveEssentialService {
  enum Verdict {
    ALLOW,
    DENY,
    UNAVAILABLE
  }

  @FunctionalInterface
  interface ConditionProvider {
    Verdict test(Player player, Map<String, String> arguments);
  }

  @FunctionalInterface
  interface ActionProvider {
    boolean execute(Player player, Map<String, String> arguments);
  }

  @FunctionalInterface
  interface InteractionProvider {
    boolean interact(Player player, NpcDefinition npc);
  }

  interface Registration extends AutoCloseable {
    @Override
    void close();
  }

  Optional<NpcDefinition> npc(String id);

  Collection<NpcDefinition> npcs();

  CompletableFuture<NpcDefinition> save(NpcDefinition npc, long expectedRevision);

  CompletableFuture<Void> delete(String id, long expectedRevision);

  Registration registerCondition(String namespacedId, ConditionProvider provider);

  Registration registerAction(String namespacedId, ActionProvider provider);

  Registration registerInteraction(String namespacedId, int priority, InteractionProvider provider);

  CompletableFuture<Optional<String>> profile(UUID player, String namespacedKey);

  CompletableFuture<Void> profile(UUID player, String namespacedKey, String value);
}
