package dev.lovelace.combat;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.auth.AuthOracle;
import dev.lovelace.lovecore.api.combat.CombatState;
import dev.lovelace.lovecore.api.presence.PresenceOracle;
import dev.lovelace.lovecore.api.revive.DownedOracle;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import nl.marido.deluxecombat.api.DeluxeCombatAPI;
import nl.marido.deluxecombat.events.CombatlogEvent;
import nl.marido.deluxecombat.objects.addons.Addon;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.*;
import org.bukkit.scheduler.BukkitTask;

/** Loaded by DeluxeCombat's addon loader, NOT Bukkit's plugin loader. */
public final class DeluxeCombatAddon extends Addon {
  private final List<BukkitTask> tasks = new ArrayList<>();
  private final List<Listener> listeners = new ArrayList<>();
  private final Set<UUID> pendingClears = ConcurrentHashMap.newKeySet();
  private final java.util.function.Supplier<DeluxeCombatAPI> apiFactory;
  private long generation;

  public DeluxeCombatAddon() { this(DeluxeCombatAPI::new); }
  DeluxeCombatAddon(java.util.function.Supplier<DeluxeCombatAPI> apiFactory) { this.apiFactory = apiFactory; }
  private DeluxeCombatAPI api;
  private Plugin owner;
  private CombatBridge bridge;
  private CombatState upstream;
  private YamlConfiguration config;
  private volatile boolean running;
  private boolean warned;
  private final Map<UUID, UUID> fallAttackers = new HashMap<>();
  private final Set<UUID> timerDeaths = new HashSet<>();
  private Plugin duelPlugin;
  private Object duelMatches;
  private java.lang.reflect.Method duelContains;

  @Override public String getName() { return "Love Integration"; }
  @Override public String getInternalName() { return "loveintegration"; }
  @Override public String getAuthor() { return "mvr347"; }
  @Override public String getVersion() { return "1.0.0"; }
  @Override public String getMaterial() { return "PLAYER_HEAD"; }
  @Override public String getDescription() { return "Единое боевое состояние LoveCore, падение и дуэли."; }
  @Override public List<String> getRequiredPlugins() { return List.of("LoveCore"); }

  @Override public void register() {
    unregister();
    owner = Bukkit.getPluginManager().getPlugin("DeluxeCombat");
    if (owner == null || !owner.isEnabled()) throw new IllegalStateException("DeluxeCombat не включён");
    api = apiFactory.get();
    // Addons can load before LoveCore's onEnable: bind on the following server tick.
    running = true;
    tasks.add(Bukkit.getScheduler().runTask(owner, this::start));
  }

  private void start() {
    if (!running) return;
    try {
      Path file = owner.getDataFolder().toPath().resolve("addons/LoveIntegration.yml");
      config = new YamlConfiguration();
      if (Files.exists(file)) config.load(file.toFile());
      config.addDefault("sync-period-ticks", 1);
      config.addDefault("downed.tag-seconds", 15);
      config.addDefault("downed.tag-attacker", true);
      config.addDefault("duels.exclude-from-deluxecombat", true);
      config.addDefault("duels.prevent-downed", true);
      config.addDefault("protection.auth", true);
      config.addDefault("protection.vanish", true);
      config.addDefault("revive.prefer-reviveme-if-installed", false);
      config.options().copyDefaults(true);
      if (!Files.exists(file)) { Files.createDirectories(file.getParent()); config.save(file.toFile()); }
      upstream = LoveCore.service(CombatState.class).orElseThrow(() -> new IllegalStateException("Служба CombatState LoveCore отсутствует"));
      bridge = new CombatBridge(upstream, Clock.systemUTC(), id -> { if (running) pendingClears.add(id); });
      Bukkit.getServicesManager().register(CombatState.class, bridge, owner, ServicePriority.Highest);
      listen(new Events());
      hookDowned("LoveEssential", "dev.lovelace.loveessential.api.revive.DownedEvent", "getDowned", "getCause");
      if (!Bukkit.getPluginManager().isPluginEnabled("LoveEssential")
          || config.getBoolean("revive.prefer-reviveme-if-installed", false))
        hookDowned("ReviveMe", "net.kokoricraft.reviveme.events.PlayerDownedEvent", "getPlayer", "getEnemy");
      hookEssentialCompletion();
      sync();
      tasks.add(Bukkit.getScheduler().runTaskTimer(owner, this::syncSafely, 1, Math.clamp(config.getInt("sync-period-ticks", 1), 1, 20)));
      owner.getLogger().info("[Love Integration] Аддон подключён: CombatState → DeluxeCombat; интеграции падения зарегистрированы.");
    } catch (Exception | LinkageError failure) {
      owner.getLogger().log(Level.SEVERE, "[Love Integration] Аддон отключён: не удалось подключить API", failure);
      unregister();
    }
  }

  private void listen(Listener listener) {
    listeners.add(listener);
    Bukkit.getPluginManager().registerEvents(listener, owner);
  }

