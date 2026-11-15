package dev.lovelace.loveessential.world;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.config.ModuleConfigs;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;

/** Entry closure works for vanilla dimensions too, without importing or deleting their worlds. */
public final class WorldEntry implements Listener, AutoCloseable {
  private final LoveEssentialPlugin plugin;
  private final Lang lang;
  private final BiPredicate<Player, World> permission;
  private final Set<UUID> relocating = new HashSet<>();
  private final Predicate<World> publicAccess;
  private record SpawnSnapshot(boolean active, Set<World> blocked, List<Location> fallbacks) {}
  private volatile SpawnSnapshot spawns = new SpawnSnapshot(false, Set.of(), List.of());

  public WorldEntry(LoveEssentialPlugin plugin, Lang lang, BiPredicate<Player, World> permission,
      Predicate<World> publicAccess) {
    this.plugin = plugin;
    this.lang = lang;
    this.permission = permission;
    this.publicAccess = publicAccess;
    Bukkit.getPluginManager().registerEvents(this, plugin);
    refreshSnapshot();
  }

  private WorldAccessPolicy policy() { return WorldAccessPolicy.read(plugin.getConfig()); }
  public boolean allowed(String name, String environment) {
    return !plugin.module("worlds") || policy().allows(name, environment);
  }
  public boolean allowed(World world) { return allowed(world.getName(), world.getEnvironment().name()); }
  public void require(String name, String environment) {
    if (!allowed(name, environment)) throw new IllegalArgumentException("worlds.disabled");
  }

  /** Called on the main thread before accepting a config; never leave every escape world closed. */
  public static void validateAvailable(org.bukkit.configuration.ConfigurationSection config) {
    var policy = WorldAccessPolicy.read(config);
    if (policy.fallbackWorld().isEmpty() && policy.disabledWorlds().isEmpty() && policy.nether() && policy.end()) return;
    List<World> worlds = Bukkit.getWorlds();
    if (worlds.isEmpty()) return; // No worlds yet during early startup.
    if (!policy.fallbackWorld().isEmpty()) {
      World fallback = Bukkit.getWorld(policy.fallbackWorld());
      if (fallback == null || !policy.allows(fallback.getName(), fallback.getEnvironment().name()))
        throw new IllegalArgumentException("worlds.invalid-fallback");
    }
    if (worlds.stream().noneMatch(w -> policy.allows(w.getName(), w.getEnvironment().name())))
      throw new IllegalArgumentException("worlds.invalid-fallback");
  }

  private World fallback(Player player) {
    var policy = policy();
    World selected = policy.fallbackWorld().isEmpty() ? null : Bukkit.getWorld(policy.fallbackWorld());
    if (selected != null && allowed(selected) && permission.test(player, selected)) return selected;
    return Bukkit.getWorlds().stream().filter(this::allowed).filter(w -> permission.test(player, w))
        .sorted(Comparator.comparingInt(w -> w.getEnvironment() == World.Environment.NORMAL ? 0 : 1))
        .findFirst().orElse(null);
  }

  public void refreshSnapshot() {
    var policy = policy();
    var worlds = Bukkit.getWorlds();
    Set<World> blocked = new HashSet<>();
    for (World world : worlds)
      if (!policy.allows(world.getName(), world.getEnvironment().name())) blocked.add(world);
    var locations = worlds.stream().filter(w -> !blocked.contains(w)).filter(publicAccess)
        .sorted(Comparator.comparingInt((World w) -> w.getName().equals(policy.fallbackWorld()) ? -1
            : w.getEnvironment() == World.Environment.NORMAL ? 0 : 1))
        .map(w -> w.getSpawnLocation().clone()).toList();
    spawns = new SpawnSnapshot(plugin.module("worlds"), Set.copyOf(blocked), locations);
  }

