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
    loadPlayer(p, announce, 0);
    applySavedSkin(p);
  }

  private static final int LOAD_RETRIES = 3;

  private void loadPlayer(Player p, boolean announce, int attempt) {
    store
        .profile(p.getUniqueId(), "loveessential.vanish")
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      if (!p.isOnline()) return;
                      if (error != null) {
                        // A failed read (for example storage-busy during a join wave) must never
                        // leave the player hidden from everyone until the next relog.
                        if (attempt + 1 < LOAD_RETRIES) {
                          Bukkit.getScheduler()
                              .runTaskLater(plugin, () -> loadPlayer(p, announce, attempt + 1), 40L);
                          return;
                        }
                        lang.send(p, "errors.storage");
                      }
                      loading.remove(p.getUniqueId());
                      if (error == null && Boolean.parseBoolean(v.orElse("false")))
                        vanished.add(p.getUniqueId());
                      applyVanish();
                      var vanilla = vanillaJoin.remove(p.getUniqueId());
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
        teleport.teleport(p, place.value().position());
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
        var profile = Bukkit.createProfile(skin);
        java.util.concurrent.CompletableFuture.supplyAsync(
                () -> {
                  profile.complete();
                  return profile;
                },
                ((LoveEssentialPlugin) plugin).io())
            .thenAccept(
                fetched ->
                    main.execute(
                        () -> {
                          if (!p.isOnline()) return;
                          // Only the textures move to the player; the fetched profile also carries
                          // another account's name and UUID, which must never replace our own.
                          var textures =
                              fetched.getProperties().stream()
                                  .filter(property -> property.getName().equals("textures"))
                                  .findFirst();
                          if (textures.isEmpty() && !reset) {
                            lang.send(p, "errors.not-found");
                            return;
                          }
                          String value = textures.map(ProfileProperty::getValue).orElse("");
                          String signature =
                              textures.map(ProfileProperty::getSignature).orElse("");
                          // Reset applies the account's own textures but forgets the saved choice.
                          String savedName = reset ? "" : skin;
                          String savedValue = reset ? "" : value;
                          String savedSignature = reset || signature == null ? "" : signature;
                          saveSkin(p, savedName, savedValue, savedSignature)
                              .whenComplete(
                                  (v, error) ->
                                      main.execute(
                                          () -> {
                                            if (error != null) {
                                              lang.send(p, "errors.storage");
                                              return;
                                            }
                                            if (!p.isOnline()) return;
                                            applyTextures(p, value, signature);
                                            lang.send(p, "common.done");
                                          }));
                        }));
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
    var destination = place.value().position();
    ((LoveEssentialPlugin) plugin)
        .requireDestination(
            id.startsWith("home:") ? "home" : "spawn", destination.location().getWorld().getName());
    teleport.teleport(p, destination);
  }

  private String home(Player p, String name) {
    if (!name.matches("[a-zA-Z0-9_-]{1,32}")) throw new IllegalArgumentException("validation.id");
    return "home:" + p.getUniqueId() + ":" + name.toLowerCase(Locale.ROOT);
  }

  private java.util.concurrent.CompletableFuture<Void> saveSkin(
      Player p, String name, String value, String signature) {
    var id = p.getUniqueId();
    return store
        .profile(id, "loveessential.skin", name)
        .thenCompose(v -> store.profile(id, "loveessential.skin.value", value))
        .thenCompose(v -> store.profile(id, "loveessential.skin.signature", signature));
  }

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
        .profile(p.getUniqueId(), "loveessential.skin.value")
        .thenCompose(
            value ->
                store
                    .profile(p.getUniqueId(), "loveessential.skin.signature")
                    .thenApply(signature -> new String[] {value.orElse(""), signature.orElse("")}))
        .thenAccept(
            saved ->
                main.execute(
                    () -> {
                      if (p.isOnline() && !saved[0].isBlank())
                        applyTextures(p, saved[0], saved[1]);
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
    vanished.remove(p.getUniqueId());
    loading.remove(p.getUniqueId());
    active.remove(p.getUniqueId());
    manualAfk.remove(p.getUniqueId());
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