  private void syncSafely() {
    try { sync(); }
    catch (RuntimeException | LinkageError failure) {
      if (!warned) { warned = true; owner.getLogger().log(Level.SEVERE, "[Love Integration] Ошибка API; последнее состояние сохранено до истечения метки", failure); }
    }
  }

  private void sync() {
    if (!running || bridge == null) return;
    for (UUID id : Set.copyOf(pendingClears)) {
      pendingClears.remove(id);
      Player p = Bukkit.getPlayer(id);
      if (p != null) api.untag(p);
    }
    var snapshot = new HashMap<UUID, CombatBridge.Entry>();
    Instant now = Instant.now();
    for (Player p : Bukkit.getOnlinePlayers()) {
      UUID id = p.getUniqueId();
      if (excluded(p)) {
        if (api.isInCombat(p)) api.untag(p);
      } else if (api.isInCombat(p)) {
        long millis = api.getRemainingCombatTimeMillis(p);
        if (millis > 0) snapshot.put(id, new CombatBridge.Entry(now.plusMillis(millis), "deluxecombat"));
      }
      // Preserve siege/raid/custom Love marks, but never adopt stale inferred PvP/metadata.
      String source = upstream.combatSource(id).orElse(null);
      if (CombatBridge.inheritedSource(source)) upstream.combatEndsAt(id).ifPresent(end ->
          snapshot.merge(id, new CombatBridge.Entry(end, source), (a, b) -> a.end().isAfter(b.end()) ? a : b));
    }
    bridge.publish(snapshot);
    warned = false;
  }

  boolean protectedPlayer(Player p) {
    if (config.getBoolean("protection.auth", true)
        && LoveCore.service(AuthOracle.class).map(a -> !a.isAuthenticated(p.getUniqueId())).orElse(false)) return true;
    return config.getBoolean("protection.vanish", true)
        && Bukkit.getServicesManager().getRegistrations(PresenceOracle.class).stream()
            .anyMatch(s -> s.getProvider().vanished(p.getUniqueId()));
  }

  private boolean excluded(Player p) {
    return protectedPlayer(p) || config.getBoolean("duels.exclude-from-deluxecombat", true) && inDuel(p);
  }

  private boolean inDuel(Player p) {
    Plugin duels = Bukkit.getPluginManager().getPlugin("LoveDuels");
    if (duels == null || !duels.isEnabled()) return false;
    try {
      if (duelPlugin != duels) {
        Object manager = duels.getClass().getMethod("getDuelManager").invoke(duels);
        duelMatches = manager.getClass().getMethod("getMatchManager").invoke(manager);
        duelContains = duelMatches.getClass().getMethod("isInMatch", UUID.class);
        duelPlugin = duels;
      }
      return (boolean) duelContains.invoke(duelMatches, p.getUniqueId());
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Несовместимый API LoveDuels", failure);
    }
  }

  @SuppressWarnings("unchecked")
  private void hookDowned(String pluginName, String eventName, String playerMethod, String causeMethod) throws Exception {
    Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
    if (plugin == null || !plugin.isEnabled()) return;
    Class<? extends Event> type = Class.forName(eventName, true, plugin.getClass().getClassLoader()).asSubclass(Event.class);
    var getPlayer = type.getMethod(playerMethod);
    var getCause = type.getMethod(causeMethod);
    Listener listener = new Listener() {};
    listeners.add(listener);
    Bukkit.getPluginManager().registerEvent(type, listener, EventPriority.HIGHEST, (unused, event) -> {
      try {
        Player p = (Player) getPlayer.invoke(event);
        if (p == null) return;
        if (event instanceof Cancellable c && !c.isCancelled()
            && (config.getBoolean("duels.prevent-downed", true) && inDuel(p)
                || pluginName.equals("LoveEssential") && config.getBoolean("revive.prefer-reviveme-if-installed", false)
                    && Bukkit.getPluginManager().isPluginEnabled("ReviveMe"))) c.setCancelled(true);
      } catch (ReflectiveOperationException failure) { throw new EventException(failure); }
    }, owner, true);
    Bukkit.getPluginManager().registerEvent(type, listener, EventPriority.MONITOR, (unused, event) -> {
      try {
        if (event instanceof Cancellable c && c.isCancelled()) return;
        Player p = (Player) getPlayer.invoke(event);
        Object cause = getCause.invoke(event);
        Player attacker = cause instanceof Player a ? a : cause instanceof EntityDamageByEntityEvent damage ? attacker(damage) : null;
        // The downed event precedes installation of its state. Validate it after all listeners.
        long expected = generation;
        Bukkit.getScheduler().runTask(owner, () -> {
          if (generation != expected || !running || p == null || !p.isOnline() || !downed(p, pluginName) || excluded(p)) return;
          if (attacker != null && !attacker.equals(p)) fallAttackers.put(p.getUniqueId(), attacker.getUniqueId());
          int seconds = Math.clamp(config.getInt("downed.tag-seconds", 15), 0, 3600);
          if (seconds == 0 || attacker == null || !attacker.isOnline() || excluded(attacker) || attacker.equals(p)) return;
          if (api.hasProtection(p) || api.hasProtection(attacker)
              || !api.hasPvPEnabled(p) || !api.hasPvPEnabled(attacker)) return;
          tagAtLeast(p, attacker, seconds);
          if (config.getBoolean("downed.tag-attacker", true)) tagAtLeast(attacker, p, seconds);
          syncSafely();
        });
      } catch (ReflectiveOperationException failure) { throw new EventException(failure); }
    }, owner, false);
  }

