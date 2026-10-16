package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.Placeholders;
import java.util.*;
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

  private record Request(UUID sender, boolean here, long expires) {}

  private final JavaPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final Predicate<String> enabled;
  private final TeleportService teleport;
  private final Map<String, SqliteStore.Document<Place>> places = new HashMap<>();
  private final Set<UUID> vanished = new HashSet<>(),
      loading = new HashSet<>(),
      manualAfk = new HashSet<>();
  private final Map<UUID, Long> active = new HashMap<>(), portalCooldown = new HashMap<>();
  private final Map<UUID, Request> requests = new HashMap<>();
  private final Map<UUID, ArmorStand> seats = new HashMap<>();
  private final Map<String, List<TextDisplay>> holograms = new HashMap<>();
  private final Map<UUID, Position> portalFirst = new HashMap<>();
  private BukkitTask task;
  private Placeholders placeholders;
  private long ticks;

  public Gameplay(
      JavaPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      Predicate<String> enabled) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.enabled = enabled;
    teleport = new TeleportService(plugin, main, store, lang, love);
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
                    }));
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    for (var p : Bukkit.getOnlinePlayers()) loadPlayer(p, false);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
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
    store
        .profile(p.getUniqueId(), "loveessential.vanish")
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      if (!p.isOnline()) return;
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      loading.remove(p.getUniqueId());
                      if (Boolean.parseBoolean(v.orElse("false"))) vanished.add(p.getUniqueId());
                      applyVanish();
                      if (announce && enabled.test("joinquit") && !vanished(p))
                        for (var viewer : Bukkit.getOnlinePlayers())
                          if (viewer.canSee(p))
                            viewer.sendMessage(
                                lang.render("joinquit.join", Map.of("player", p.getName())));
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

  public void command(Player p, String cmd, String[] args) {
    String name = args.length > 0 ? args[0] : "default";
    switch (cmd) {
      case "sethome" -> {
        check("home");
        String id = home(p, name);
        long count =
            places.keySet().stream()
                .filter(k -> k.startsWith("home:" + p.getUniqueId() + ":"))
                .count();
        int limit = plugin.getConfig().getInt("home.default-limit", 3);
        for (int n : List.of(5, 10, 25, 100))
          if (p.hasPermission("loveessential.home.limit." + n)) limit = n;
        if (!places.containsKey(id) && count >= limit)
          throw new IllegalArgumentException("errors.home-limit");
        save(p, id, new Place(Position.of(p.getLocation()), Map.of(), List.of()));
      }
      case "home" -> {
        check("home");
        go(p, home(p, name));
      }
      case "delhome" -> {
        check("home");
        remove(p, home(p, name));
      }
      case "homes" -> {
        check("home");
        lang.send(
            p,
            "home.list",
            Map.of(
                "homes",
                String.join(
                    ", ",
                    places.keySet().stream()
                        .filter(k -> k.startsWith("home:" + p.getUniqueId() + ":"))
                        .map(k -> k.substring(k.lastIndexOf(':') + 1))
                        .toList())));
      }
      case "setspawn" -> {
        check("spawn");
        admin(p);
        save(p, "spawn:" + name, new Place(Position.of(p.getLocation()), Map.of(), List.of()));
      }
      case "spawn" -> {
        check("spawn");
        go(p, "spawn:" + name);
      }
      case "back" -> {
        check("teleport");
        store
            .get("back", p.getUniqueId().toString(), Position.class)
            .whenComplete(
                (v, error) ->
                    main.execute(
                        () -> {
                          if (error != null) {
                            lang.send(p, "errors.storage");
                            return;
                          }
                          if (v.isPresent()) teleport.teleport(p, v.get().value());
                          else lang.send(p, "errors.not-found");
                        }));
      }
      case "tpa", "tpahere" -> {
        check("teleport");
        var target = Bukkit.getPlayerExact(name);
        if (target == null || !p.canSee(target) || target.equals(p) || !love.authenticated(target))
          throw new IllegalArgumentException("errors.not-found");
        requests.put(
            target.getUniqueId(),
            new Request(
                p.getUniqueId(), cmd.equals("tpahere"), System.currentTimeMillis() + 60000));
        lang.send(target, "teleport.request", Map.of("player", p.getName()));
        lang.send(p, "common.done");
      }
      case "tpaccept" -> {
        check("teleport");
        var request = requests.remove(p.getUniqueId());
        if (request == null || request.expires() < System.currentTimeMillis())
          throw new IllegalArgumentException("errors.not-found");
        var sender = Bukkit.getPlayer(request.sender());
        if (sender == null || !p.canSee(sender))
          throw new IllegalArgumentException("errors.not-found");
        if (request.here()) teleport.teleport(p, Position.of(sender.getLocation()));
        else teleport.teleport(sender, Position.of(p.getLocation()));
      }
      case "tpdeny" -> {
        requests.remove(p.getUniqueId());
        lang.send(p, "common.done");
      }
      case "vanish" -> {
        check("vanish");
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
        if (!manualAfk.add(p.getUniqueId())) manualAfk.remove(p.getUniqueId());
        lang.send(p, "afk.changed");
      }
      case "skin" -> {
        check("skin");
        String skin = name.equals("default") ? p.getName() : name;
        if (!skin.matches("[a-zA-Z0-9_]{1,16}")) throw new IllegalArgumentException("errors.input");
        var profile = Bukkit.createProfile(skin);
        java.util.concurrent.CompletableFuture.supplyAsync(
                () -> {
                  profile.complete();
                  return profile;
                },
                ((LoveEssentialPlugin) plugin).io())
            .thenAccept(
                updated ->
                    main.execute(
                        () -> {
                          if (!p.isOnline()) return;
                          store
                              .profile(p.getUniqueId(), "loveessential.skin", skin)
                              .whenComplete(
                                  (v, error) ->
                                      main.execute(
                                          () -> {
                                            if (error != null) {
                                              lang.send(p, "errors.storage");
                                              return;
                                            }
                                            p.setPlayerProfile(updated);
                                            lang.send(p, "common.done");
                                          }));
                        }));
      }
      case "sit" -> {
        check("sitride");
        if (seats.containsKey(p.getUniqueId())) {
          seats.remove(p.getUniqueId()).remove();
          return;
        }
        var at = p.getLocation().clone().subtract(0, 1.3, 0);
        var seat =
            p.getWorld()
                .spawn(
                    at,
                    ArmorStand.class,
                    s -> {
                      s.setPersistent(false);
                      s.setVisible(false);
                      s.setGravity(false);
                      s.setMarker(true);
                      s.setInvulnerable(true);
                    });
        seat.addPassenger(p);
        seats.put(p.getUniqueId(), seat);
      }
      case "ride" -> {
        check("sitride");
        if (!p.hasPermission("loveessential.ride"))
          throw new IllegalArgumentException("errors.permission");
        var target =
            p.getWorld().getNearbyEntities(p.getLocation(), 2, 2, 2).stream()
                .filter(
                    e ->
                        e instanceof LivingEntity
                            && !e.equals(p)
                            && (!(e instanceof Player other)
                                || other.hasPermission("loveessential.ride.accept")
                                    && p.canSee(other)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
        target.addPassenger(p);
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

  public void hologram(Player p, String op, String id, String text) {
    check("hologram");
    admin(p);
    if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("validation.id");
    String doc = "hologram:" + id;
    switch (op) {
      case "create", "addline" -> {
        var old = places.get(doc);
        if (op.equals("create") && old != null)
          throw new IllegalArgumentException("errors.conflict");
        if (op.equals("addline") && old == null)
          throw new IllegalArgumentException("errors.not-found");
        var keys = new ArrayList<String>(old == null ? List.of() : old.value().textKeys());
        String key = "holograms." + id + ".line" + keys.size();
        keys.add(key);
        var place =
            new Place(
                old == null ? Position.of(p.getLocation()) : old.value().position(),
                Map.of(),
                keys);
        java.util.concurrent.CompletableFuture.runAsync(
                () -> {
                  try {
                    lang.putAll(Map.of(key, text));
                  } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                  }
                },
                ((LoveEssentialPlugin) plugin).io())
            .whenComplete(
                (v, error) ->
                    main.execute(
                        () -> {
                          if (error != null) lang.send(p, "errors.storage");
                          else save(p, doc, place);
                        }));
      }
      case "move" -> {
        var old = Objects.requireNonNull(places.get(doc));
        save(
            p,
            doc,
            new Place(Position.of(p.getLocation()), old.value().options(), old.value().textKeys()));
      }
      case "delete" -> remove(p, doc);
      default -> throw new IllegalArgumentException("errors.input");
    }
  }

  public void portal(Player p, String op, String id) {
    check("portal");
    admin(p);
    switch (op) {
      case "pos1" -> {
        portalFirst.put(p.getUniqueId(), Position.of(p.getLocation()));
        lang.send(p, "common.done");
      }
      case "create" -> {
        if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("validation.id");
        var first = portalFirst.get(p.getUniqueId());
        if (first == null || !first.world().equals(p.getWorld().getUID()))
          throw new IllegalArgumentException("errors.input");
        Position second = Position.of(p.getLocation());
        Map<String, String> options = new HashMap<>();
        options.put("x2", Double.toString(second.x()));
        options.put("y2", Double.toString(second.y()));
        options.put("z2", Double.toString(second.z()));
        save(p, "portal:" + id, new Place(first, options, List.of()));
      }
      case "destination" -> {
        var old = Objects.requireNonNull(places.get("portal:" + id));
        var options = new HashMap<>(old.value().options());
        options.put("destination", new com.google.gson.Gson().toJson(Position.of(p.getLocation())));
        save(p, "portal:" + id, new Place(old.value().position(), options, List.of()));
      }
      case "delete" -> remove(p, "portal:" + id);
      default -> throw new IllegalArgumentException("errors.input");
    }
  }

  private void tick() {
    ticks += 20;
    requests.values().removeIf(r -> r.expires() < System.currentTimeMillis());
    if (enabled.test("hologram")) {
      for (var entry : places.entrySet())
        if (entry.getKey().startsWith("hologram:")) {
          var place = entry.getValue().value();
          World world = Bukkit.getWorld(place.position().world());
          if (world == null) continue;
          Location at = place.position().location();
          boolean near =
              world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)
                  && world.getPlayers().stream()
                      .anyMatch(p -> p.getLocation().distanceSquared(at) < 48 * 48);
          var displays = holograms.get(entry.getKey());
          if (near && (displays == null || displays.stream().anyMatch(d -> !d.isValid()))) {
            if (displays != null) displays.forEach(Entity::remove);
            displays = new ArrayList<>();
            for (int i = 0; i < place.textKeys().size(); i++) {
              String key = place.textKeys().get(i);
              var display =
                  world.spawn(
                      at.clone().add(0, -i * .27, 0),
                      TextDisplay.class,
                      d -> {
                        d.setPersistent(false);
                        d.setBillboard(Display.Billboard.CENTER);
                        d.text(lang.render(key));
                      });
              displays.add(display);
            }
            holograms.put(entry.getKey(), displays);
          } else if (!near && displays != null) {
            displays.forEach(Entity::remove);
            holograms.remove(entry.getKey());
          }
        }
    } else {
      holograms.values().forEach(l -> l.forEach(Entity::remove));
      holograms.clear();
    }
    if (enabled.test("nightskip"))
      for (World w : Bukkit.getWorlds())
        if (w.getTime() > 12500 && w.getTime() < 23500) {
          long eligible = w.getPlayers().stream().filter(p -> !vanished(p) && !afk(p)).count(),
              sleeping = w.getPlayers().stream().filter(Player::isSleeping).count();
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
          if (!p.hasPermission("loveessential.afk.bypass")
              && System.currentTimeMillis()
                      - active.getOrDefault(p.getUniqueId(), System.currentTimeMillis())
                  > kick * 1000) p.kick(lang.render("afk.kick"));
    }
    seats
        .entrySet()
        .removeIf(
            e -> {
              if (!e.getValue().isValid() || e.getValue().getPassengers().isEmpty()) {
                e.getValue().remove();
                return true;
              }
              return false;
            });
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
                      var displays = holograms.remove(id);
                      if (displays != null) displays.forEach(Entity::remove);
                      lang.send(p, "common.saved");
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
                      var displays = holograms.remove(id);
                      if (displays != null) displays.forEach(Entity::remove);
                      lang.send(p, "common.done");
                    }));
  }

  private void go(Player p, String id) {
    var place = places.get(id);
    if (place == null) throw new IllegalArgumentException("errors.not-found");
    teleport.teleport(p, place.value().position());
  }

  private String home(Player p, String name) {
    if (!name.matches("[a-zA-Z0-9_-]{1,32}")) throw new IllegalArgumentException("validation.id");
    return "home:" + p.getUniqueId() + ":" + name.toLowerCase(Locale.ROOT);
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
    loadPlayer(e.getPlayer(), true);
    if (enabled.test("joinquit")) e.joinMessage(null);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    var p = e.getPlayer();
    if (enabled.test("joinquit"))
      e.quitMessage(
          vanished(p) ? null : lang.render("joinquit.quit", Map.of("player", p.getName())));
    vanished.remove(p.getUniqueId());
    loading.remove(p.getUniqueId());
    active.remove(p.getUniqueId());
    manualAfk.remove(p.getUniqueId());
    requests.remove(p.getUniqueId());
    portalCooldown.remove(p.getUniqueId());
    portalFirst.remove(p.getUniqueId());
    var seat = seats.remove(p.getUniqueId());
    if (seat != null) seat.remove();
    teleport.forget(p);
  }

  @EventHandler
  public void move(PlayerMoveEvent e) {
    if (e.getTo() == null || e.getFrom().distanceSquared(e.getTo()) < .001) return;
    var p = e.getPlayer();
    active.put(p.getUniqueId(), System.currentTimeMillis());
    manualAfk.remove(p.getUniqueId());
    if (enabled.test("traces") && !vanished(p) && ticks % 4 == 0)
      p.getWorld()
          .spawnParticle(
              Particle.DUST,
              p.getLocation().clone().add(0, .05, 0),
              1,
              0,
              0,
              0,
              0,
              new Particle.DustOptions(Color.fromRGB(120, 100, 75), .5f));
    if (enabled.test("portal")
        && System.currentTimeMillis() - portalCooldown.getOrDefault(p.getUniqueId(), 0L) > 3000)
      for (var entry : places.entrySet())
        if (entry.getKey().startsWith("portal:")) {
          var place = entry.getValue().value();
          var a = place.position();
          if (!a.world().equals(p.getWorld().getUID())
              || !place.options().containsKey("destination")) continue;
          double x2 = Double.parseDouble(place.options().get("x2")),
              y2 = Double.parseDouble(place.options().get("y2")),
              z2 = Double.parseDouble(place.options().get("z2"));
          var at = p.getLocation();
          if (at.getX() >= Math.min(a.x(), x2)
              && at.getX() <= Math.max(a.x(), x2) + 1
              && at.getY() >= Math.min(a.y(), y2)
              && at.getY() <= Math.max(a.y(), y2) + 1
              && at.getZ() >= Math.min(a.z(), z2)
              && at.getZ() <= Math.max(a.z(), z2) + 1) {
            portalCooldown.put(p.getUniqueId(), System.currentTimeMillis());
            teleport.teleport(
                p,
                new com.google.gson.Gson()
                    .fromJson(place.options().get("destination"), Position.class));
            break;
          }
        }
  }

  @EventHandler
  public void interact(PlayerInteractEvent e) {
    active.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
  }

  @EventHandler
  public void ping(ServerListPingEvent e) {
    if (enabled.test("motd")) e.motd(lang.render("motd.text"));
  }

  public void close() {
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    seats.values().forEach(Entity::remove);
    seats.clear();
    holograms.values().forEach(l -> l.forEach(Entity::remove));
    holograms.clear();
    for (var viewer : Bukkit.getOnlinePlayers())
      for (var target : Bukkit.getOnlinePlayers()) viewer.showPlayer(plugin, target);
  }
}
