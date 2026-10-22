package dev.lovelace.loveessential.module;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

/**
 * Everything a vanished player stops causing: mob aggro, item pickup, noise, vibrations, trampling
 * and every public list. The player keeps their own game mode; only what others notice is removed.
 * Which parts apply is chosen in {@code vanish.yml}.
 */
public final class Vanish implements Listener, AutoCloseable {
  private final LoveEssentialPlugin plugin;
  private final Gameplay gameplay;
  private final Lang lang;
  private final Map<UUID, Boolean> flightBefore = new ConcurrentHashMap<>();
  private final Set<UUID> flightOff = ConcurrentHashMap.newKeySet();
  private BukkitTask indicator;

  public Vanish(LoveEssentialPlugin plugin, Gameplay gameplay, Lang lang) {
    this.plugin = plugin;
    this.gameplay = gameplay;
    this.lang = lang;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    indicator = Bukkit.getScheduler().runTaskTimer(plugin, this::remind, 40, 40);
  }

  private boolean option(String key, boolean fallback) {
    return plugin.getConfig().getBoolean("vanish." + key, fallback);
  }

  private boolean hidden(Player p) {
    return plugin.module("vanish") && gameplay.vanished(p);
  }

  /** Whether the player may fly while hidden (the menu toggles it for the session). */
  public boolean flight(Player p) {
    return option("fly", true) && !flightOff.contains(p.getUniqueId());
  }

  public void flight(Player p, boolean enabled) {
    if (enabled) flightOff.remove(p.getUniqueId());
    else flightOff.add(p.getUniqueId());
    if (hidden(p)) applyFlight(p, true);
  }

  /** Sets or removes every side effect of being hidden. Idempotent. */
  public void apply(Player p, boolean on) {
    if (on) {
      if (option("silent", true)) p.setSilent(true);
      p.setCollidable(false);
      p.setAffectsSpawning(false);
      p.setSleepingIgnored(true);
      p.setMetadata("vanished", new FixedMetadataValue(plugin, true));
    } else {
      p.setSilent(false);
      p.setCollidable(true);
      p.setAffectsSpawning(true);
      p.setSleepingIgnored(false);
      p.removeMetadata("vanished", plugin);
    }
    applyFlight(p, on);
  }

  private void applyFlight(Player p, boolean on) {
    var id = p.getUniqueId();
    boolean special = p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
    if (on && flight(p)) {
      flightBefore.putIfAbsent(id, p.getAllowFlight());
      p.setAllowFlight(true);
    } else {
      var before = flightBefore.remove(id);
      if (before != null && !before && !special) {
        p.setFlying(false);
        p.setAllowFlight(false);
      }
    }
  }

  private void remind() {
    if (!option("indicator", true)) return;
    for (var p : Bukkit.getOnlinePlayers()) if (hidden(p)) p.sendActionBar(lang.render("vanish.indicator"));
  }

  /** Announces a fake leave or join to everybody who cannot see the player (optional). */
  public void announce(Player p, boolean nowHidden) {
    if (!option("fake-messages", false)) return;
    var message =
        lang.render(nowHidden ? "joinquit.quit" : "joinquit.join", Map.of("player", p.getName()));
    for (var viewer : Bukkit.getOnlinePlayers())
      if (!viewer.equals(p) && !viewer.hasPermission("loveessential.vanish.see"))
        viewer.sendMessage(message);
  }

  @EventHandler(ignoreCancelled = true)
  public void target(EntityTargetLivingEntityEvent e) {
    if (e.getTarget() instanceof Player p && hidden(p) && !option("mob-targeting", false))
      e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void pickup(EntityPickupItemEvent e) {
    if (e.getEntity() instanceof Player p && hidden(p) && !option("pickup", false))
      e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void arrow(PlayerPickupArrowEvent e) {
    if (hidden(e.getPlayer()) && !option("pickup", false)) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void experience(PlayerPickupExperienceEvent e) {
    if (hidden(e.getPlayer()) && !option("pickup", false)) e.setCancelled(true);
  }

  /** Pressure plates, tripwires, trampled farmland and turtle eggs react to a hidden player. */
  @EventHandler(ignoreCancelled = false, priority = EventPriority.LOW)
  public void physical(PlayerInteractEvent e) {
    if (e.getAction() == Action.PHYSICAL
        && hidden(e.getPlayer())
        && !option("physical", false)) e.setCancelled(true);
  }

  /** Sculk sensors and wardens listen to these vibrations. */
  @EventHandler(ignoreCancelled = true)
  public void vibration(GenericGameEvent e) {
    if (e.getEntity() instanceof Player p && hidden(p) && !option("vibrations", false))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void death(PlayerDeathEvent e) {
    if (hidden(e.getPlayer()) && option("hide-messages", true)) e.deathMessage(null);
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void advancement(PlayerAdvancementDoneEvent e) {
    if (hidden(e.getPlayer()) && option("hide-messages", true)) e.message(null);
  }

  /** The server list shows neither the count nor the names of hidden players. */
  @EventHandler
  public void listPing(PaperServerListPingEvent e) {
    if (!plugin.module("vanish") || !option("hide-from-list", true)) return;
    var hiddenIds = new HashSet<UUID>();
    for (var p : Bukkit.getOnlinePlayers()) if (gameplay.vanished(p)) hiddenIds.add(p.getUniqueId());
    if (hiddenIds.isEmpty()) return;
    e.getListedPlayers().removeIf(info -> hiddenIds.contains(info.id()));
    e.setNumPlayers(Math.max(0, e.getNumPlayers() - hiddenIds.size()));
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    flightBefore.remove(e.getPlayer().getUniqueId());
    flightOff.remove(e.getPlayer().getUniqueId());
  }

  @Override
  public void close() {
    if (indicator != null) indicator.cancel();
    HandlerList.unregisterAll(this);
    for (var p : Bukkit.getOnlinePlayers()) if (gameplay.vanished(p)) apply(p, false);
  }
}