  private void hookEssentialCompletion() throws Exception {
    Plugin plugin = Bukkit.getPluginManager().getPlugin("LoveEssential");
    if (plugin == null || !plugin.isEnabled()) return;
    completionEvent(plugin, "ReviveEvent", event -> {
      Player p = (Player) event.getClass().getMethod("getDowned").invoke(event);
      fallAttackers.remove(p.getUniqueId()); timerDeaths.remove(p.getUniqueId());
    });
    completionEvent(plugin, "DownedDeathEvent", event -> {
      Player p = (Player) event.getClass().getMethod("getDowned").invoke(event);
      String cause = event.getClass().getMethod("getCause").invoke(event).toString();
      if (cause.equals("TIMER")) timerDeaths.add(p.getUniqueId()); else timerDeaths.remove(p.getUniqueId());
    });
  }

  @FunctionalInterface private interface Completion { void accept(Event event) throws ReflectiveOperationException; }
  private void completionEvent(Plugin plugin, String name, Completion action) throws Exception {
    Class<? extends Event> type = Class.forName("dev.lovelace.loveessential.api.revive." + name, true,
        plugin.getClass().getClassLoader()).asSubclass(Event.class);
    Listener listener = new Listener() {};
    listeners.add(listener);
    Bukkit.getPluginManager().registerEvent(type, listener, EventPriority.MONITOR, (unused, event) -> {
      try { action.accept(event); } catch (ReflectiveOperationException failure) { throw new EventException(failure); }
    }, owner, true);
  }

  private void tagAtLeast(Player p, Player attacker, int seconds) {
    long remaining = Math.max(0, api.getRemainingCombatTimeMillis(p));
    int duration = (int) Math.min(Integer.MAX_VALUE, Math.max(seconds, (remaining + 999) / 1000));
    api.tag(p, attacker, duration);
  }

  private boolean downed(Player p, String provider) {
    if (provider.equals("LoveEssential")) return LoveCore.provider(DownedOracle.class, "loveessential")
        .map(o -> o.downed(p.getUniqueId())).orElse(false);
    Plugin plugin = Bukkit.getPluginManager().getPlugin("ReviveMe");
    if (plugin == null || !plugin.isEnabled()) return false;
    try {
      Class<?> apiClass = Class.forName("net.kokoricraft.reviveme.api.ReviveMeAPI", true, plugin.getClass().getClassLoader());
      return Boolean.TRUE.equals(apiClass.getMethod("hasDowned", Player.class).invoke(null, p));
    } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Несовместимый API ReviveMe", failure); }
  }

  private static Player attacker(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p) return p;
    return e.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player p ? p : null;
  }

  private final class Events implements Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void protection(EntityDamageByEntityEvent e) {
      Player a = attacker(e);
      if (e.getEntity() instanceof Player p && (protectedPlayer(p) || a != null && protectedPlayer(a))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void logout(CombatlogEvent e) {
      // A stale DC tag must not punish authentication/vanish or an isolated duel.
      // Ordinary and downed combat logouts remain DeluxeCombat's responsibility.
      if (excluded(e.getCombatlogger())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) {
      UUID id = e.getPlayer().getUniqueId();
      bridge.forget(id); fallAttackers.remove(id); timerDeaths.remove(id);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void attribution(PlayerDeathEvent e) {
      // Hunt and other normal-priority death listeners can see the original online attacker.
      // Only confirmed timer deaths: never manufacture a bounty for suicide/command/logout.
      UUID id = e.getEntity().getUniqueId();
      if (timerDeaths.remove(id) && e.getEntity().getKiller() == null) {
        UUID attacker = fallAttackers.get(id);
        Player p = attacker == null ? null : Bukkit.getPlayer(attacker);
        if (p != null && p.isOnline()) e.getEntity().setKiller(p);
      }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) {
      UUID id = e.getEntity().getUniqueId();
      bridge.forget(id); fallAttackers.remove(id); timerDeaths.remove(id);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void disable(org.bukkit.event.server.PluginDisableEvent e) {
      if (e.getPlugin().getName().equals("LoveCore") || e.getPlugin() == owner) unregister();
    }
  }

  @Override public void unregister() {
    running = false;
    generation++;
    tasks.forEach(BukkitTask::cancel);
    tasks.clear();
    listeners.forEach(HandlerList::unregisterAll);
    listeners.clear();
    if (bridge != null) { Bukkit.getServicesManager().unregister(CombatState.class, bridge); bridge.close(); bridge = null; }
    pendingClears.clear();
    fallAttackers.clear(); timerDeaths.clear();
    duelPlugin = null; duelMatches = null; duelContains = null;
  }
}
