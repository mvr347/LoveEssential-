package dev.lovelace.loveessential.command;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lovelace.loveessential.api.NpcChangedEvent;
import dev.lovelace.loveessential.api.NpcDefinition;
import dev.lovelace.loveessential.npc.NpcService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Session selection and short-lived, single-use delete confirmations. Main thread only. */
public final class NpcCommandSelection implements Listener {
  public record Confirmation(String id, long revision, String token) {}

  private final JavaPlugin plugin;
  private final NpcService npcs;
  private final Map<UUID, String> selected = new HashMap<>();
  private final Cache<UUID, Confirmation> pending =
      Caffeine.newBuilder().maximumSize(1024).expireAfterWrite(Duration.ofSeconds(60)).build();

  public NpcCommandSelection(JavaPlugin plugin, NpcService npcs) {
    this.plugin = plugin;
    this.npcs = npcs;
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
  }

  public NpcDefinition target(Player player, String reference) {
    if (reference != null) return exact(npcs.resolve(reference));
    String id = selected.get(player.getUniqueId());
    if (id != null) {
      var existing = npcs.get(id).filter(n -> n.id().equals(id));
      if (existing.isPresent()) return existing.get();
      clear(player);
    }
    throw new IllegalArgumentException("npc.none-selected");
  }

  private NpcDefinition exact(String id) {
    return npcs.get(id).filter(n -> n.id().equals(id))
        .orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
  }

  public void select(Player player, NpcDefinition npc) {
    selected.put(player.getUniqueId(), npc.id());
    pending.invalidate(player.getUniqueId());
  }

  public void clear(Player player) {
    selected.remove(player.getUniqueId());
    pending.invalidate(player.getUniqueId());
  }

  public boolean selected(Player player, String id) {
    return id.equals(selected.get(player.getUniqueId()));
  }

  public Confirmation request(Player player, NpcDefinition npc) {
    var confirmation = new Confirmation(npc.id(), npc.revision(), UUID.randomUUID().toString());
    pending.put(player.getUniqueId(), confirmation);
    return confirmation;
  }

  public Confirmation confirm(Player player, String token) {
    var confirmation = pending(player, token);
    pending.invalidate(player.getUniqueId());
    if (exact(confirmation.id()).revision() != confirmation.revision())
      throw new IllegalArgumentException("errors.conflict");
    return confirmation;
  }

  public void cancel(Player player, String token) {
    pending(player, token);
    pending.invalidate(player.getUniqueId());
  }

  private Confirmation pending(Player player, String token) {
    var confirmation = pending.getIfPresent(player.getUniqueId());
    if (confirmation == null || token != null && !token.equals(confirmation.token()))
      throw new IllegalArgumentException("npc.confirm-expired");
    return confirmation;
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    clear(event.getPlayer());
  }

  @EventHandler
  public void changed(NpcChangedEvent event) {
    if (event.getCurrent().isPresent()) return;
    event.getPrevious().ifPresent(npc -> {
      selected.values().removeIf(npc.id()::equals);
      pending.asMap().values().removeIf(c -> c.id().equals(npc.id()));
    });
  }

  @EventHandler
  public void disabled(PluginDisableEvent event) {
    if (event.getPlugin() == plugin) {
      selected.clear();
      pending.invalidateAll();
    }
  }
}