  public void refresh() {
    refreshSnapshot();
    if (!plugin.module("worlds")) return;
    validateAvailable(plugin.getConfig());
    for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) evacuate(player);
  }

  private void evacuate(Player player) {
    if (!plugin.module("worlds") || !player.isOnline() || allowed(player.getWorld())
        || !relocating.add(player.getUniqueId())) return;
    try {
      World fallback = fallback(player);
      if (fallback == null || !player.teleport(fallback.getSpawnLocation())) {
        player.kick(lang.render("worlds.relocation-failed"));
        return;
      }
      lang.send(player, "worlds.relocated", Map.of("world", fallback.getName()));
    } finally { relocating.remove(player.getUniqueId()); }
  }

  private boolean portalAllowed(PlayerTeleportEvent e) {
    var p = policy();
    return switch (e.getCause()) {
      case NETHER_PORTAL -> p.netherPortals()
          && (e.getTo() != null || e.getFrom().getWorld().getEnvironment() == World.Environment.NETHER || p.nether());
      case END_PORTAL, END_GATEWAY -> p.endPortals()
          && (e.getTo() != null || e.getFrom().getWorld().getEnvironment() == World.Environment.THE_END || p.end());
      default -> true;
    };
  }

  private void check(PlayerTeleportEvent e) {
    if (!plugin.module("worlds")) return;
    if (e.getTo() != null && !allowed(e.getTo().getWorld()) || !portalAllowed(e)) {
      e.setCancelled(true);
      lang.send(e.getPlayer(), "worlds.disabled");
    }
  }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent e) { check(e); }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void portal(PlayerPortalEvent e) { check(e); }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityTeleport(EntityTeleportEvent e) {
    if (plugin.module("worlds") && e.getTo() != null && !allowed(e.getTo().getWorld())) e.setCancelled(true);
  }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityPortal(EntityPortalEvent e) {
    entityTeleport(e);
    if (!plugin.module("worlds") || e.getTo() == null) return;
    var p = policy();
    boolean end = e.getFrom().getWorld().getEnvironment() == World.Environment.THE_END
        || e.getTo().getWorld().getEnvironment() == World.Environment.THE_END;
    if (end ? !p.endPortals() : !p.netherPortals()) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void initialSpawn(AsyncPlayerSpawnLocationEvent e) {
    // Connection configuration runs off-thread and has no Player yet. Use only an immutable
    // main-thread snapshot, and return a clone so event consumers cannot mutate the cache.
    var snapshot = spawns;
    if (!snapshot.active() || !snapshot.blocked().contains(e.getSpawnLocation().getWorld())) return;
    if (!snapshot.fallbacks().isEmpty()) e.setSpawnLocation(snapshot.fallbacks().getFirst().clone());
  }
  @EventHandler(priority = EventPriority.MONITOR)
  public void loaded(WorldLoadEvent e) { refreshSnapshot(); }
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void unloaded(WorldUnloadEvent e) {
    Bukkit.getScheduler().runTask(plugin, this::refreshSnapshot);
  }
  @EventHandler(priority = EventPriority.HIGHEST)
  public void respawn(PlayerRespawnEvent e) {
    if (!plugin.module("worlds") || allowed(e.getRespawnLocation().getWorld())) return;
    World fallback = fallback(e.getPlayer());
    if (fallback != null) e.setRespawnLocation(fallback.getSpawnLocation());
  }
  @EventHandler(priority = EventPriority.MONITOR)
  public void join(PlayerJoinEvent e) {
    Bukkit.getScheduler().runTask(plugin, () -> evacuate(e.getPlayer()));
  }
  @EventHandler(priority = EventPriority.MONITOR)
  public void changed(PlayerChangedWorldEvent e) {
    if (plugin.module("worlds") && !allowed(e.getPlayer().getWorld()))
      Bukkit.getScheduler().runTask(plugin, () -> evacuate(e.getPlayer()));
  }

  private boolean admin(CommandSender sender) {
    return plugin.module("worlds") && sender.hasPermission("loveessential.worlds.admin");
  }
  private static String dimension(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "nether", "незер", "ад" -> "nether";
      case "end", "the_end", "энд", "енд" -> "end";
      default -> null;
    };
  }
  private void completed(CommandSender sender, CompletableFuture<Void> future, Runnable after) {
    future.whenComplete((v,error) -> {
      if (!plugin.isEnabled()) return;
      Bukkit.getScheduler().runTask(plugin, () -> {
      if (sender instanceof Player p && !p.isOnline()) return;
      if (error != null) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        lang.send(sender, cause instanceof IllegalArgumentException ? cause.getMessage() : "errors.config");
      } else { lang.send(sender, "chat.saved"); after.run(); }
      });
    });
  }

  public boolean control(CommandSender sender, String[] args, List<String> names) {
    if (args.length == 0 || !Set.of("enable","disable","portals","status").contains(args[0].toLowerCase(Locale.ROOT))) return false;
    if (!admin(sender)) throw new IllegalArgumentException("errors.permission");
    String action = args[0].toLowerCase(Locale.ROOT);
    if (action.equals("status")) {
      if (args.length != 1) throw new IllegalArgumentException("worlds.usage");
      var p = policy();
      lang.send(sender, "worlds.access-status", Map.of("nether", ""+p.nether(), "end", ""+p.end(),
          "nether_portals", ""+p.netherPortals(), "end_portals", ""+p.endPortals(),
          "disabled", String.join(", ",p.disabledWorlds()), "fallback",p.fallbackWorld().isEmpty()?"auto":p.fallbackWorld()));
      return true;
    }
    String path; Object value;
    if (action.equals("portals")) {
      if (args.length != 3 || dimension(args[1]) == null
          || !Set.of("on","off","true","false").contains(args[2].toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("worlds.usage");
      path = "entry."+dimension(args[1])+".portals";
      value = Set.of("on","true").contains(args[2].toLowerCase(Locale.ROOT));
    } else {
      if (args.length != 2) throw new IllegalArgumentException("worlds.usage");
      String dimension = dimension(args[1]);
      if (dimension != null) { path="entry."+dimension+".enabled"; value=action.equals("enable"); }
      else {
        Worlds.name(args[1]);
        if (names.stream().noneMatch(n -> n.equalsIgnoreCase(args[1]))) throw new IllegalArgumentException("errors.not-found");
        var disabled = new ArrayList<>(policy().disabledWorlds());
        disabled.removeIf(n -> n.equalsIgnoreCase(args[1]));
        if (action.equals("disable")) disabled.add(args[1].toLowerCase(Locale.ROOT));
        path="entry.disabled-worlds"; value=disabled;
      }
    }
    Object expected = ModuleConfigs.snapshot(plugin.getConfig().get("worlds."+path));
    completed(sender, plugin.editSetting("worlds",path,expected,value), () -> {});
    return true;
  }

  public void settings(Player player, NativeDialogs ui, Runnable back) {
    if (!admin(player)) throw new IllegalArgumentException("errors.permission");
    var p=policy(); Object expected=ModuleConfigs.snapshot(plugin.getConfig().get("worlds.entry"));
    ui.show(player,"worlds.access-title","worlds.access-help",List.of(
        ui.select("nether","worlds.nether", ""+p.nether(),List.of("true","false")),
        ui.select("end","worlds.end", ""+p.end(),List.of("true","false")),
        ui.select("nether_portals","worlds.nether-portals", ""+p.netherPortals(),List.of("true","false")),
        ui.select("end_portals","worlds.end-portals", ""+p.endPortals(),List.of("true","false")),
        ui.text("fallback","worlds.fallback",p.fallbackWorld(),48),
        ui.text("disabled","worlds.disabled-worlds",String.join(", ",p.disabledWorlds()),1024)),List.of(
          ui.button("save","content.save",v -> {
            var names=Arrays.stream(v.get("disabled").split(",")).map(String::strip).filter(n -> !n.isEmpty()).toList();
            var next=Map.<String,Object>of("nether",Map.of("enabled",Boolean.parseBoolean(v.get("nether")),"portals",Boolean.parseBoolean(v.get("nether_portals"))),
                "end",Map.of("enabled",Boolean.parseBoolean(v.get("end")),"portals",Boolean.parseBoolean(v.get("end_portals"))),
                "fallback-world",v.get("fallback").strip(),"disabled-worlds",names);
            completed(player,plugin.editSetting("worlds","entry",expected,next),() -> settings(player,ui,back));
          }),ui.button("back","common.back",v -> back.run())),this::admin);
  }

  public List<String> suggestions(CommandSender sender, String[] args, List<String> names) {
    if (!admin(sender)) return List.of();
    if (args.length==2 && Set.of("enable","disable").contains(args[0]))
      return java.util.stream.Stream.concat(java.util.stream.Stream.of("nether","end"),names.stream()).distinct().toList();
    if (args.length==2 && args[0].equals("portals")) return List.of("nether","end");
    if (args.length==3 && args[0].equals("portals")) return List.of("on","off");
    return List.of();
  }
  @Override public void close() {
    HandlerList.unregisterAll(this);
    relocating.clear();
    spawns = new SpawnSnapshot(false, Set.of(), List.of());
  }
}
