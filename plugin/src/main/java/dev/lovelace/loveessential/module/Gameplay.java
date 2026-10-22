package dev.lovelace.loveessential.module;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.Placeholders;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Supporting modules share storage and services, and can each be disabled. */
public final class Gameplay implements Listener, AutoCloseable {
  public record Place(Position position, Map<String, String> options, List<String> textKeys) {
    public Place {
      options = Map.copyOf(options);
      textKeys = List.copyOf(textKeys);
    }
  }

  private final JavaPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final Predicate<String> enabled;
  private final TeleportService teleport;
  private final Map<String, SqliteStore.Document<Place>> places = new HashMap<>();
  // PlaceholderAPI expansions can be queried from async chat threads, so these are concurrent.
  private final Set<UUID> vanished = ConcurrentHashMap.newKeySet(),
      loading = ConcurrentHashMap.newKeySet(),
      manualAfk = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Long> active = new ConcurrentHashMap<>();
  private final Map<UUID, net.kyori.adventure.text.Component> vanillaJoin =
      new ConcurrentHashMap<>();
  private final Comfort comfort;
  private final Holograms hologramService;
  private final dev.lovelace.loveessential.portal.Portals portals;

  private BukkitTask task;
  private Placeholders placeholders;
  private long ticks;

  public Gameplay(
      JavaPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      Predicate<String> enabled,
      dev.lovelace.loveessential.condition.ActionEngine actions) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.enabled = enabled;
    teleport = new TeleportService(plugin, main, store, lang, love);
    portals =
        new dev.lovelace.loveessential.portal.Portals(
            (LoveEssentialPlugin) plugin, main, store, lang, love, teleport, actions);
    comfort = new Comfort((LoveEssentialPlugin) plugin, main, store, lang, love, this);
    hologramService = new Holograms((LoveEssentialPlugin) plugin, main, store, lang, love);
  }

  public dev.lovelace.loveessential.portal.Portals portals() {
    return portals;
  }

  public Holograms holograms() {
    return hologramService;
  }

  public void placeholders(Placeholders p) {
    placeholders = p;
  }

  public java.util.concurrent.CompletableFuture<Void> load() {
    return store
        .list("place", Place.class)
        .thenCompose(
            list ->
                main.call(
                    () -> {
                      places.clear();
                      list.forEach(d -> places.put(d.id(), d));
                      return null;
                    }))
        .thenCompose(v -> hologramService.load())
        .thenCompose(v -> portals.load());
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    for (var p : Bukkit.getOnlinePlayers()) loadPlayer(p, false);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    comfort.start();
    portals.start();
    hologramService.start();
  }

  public boolean afk(Player p) {
    return enabled.test("afk")
        && (manualAfk.contains(p.getUniqueId())
            || System.currentTimeMillis()
                    - active.getOrDefault(p.getUniqueId(), System.currentTimeMillis())
                > plugin.getConfig().getLong("afk.timeout-seconds", 300) * 1000);
  }

  public boolean vanished(Player p) {
    return vanished.contains(p.getUniqueId());
  }

  private void loadPlayer(Player p, boolean announce) {
    active.put(p.getUniqueId(), System.currentTimeMillis());
    loading.add(p.getUniqueId());
    applyVanish();
    // Each login owns its own chain of reads, so a quick relog cannot announce twice.
    var session = new Object();
    loadSessions.put(p.getUniqueId(), session);
    readVanish(p, announce, session, 0);
    applySavedSkin(p);
  }

  private static final int LOAD_RETRIES = 3;
  private final Map<UUID, Object> loadSessions = new ConcurrentHashMap<>();

  private void readVanish(Player p, boolean announce, Object session, int attempt) {
    store
        .profile(p.getUniqueId(), "loveessential.vanish")
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      var id = p.getUniqueId();
                      if (!p.isOnline() || loadSessions.get(id) != session) return;
                      if (error != null) {
                        // A failed read (for example storage-busy during a join wave) must never
                        // leave an ordinary player hidden from everyone until the next relog. Staff
                        // who may be vanished stay hidden and keep retrying instead: showing them
                        // by mistake would reveal exactly what vanish is meant to hide.
                        boolean staff = p.hasPermission("loveessential.vanish");
                        if (attempt + 1 < LOAD_RETRIES || staff) {
                          if (attempt + 1 == LOAD_RETRIES) lang.send(p, "errors.storage");
                          Bukkit.getScheduler()
                              .runTaskLater(
                                  plugin,
                                  () -> readVanish(p, announce, session, attempt + 1),
                                  attempt + 1 < LOAD_RETRIES ? 40L : 200L);
                          return;
                        }
                        lang.send(p, "errors.storage");
                      }
                      loadSessions.remove(id);
                      loading.remove(id);
                      if (error == null && Boolean.parseBoolean(v.orElse("false")))
                        vanished.add(id);
                      applyVanish();
                      var vanilla = vanillaJoin.remove(id);
                      if (!announce || vanished(p)) return;
                      var message =
                          ((LoveEssentialPlugin) plugin).allows("joinquit", p)
                              ? lang.render("joinquit.join", Map.of("player", p.getName()))
                              : vanilla;
                      if (message != null)
                        for (var viewer : Bukkit.getOnlinePlayers())
                          if (viewer.canSee(p)) viewer.sendMessage(message);
                    }));
  }

  public void applyVanish() {
    for (var viewer : Bukkit.getOnlinePlayers())
      for (var target : Bukkit.getOnlinePlayers()) {
        if (viewer.equals(target)) continue;
        boolean hide =
            loading.contains(target.getUniqueId())
                || enabled.test("vanish")
                    && vanished(target)
                    && !viewer.hasPermission("loveessential.vanish.see");
        if (hide) viewer.hidePlayer(plugin, target);
        else viewer.showPlayer(plugin, target);
      }
  }

  public List<String> names(Player p, String kind) {
    if (kind.equals("portal"))
      return portals.all().stream().map(SqliteStore.Document::id).sorted().toList();
    if (kind.equals("hologram"))
      return hologramService.all().stream().map(d -> d.value().id()).sorted().toList();
    String prefix = kind.equals("home") ? "home:" + p.getUniqueId() + ":" : kind + ":";
    return places.keySet().stream()
        .filter(k -> k.startsWith(prefix))
        .map(k -> k.substring(prefix.length()))
        .sorted()
        .toList();
  }

  public void command(Player p, String cmd, String[] args) {
    if (Comfort.COMMANDS.contains(cmd)) {
      comfort.command(p, cmd, args);
      return;
    }
    String name = args.length > 0 ? args[0] : "default";
    switch (cmd) {
      case "sethome" -> {
        check("home");
        ((LoveEssentialPlugin) plugin).requireAccess("home", p);
        if (args.length != 0) throw new IllegalArgumentException("home.simple-usage");
        save(p, home(p, "default"), new Place(Position.of(p.getLocation()), Map.of(), List.of()));
      }
      case "home" -> {
        check("home");
        ((LoveEssentialPlugin) plugin).requireAccess("home", p);
        if (args.length != 0) throw new IllegalArgumentException("home.simple-usage");
        var place = places.get(home(p, "default"));
        if (place == null) {
          lang.send(p, "home.none");
          return;
        }
        travel(p, "home", place.value().position());
      }
      case "delhome" -> {
        check("home");
        ((LoveEssentialPlugin) plugin).requireAccess("home", p);
        if (args.length != 0) throw new IllegalArgumentException("home.simple-usage");
        remove(p, home(p, "default"));
      }
      case "setspawn" -> {
        check("spawn");
        admin(p);
        save(p, "spawn:" + name, new Place(Position.of(p.getLocation()), Map.of(), List.of()));
      }
      case "spawn" -> {
        check("spawn");
        ((LoveEssentialPlugin) plugin).requireAccess("spawn", p);
        go(p, "spawn:" + name);
      }
      case "vanish" -> {
        check("vanish");
        ((LoveEssentialPlugin) plugin).requireAccess("vanish", p);
        if (!p.hasPermission("loveessential.vanish"))
          throw new IllegalArgumentException("errors.permission");
        boolean value = !vanished(p);
        store
            .profile(p.getUniqueId(), "loveessential.vanish", Boolean.toString(value))
            .whenComplete(
                (v, error) ->
                    main.execute(
                        () -> {
                          if (error != null) {
                            lang.send(p, "errors.storage");
                            return;
                          }
                          if (value) vanished.add(p.getUniqueId());
                          else vanished.remove(p.getUniqueId());
                          applyVanish();
                          lang.send(p, "vanish.changed", Map.of("state", Boolean.toString(value)));
                        }));
      }
      case "afk" -> {
        check("afk");
        ((LoveEssentialPlugin) plugin).requireAccess("afk", p);
        if (!manualAfk.add(p.getUniqueId())) manualAfk.remove(p.getUniqueId());
        lang.send(p, "afk.changed");
      }
      case "skin" -> {
        check("skin");
        ((LoveEssentialPlugin) plugin).requireAccess("skin", p);
        boolean reset = name.equals("default") || name.equalsIgnoreCase(p.getName());
        String skin = reset ? p.getName() : name;
        if (!skin.matches("[a-zA-Z0-9_]{1,16}")) throw new IllegalArgumentException("errors.input");
        long now = System.currentTimeMillis();
        var last = lastSkin.get(p.getUniqueId());
        if (last != null && now - last < SKIN_COOLDOWN_MS) {
          lang.send(
              p, "skin.cooldown", Map.of("seconds", Long.toString(SKIN_COOLDOWN_MS / 1000)));
          return;
        }
        lastSkin.put(p.getUniqueId(), now);
        var profile = Bukkit.createProfile(skin);
        java.util.concurrent.CompletableFuture.supplyAsync(
                () -> new SkinLookup(profile, profile.complete()),
                ((LoveEssentialPlugin) plugin).io())
            .whenComplete(
                (lookup, failure) ->
                    main.execute(() -> applyLookup(p, skin, reset, lookup, failure)));
      }
      case "profile" ->
          lang.send(
              p,
              "profile.info",
              Map.of(
                  "player",
                  p.getName(),
                  "clan",
                  love.clan(p).orElse(""),
                  "reputation",
                  love.reputation(p).isPresent()
                      ? Integer.toString(love.reputation(p).getAsInt())
                      : "",
                  "authenticated",
                  Boolean.toString(love.authenticated(p))));
      default -> throw new IllegalArgumentException("errors.input");
    }
  }

  public void portal(Player p, String op, String id) {
    portals.command(p, id.isBlank() ? new String[] {op} : new String[] {op, id});
  }

  private void tick() {
    ticks += 20;
    if (enabled.test("nightskip"))
      for (World w : Bukkit.getWorlds())
        if (w.getTime() > 12500 && w.getTime() < 23500) {
          long
              eligible =
                  w.getPlayers().stream()
                      .filter(
                          p ->
                              ((LoveEssentialPlugin) plugin).allows("nightskip", p)
                                  && !vanished(p)
                                  && !afk(p))
                      .count(),
              sleeping =
                  w.getPlayers().stream()
                      .filter(
                          p ->
                              ((LoveEssentialPlugin) plugin).allows("nightskip", p)
                                  && !vanished(p)
                                  && !afk(p)
                                  && p.isSleeping())
                      .count();
          if (sleeping > 0 && eligible > 0) {
            long extra =
                (long)
                    (plugin.getConfig().getDouble("night-skip.max-multiplier", 30)
                        * sleeping
                        / eligible
                        * 20);
            w.setTime(Math.min(23500, w.getTime() + extra));
          }
        }
    if (enabled.test("afk")) {
      long kick = plugin.getConfig().getLong("afk.kick-after-seconds", 0);
      if (kick > 0)
        for (var p : Bukkit.getOnlinePlayers())
          if (((LoveEssentialPlugin) plugin).allows("afk", p)
              && !p.hasPermission("loveessential.afk.bypass")
              && System.currentTimeMillis()
                      - active.getOrDefault(p.getUniqueId(), System.currentTimeMillis())
                  > kick * 1000) p.kick(lang.render("afk.kick"));
    }
  }

  private void save(Player p, String id, Place value) {
    if (!love.territorialAccess(p, value.position().location()))
      throw new IllegalArgumentException("errors.territory");
    long revision =
        Optional.ofNullable(places.get(id)).map(SqliteStore.Document::revision).orElse(0L);
    store
        .save("place", id, revision, value, p.getUniqueId().toString())
        .whenComplete(
            (rev, error) ->
                main.execute(
                    () -> {
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      places.put(id, new SqliteStore.Document<>(id, rev, value));
                      if (id.startsWith("home:")) lang.send(p, "home.point-set");
                      else lang.send(p, "chat.saved");
                    }));
  }

  private void remove(Player p, String id) {
    var old = places.get(id);
    if (old == null) throw new IllegalArgumentException("errors.not-found");
    store
        .delete("place", id, old.revision(), p.getUniqueId().toString())
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      places.remove(id);
                      lang.send(p, "common.done");
                    }));
  }

  private void go(Player p, String id) {
    var place = places.get(id);
    if (place == null) throw new IllegalArgumentException("errors.not-found");
    travel(p, id.startsWith("home:") ? "home" : "spawn", place.value().position());
  }

  /** Home and spawn share one gate: the module's world rules also apply to where you land. */
  private void travel(Player p, String module, Position destination) {
    var world = Bukkit.getWorld(destination.world());
    if (world == null) throw new IllegalArgumentException("worlds.not-loaded");
    ((LoveEssentialPlugin) plugin).requireDestination(module, world.getName());
    teleport.teleport(p, destination);
  }

  private String home(Player p, String name) {
    if (!name.matches("[a-zA-Z0-9_-]{1,32}")) throw new IllegalArgumentException("validation.id");
    return "home:" + p.getUniqueId() + ":" + name.toLowerCase(Locale.ROOT);
  }

  private static final long SKIN_COOLDOWN_MS = 10_000;
  private final Map<UUID, Long> lastSkin = new ConcurrentHashMap<>();

  private record SkinLookup(com.destroystokyo.paper.profile.PlayerProfile profile, boolean ok) {}

  private void applyLookup(
      Player p, String skin, boolean reset, SkinLookup lookup, Throwable failure) {
    if (!p.isOnline()) return;
    // A failed or rate-limited lookup says nothing about the skin; leave everything as it is.
    // The cooldown stays: a failed lookup is still a request to Mojang.
    if (failure != null || lookup == null || !lookup.ok()) {
      lang.send(p, "skin.lookup-failed");
      return;
    }
    // Only the textures move to the player; the fetched profile also carries another account's
    // name and UUID, which must never replace our own.
    var textures =
        lookup.profile().getProperties().stream()
            .filter(property -> property.getName().equals("textures"))
            .findFirst();
    if (textures.isEmpty() && !reset) {
      lang.send(p, "errors.not-found");
      return;
    }
    String value = textures.map(ProfileProperty::getValue).orElse("");
    String signature = textures.map(ProfileProperty::getSignature).orElse("");
    if (signature == null) signature = "";
    String finalSignature = signature;
    // Reset applies the account's own textures but forgets the saved choice.
    String saved = reset ? "" : String.join("\n", skin, value, signature);
    store
        .profile(p.getUniqueId(), SKIN_KEY, saved)
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      if (!p.isOnline()) return;
                      applyTextures(p, value, finalSignature);
                      lang.send(p, "common.done");
                    }));
  }

  /** The saved choice is one record, "name\ntextures\nsignature", so it can never be half-written. */
  private static final String SKIN_KEY = "loveessential.skin";

  /** Swaps only the textures property; the account name and UUID of the profile stay untouched. */
  private static void applyTextures(Player p, String value, String signature) {
    var current = p.getPlayerProfile();
    current.removeProperty("textures");
    if (value != null && !value.isBlank())
      current.setProperty(
          new ProfileProperty(
              "textures", value, signature == null || signature.isBlank() ? null : signature));
    p.setPlayerProfile(current);
  }

  /** A chosen skin is persisted as signed textures, so it survives a relog without a Mojang call. */
  private void applySavedSkin(Player p) {
    if (!enabled.test("skin")) return;
    store
        .profile(p.getUniqueId(), SKIN_KEY)
        .thenAccept(
            saved ->
                main.execute(
                    () -> {
                      if (!p.isOnline()) return;
                      // Older installs stored just the name here; that carries no textures.
                      var parts = saved.orElse("").split("\n", -1);
                      if (parts.length == 3 && !parts[1].isBlank())
                        applyTextures(p, parts[1], parts[2]);
                    }));
  }

  private void check(String module) {
    if (!enabled.test(module)) throw new IllegalArgumentException("errors.module-disabled");
  }

  private void admin(Player p) {
    if (!p.hasPermission("loveessential.admin"))
      throw new IllegalArgumentException("errors.permission");
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    // The vanish state is read asynchronously, so the vanilla message is held back and re-sent
    // by loadPlayer only to viewers who can see the player.
    if (!enabled.test("joinquit") && enabled.test("vanish") && e.joinMessage() != null)
      vanillaJoin.put(e.getPlayer().getUniqueId(), e.joinMessage());
    if (enabled.test("joinquit") || enabled.test("vanish")) e.joinMessage(null);
    loadPlayer(e.getPlayer(), true);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    var p = e.getPlayer();
    if (enabled.test("joinquit"))
      e.quitMessage(
          vanished(p) || !((LoveEssentialPlugin) plugin).allows("joinquit", p)
              ? null
              : lang.render("joinquit.quit", Map.of("player", p.getName())));
    else if (vanished(p)) e.quitMessage(null);
    vanillaJoin.remove(p.getUniqueId());
    loadSessions.remove(p.getUniqueId());
    vanished.remove(p.getUniqueId());
    loading.remove(p.getUniqueId());
    active.remove(p.getUniqueId());
    manualAfk.remove(p.getUniqueId());
    lastSkin.remove(p.getUniqueId());
    teleport.forget(p);
  }

  @EventHandler
  public void move(PlayerMoveEvent e) {
    if (e.getTo() == null
        || e.getFrom().getWorld().equals(e.getTo().getWorld())
            && e.getFrom().distanceSquared(e.getTo()) < .001) return;
    var p = e.getPlayer();
    active.put(p.getUniqueId(), System.currentTimeMillis());
    manualAfk.remove(p.getUniqueId());
  }

  @EventHandler
  public void interact(PlayerInteractEvent e) {
    active.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
  }

  private java.util.function.Supplier<net.kyori.adventure.text.Component> motd;

  public void motd(java.util.function.Supplier<net.kyori.adventure.text.Component> provider) {
    motd = provider;
  }

  @EventHandler
  public void ping(ServerListPingEvent e) {
    if (enabled.test("motd")) e.motd(motd == null ? lang.render("motd.text") : motd.get());
  }

  public void close() {
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    comfort.close();
    hologramService.close();
    portals.close();
    for (var viewer : Bukkit.getOnlinePlayers())
      for (var target : Bukkit.getOnlinePlayers()) viewer.showPlayer(plugin, target);
  }
}
