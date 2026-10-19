package dev.lovelace.loveessential.world;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.TeleportService;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/** World metadata in SQLite; generation, loading and settings use Paper's server-thread API. */
public final class Worlds implements Listener, AutoCloseable {
  public record Options(
      boolean pvp,
      String difficulty,
      String gameMode,
      boolean enforceMode,
      long time,
      boolean freezeTime,
      String weather,
      double borderSize,
      double borderX,
      double borderZ,
      Map<String, String> rules,
      String respawnWorld) {
    public Options {
      rules = Map.copyOf(rules);
    }

    public static Options defaults() {
      return new Options(
          true, "NORMAL", "SURVIVAL", false, 6000, false, "CLEAR", 59999968, 0, 0, Map.of(), "");
    }
  }

  public record Definition(
      String name,
      String environment,
      String type,
      long seed,
      boolean structures,
      boolean autoload,
      String permission,
      Position spawn,
      Options options) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final NativeDialogs ui;
  private final TeleportService teleport;
  private final Map<String, SqliteStore.Document<Definition>> definitions =
      new ConcurrentHashMap<>();
  private final Set<String> busy = new HashSet<>();

  public Worlds(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      NativeDialogs ui) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.ui = ui;
    teleport = new TeleportService(plugin, main, store, lang, love);
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public CompletableFuture<Void> load() {
    return store
        .list("world", Definition.class)
        .thenCompose(
            saved ->
                main.call(
                    () -> {
                      saved.forEach(d -> definitions.put(d.id(), d));
                      if (plugin.module("worlds"))
                        for (var d : saved)
                          if (d.value().autoload())
                            try {
                              loadWorld(d.value());
                            } catch (RuntimeException e) {
                              plugin
                                  .getLogger()
                                  .warning(
                                      lang.text("worlds.load-failed")
                                          + " ["
                                          + d.id()
                                          + "] "
                                          + e.getClass().getSimpleName());
                            }
                      return null;
                    }));
  }

  public List<String> names() {
    return java.util.stream.Stream.concat(
            definitions.keySet().stream(), Bukkit.getWorlds().stream().map(World::getName))
        .distinct()
        .sorted()
        .toList();
  }

  public Optional<SqliteStore.Document<Definition>> definition(String name) {
    return Optional.ofNullable(definitions.get(name));
  }

  public static void name(String name) {
    if (!name.matches("[A-Za-z0-9_-]{1,48}"))
      throw new IllegalArgumentException("worlds.invalid-name");
  }

  public static void validate(Definition d) {
    name(d.name());
    World.Environment.valueOf(d.environment());
    WorldType.valueOf(d.type());
    Difficulty.valueOf(d.options().difficulty());
    GameMode.valueOf(d.options().gameMode());
    var o = d.options();
    if (o.time() < 0
        || o.time() > 23999
        || !Double.isFinite(o.borderSize())
        || o.borderSize() < 1
        || o.borderSize() > 59999968
        || !Double.isFinite(o.borderX())
        || !Double.isFinite(o.borderZ())
        || Math.abs(o.borderX()) > 29999984
        || Math.abs(o.borderZ()) > 29999984
        || !Set.of("CLEAR", "RAIN", "THUNDER").contains(o.weather()))
      throw new IllegalArgumentException("errors.input");
    if (!o.respawnWorld().isBlank()) name(o.respawnWorld());
    for (var e : o.rules().entrySet()) {
      var rule = GameRule.getByName(e.getKey());
      if (rule == null) throw new IllegalArgumentException("worlds.invalid-rule");
      if (rule.getType().equals(Boolean.class) && !Set.of("true", "false").contains(e.getValue()))
        throw new IllegalArgumentException("worlds.invalid-rule");
      if (rule.getType().equals(Integer.class)) Integer.parseInt(e.getValue());
    }
  }

  private boolean admin(Player p) {
    return plugin.module("worlds") && p.hasPermission("loveessential.worlds.admin");
  }

  private void access(Player p, String world) {
    if (!plugin.module("worlds")
        || !love.authenticated(p)
        || !p.hasPermission("loveessential.worlds.teleport"))
      throw new IllegalArgumentException("errors.permission");
    var d = definitions.get(world);
    if (d != null && !d.value().permission().isBlank() && !p.hasPermission(d.value().permission()))
      throw new IllegalArgumentException("errors.access");
  }

  public void teleport(Player p, String name) {
    access(p, name);
    var world = Bukkit.getWorld(name);
    if (world == null) throw new IllegalArgumentException("worlds.not-loaded");
    var d = definitions.get(name);
    teleport.teleport(
        p,
        d != null && d.value().spawn() != null
            ? d.value().spawn()
            : Position.of(world.getSpawnLocation()));
  }

  private Definition capture(World w) {
    return new Definition(
        w.getName(),
        w.getEnvironment().name(),
        w.getWorldType().name(),
        w.getSeed(),
        w.canGenerateStructures(),
        false,
        "",
        Position.of(w.getSpawnLocation()),
        new Options(
            w.getPVP(),
            w.getDifficulty().name(),
            "SURVIVAL",
            false,
            w.getTime(),
            trueIfStopped(w),
            w.isThundering() ? "THUNDER" : w.hasStorm() ? "RAIN" : "CLEAR",
            w.getWorldBorder().getSize(),
            w.getWorldBorder().getCenter().getX(),
            w.getWorldBorder().getCenter().getZ(),
            Map.of(),
            ""));
  }

  private boolean trueIfStopped(World w) {
    return Boolean.FALSE.equals(w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE));
  }

  public CompletableFuture<Void> create(
      String name,
      String env,
      String type,
      long seed,
      boolean structures,
      boolean imported,
      String actor) {
    MainThread.require();
    name(name);
    var d = new Definition(name, env, type, seed, structures, true, "", null, Options.defaults());
    validate(d);
    if (!imported && Bukkit.getWorld(name) != null)
      throw new IllegalArgumentException("worlds.exists");
    if (definitions.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name)))
      throw new IllegalArgumentException("errors.conflict");
    if (definitions.containsKey(name) || !busy.add(name))
      throw new IllegalArgumentException("errors.conflict");
    if (definitions.size() >= plugin.getConfig().getInt("worlds.max-worlds", 64)) {
      busy.remove(name);
      throw new IllegalArgumentException("worlds.limit");
    }
    Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    return CompletableFuture.runAsync(
            () -> {
              try {
                Path root = container.toRealPath();
                Path path = root.resolve(name);
                if (imported) {
                  if (!Files.isRegularFile(path.resolve("level.dat"))
                      || !path.toRealPath().getParent().equals(root))
                    throw new IllegalArgumentException("worlds.import-missing");
                } else if (Files.exists(path)) throw new IllegalArgumentException("worlds.exists");
              } catch (java.io.IOException e) {
                throw new CompletionException(e);
              }
            },
            plugin.io())
        .thenCompose(v -> store.save("world", name, 0, d, actor))
        .thenCompose(
            rev ->
                main.<Void>call(
                    () -> {
                      definitions.put(name, new SqliteStore.Document<>(name, rev, d));
                      loadWorld(d);
                      return null;
                    }))
        .whenComplete((v, e) -> main.execute(() -> busy.remove(name)));
  }

  private World loadWorld(Definition d) {
    MainThread.require();
    validate(d);
    World world = Bukkit.getWorld(d.name());
    if (world == null) {
      world =
          new WorldCreator(d.name())
              .environment(World.Environment.valueOf(d.environment()))
              .type(WorldType.valueOf(d.type()))
              .seed(d.seed())
              .generateStructures(d.structures())
              .createWorld();
      if (world == null) throw new IllegalArgumentException("worlds.load-failed");
    }
    apply(world, d);
    return world;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void apply(World w, Definition d) {
    var o = d.options();
    w.setPVP(o.pvp());
    w.setDifficulty(Difficulty.valueOf(o.difficulty()));
    w.setTime(o.time());
    w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, !o.freezeTime());
    w.setStorm(!o.weather().equals("CLEAR"));
    w.setThundering(o.weather().equals("THUNDER"));
    w.getWorldBorder().setCenter(o.borderX(), o.borderZ());
    w.getWorldBorder().setSize(o.borderSize());
    for (var e : o.rules().entrySet()) {
      GameRule rule = GameRule.getByName(e.getKey());
      w.setGameRule(
          rule,
          rule.getType().equals(Boolean.class)
              ? Boolean.valueOf(e.getValue())
              : Integer.valueOf(e.getValue()));
    }
    if (d.spawn() != null) w.setSpawnLocation(d.spawn().location());
    for (var p : w.getPlayers()) mode(p, d);
  }

  private void mode(Player p, Definition d) {
    if (d.options().enforceMode()) p.setGameMode(GameMode.valueOf(d.options().gameMode()));
  }

  public CompletableFuture<Void> save(Definition d, long revision, String actor) {
    validate(d);
    return store
        .save("world", d.name(), revision, d, actor)
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      definitions.put(d.name(), new SqliteStore.Document<>(d.name(), rev, d));
                      var w = Bukkit.getWorld(d.name());
                      if (w != null) apply(w, d);
                      return null;
                    }));
  }

  private SqliteStore.Document<Definition> document(String name) {
    var existing = definitions.get(name);
    if (existing != null) return existing;
    var w = Bukkit.getWorld(name);
    if (w == null) throw new IllegalArgumentException("errors.not-found");
    return new SqliteStore.Document<>(name, 0, capture(w));
  }

  private void done(Player p, CompletableFuture<?> future, Runnable after) {
    future.whenComplete(
        (v, e) ->
            main.execute(
                () -> {
                  if (!p.isOnline()) return;
                  if (e != null) ui.error(p, e);
                  else {
                    lang.send(p, "common.saved");
                    if (plugin.ready() && admin(p)) after.run();
                  }
                }));
  }

  public void command(Player p, String[] args) {
    if (!plugin.module("worlds")) throw new IllegalArgumentException("errors.module-disabled");
    if (args.length == 0 || args[0].equals("list")) {
      menu(p, 0);
      return;
    }
    if (args[0].equals("tp") && args.length == 2) {
      teleport(p, args[1]);
      return;
    }
    if (!admin(p)) throw new IllegalArgumentException("errors.permission");
    switch (args[0]) {
      case "create", "import" -> {
        if (args.length < 2) {
          creation(p, args[0].equals("import"));
          return;
        }
        done(
            p,
            create(
                args[1],
                args.length > 2 ? args[2].toUpperCase(Locale.ROOT) : "NORMAL",
                args.length > 3 ? args[3].toUpperCase(Locale.ROOT) : "NORMAL",
                args.length > 4 ? Long.parseLong(args[4]) : new Random().nextLong(),
                true,
                args[0].equals("import"),
                p.getUniqueId().toString()),
            () -> edit(p, args[1]));
      }
      case "edit", "info" -> {
        if (args.length != 2) throw new IllegalArgumentException("worlds.usage");
        edit(p, args[1]);
      }
      case "load" -> {
        if (args.length != 2) throw new IllegalArgumentException("worlds.usage");
        loadWorld(document(args[1]).value());
        edit(p, args[1]);
      }
      case "unload" -> {
        if (args.length != 2) throw new IllegalArgumentException("worlds.usage");
        unload(p, args[1]);
      }
      case "setspawn" -> {
        var d = document(p.getWorld().getName());
        var v = d.value();
        done(
            p,
            save(
                new Definition(
                    v.name(),
                    v.environment(),
                    v.type(),
                    v.seed(),
                    v.structures(),
                    v.autoload(),
                    v.permission(),
                    Position.of(p.getLocation()),
                    v.options()),
                d.revision(),
                p.getUniqueId().toString()),
            () -> edit(p, v.name()));
      }
      default -> throw new IllegalArgumentException("worlds.usage");
    }
  }

  public List<String> suggestions(Player p, String[] args) {
    if (!plugin.module("worlds")) return List.of();
    if (args.length == 1)
      return admin(p)
          ? List.of("list", "create", "import", "load", "unload", "tp", "edit", "info", "setspawn")
          : List.of("list", "tp");
    if (args.length == 2 && Set.of("load", "unload", "tp", "edit", "info").contains(args[0]))
      return names();
    if (args.length == 3 && Set.of("create", "import").contains(args[0]))
      return List.of("NORMAL", "NETHER", "THE_END");
    return List.of();
  }

  public void menu(Player p, int page) {
    if (!p.hasPermission("loveessential.worlds.teleport") && !admin(p))
      throw new IllegalArgumentException("errors.permission");
    var names = names();
    int start = Math.max(0, page) * 16;
    var buttons = new ArrayList<NativeDialogs.Button>();
    for (String name :
        names.subList(Math.min(start, names.size()), Math.min(start + 16, names.size())))
      buttons.add(
          new NativeDialogs.Button(
              name,
              lang.render(
                  "worlds.entry",
                  Map.of(
                      "world",
                      name,
                      "state",
                      lang.text(
                          Bukkit.getWorld(name) == null ? "worlds.unloaded" : "worlds.loaded"))),
              v -> {
                if (admin(p)) edit(p, name);
                else teleport(p, name);
              }));
    if (admin(p)) {
      buttons.add(ui.button("create", "worlds.create", v -> creation(p, false)));
      buttons.add(ui.button("import", "worlds.import", v -> creation(p, true)));
    }
    if (page > 0) buttons.add(ui.button("prev", "gui.previous", v -> menu(p, page - 1)));
    if (start + 16 < names.size())
      buttons.add(ui.button("next", "common.next", v -> menu(p, page + 1)));
    ui.show(
        p,
        "worlds.title",
        "worlds.help",
        List.of(),
        buttons,
        who ->
            plugin.module("worlds")
                && (who.hasPermission("loveessential.worlds.teleport") || admin(who)));
  }

  private void creation(Player p, boolean imported) {
    ui.show(
        p,
        "worlds.create",
        "worlds.create-help",
        List.of(
            ui.text("name", "worlds.name", "", 48),
            ui.select(
                "environment",
                "worlds.environment",
                "NORMAL",
                List.of("NORMAL", "NETHER", "THE_END")),
            ui.select(
                "type",
                "worlds.type",
                "NORMAL",
                List.of("NORMAL", "FLAT", "AMPLIFIED", "LARGE_BIOMES")),
            ui.text("seed", "worlds.seed", Long.toString(new Random().nextLong()), 20)),
        List.of(
            ui.button(
                "save",
                "worlds.create",
                v ->
                    done(
                        p,
                        create(
                            v.get("name"),
                            v.get("environment"),
                            v.get("type"),
                            Long.parseLong(v.get("seed")),
                            true,
                            imported,
                            p.getUniqueId().toString()),
                        () -> edit(p, v.get("name")))),
            ui.button("back", "common.back", v -> menu(p, 0))),
        this::admin);
  }

  public void edit(Player p, String name) {
    var doc = document(name);
    var d = doc.value();
    var o = d.options();
    ui.show(
        p,
        "worlds.edit",
        "worlds.edit-help",
        List.of(
            ui.text("permission", "worlds.permission", d.permission(), 128),
            ui.select("autoload", "worlds.autoload", "" + d.autoload(), List.of("true", "false")),
            ui.select("pvp", "worlds.pvp", "" + o.pvp(), List.of("true", "false")),
            ui.select(
                "difficulty",
                "worlds.difficulty",
                o.difficulty(),
                List.of("PEACEFUL", "EASY", "NORMAL", "HARD")),
            ui.select(
                "mode",
                "worlds.mode",
                o.gameMode(),
                List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR")),
            ui.select("enforce", "worlds.enforce", "" + o.enforceMode(), List.of("true", "false"))),
        List.of(
            ui.button(
                "save",
                "content.save",
                v -> {
                  var n =
                      new Options(
                          Boolean.parseBoolean(v.get("pvp")),
                          v.get("difficulty"),
                          v.get("mode"),
                          Boolean.parseBoolean(v.get("enforce")),
                          o.time(),
                          o.freezeTime(),
                          o.weather(),
                          o.borderSize(),
                          o.borderX(),
                          o.borderZ(),
                          o.rules(),
                          o.respawnWorld());
                  done(
                      p,
                      save(
                          new Definition(
                              d.name(),
                              d.environment(),
                              d.type(),
                              d.seed(),
                              d.structures(),
                              Boolean.parseBoolean(v.get("autoload")),
                              v.get("permission"),
                              d.spawn(),
                              n),
                          doc.revision(),
                          p.getUniqueId().toString()),
                      () -> edit(p, name));
                }),
            ui.button("advanced", "worlds.advanced", v -> advanced(p, doc)),
            ui.button("tp", "worlds.teleport", v -> teleport(p, name)),
            ui.button(
                "load",
                "worlds.load",
                v -> {
                  loadWorld(d);
                  edit(p, name);
                }),
            ui.button("unload", "worlds.unload", v -> unload(p, name)),
            ui.button("back", "common.back", v -> menu(p, 0))),
        this::admin);
  }

  private void advanced(Player p, SqliteStore.Document<Definition> doc) {
    var d = doc.value();
    var o = d.options();
    ui.show(
        p,
        "worlds.advanced",
        "worlds.advanced-help",
        List.of(
            ui.text("time", "worlds.time", "" + o.time(), 5),
            ui.select("freeze", "worlds.freeze", "" + o.freezeTime(), List.of("true", "false")),
            ui.select(
                "weather", "worlds.weather", o.weather(), List.of("CLEAR", "RAIN", "THUNDER")),
            ui.text("border", "worlds.border", "" + o.borderSize(), 20),
            ui.text("x", "worlds.border-x", "" + o.borderX(), 20),
            ui.text("z", "worlds.border-z", "" + o.borderZ(), 20),
            ui.text("respawn", "worlds.respawn", o.respawnWorld(), 48)),
        List.of(
            ui.button(
                "save",
                "content.save",
                v -> {
                  Map<String, String> rules = o.rules();
                  var n =
                      new Options(
                          o.pvp(),
                          o.difficulty(),
                          o.gameMode(),
                          o.enforceMode(),
                          Long.parseLong(v.get("time")),
                          Boolean.parseBoolean(v.get("freeze")),
                          v.get("weather"),
                          Double.parseDouble(v.get("border")),
                          Double.parseDouble(v.get("x")),
                          Double.parseDouble(v.get("z")),
                          rules,
                          v.get("respawn"));
                  done(
                      p,
                      save(
                          new Definition(
                              d.name(),
                              d.environment(),
                              d.type(),
                              d.seed(),
                              d.structures(),
                              d.autoload(),
                              d.permission(),
                              d.spawn(),
                              n),
                          doc.revision(),
                          p.getUniqueId().toString()),
                      () -> edit(p, d.name()));
                }),
            ui.button("rules", "worlds.rules", v -> rulesEditor(p, doc)),
            ui.button("back", "common.back", v -> edit(p, d.name()))),
        this::admin);
  }

  private void rulesEditor(Player p, SqliteStore.Document<Definition> doc) {
    var d = doc.value();
    var o = d.options();
    var rules = Arrays.stream(GameRule.values()).map(GameRule::getName).sorted().toList();
    ui.show(
        p,
        "worlds.rules",
        "worlds.rules-help",
        List.of(
            ui.select("rule", "worlds.rule", rules.getFirst(), rules),
            ui.text("value", "worlds.rule-value", "true", 64)),
        List.of(
            ui.button(
                "save",
                "content.save",
                v -> {
                  var next = new HashMap<>(o.rules());
                  next.put(v.get("rule"), v.get("value"));
                  var opts =
                      new Options(
                          o.pvp(),
                          o.difficulty(),
                          o.gameMode(),
                          o.enforceMode(),
                          o.time(),
                          o.freezeTime(),
                          o.weather(),
                          o.borderSize(),
                          o.borderX(),
                          o.borderZ(),
                          next,
                          o.respawnWorld());
                  done(
                      p,
                      save(
                          new Definition(
                              d.name(),
                              d.environment(),
                              d.type(),
                              d.seed(),
                              d.structures(),
                              d.autoload(),
                              d.permission(),
                              d.spawn(),
                              opts),
                          doc.revision(),
                          p.getUniqueId().toString()),
                      () -> edit(p, d.name()));
                }),
            ui.button("back", "common.back", v -> advanced(p, doc))),
        this::admin);
  }

  private void unload(Player p, String name) {
    var w = Bukkit.getWorld(name);
    if (w == null) throw new IllegalArgumentException("worlds.not-loaded");
    if (!w.getPlayers().isEmpty() || w.equals(Bukkit.getWorlds().getFirst()))
      throw new IllegalArgumentException("worlds.cannot-unload");
    ui.confirm(
        p,
        "worlds.unload-confirm",
        () -> {
          if (!admin(p) || !w.getPlayers().isEmpty() || !Bukkit.unloadWorld(w, true))
            throw new IllegalArgumentException("worlds.cannot-unload");
          menu(p, 0);
        },
        () -> edit(p, name),
        this::admin);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void entering(PlayerTeleportEvent e) {
    if (!plugin.module("worlds")
        || e.getTo() == null
        || e.getFrom().getWorld() == e.getTo().getWorld()) return;
    var d = definitions.get(e.getTo().getWorld().getName());
    if (d != null
        && (!love.authenticated(e.getPlayer())
            || !d.value().permission().isBlank()
                && !e.getPlayer().hasPermission(d.value().permission()))) {
      e.setCancelled(true);
      lang.send(e.getPlayer(), "errors.access");
    }
  }

  @EventHandler
  public void changed(PlayerChangedWorldEvent e) {
    var d = definitions.get(e.getPlayer().getWorld().getName());
    if (plugin.module("worlds") && d != null && love.authenticated(e.getPlayer()))
      mode(e.getPlayer(), d.value());
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent e) {
    if (!plugin.module("worlds") || !love.authenticated(e.getPlayer())) return;
    var d = definitions.get(e.getPlayer().getWorld().getName());
    if (d != null && !d.value().options().respawnWorld().isBlank()) {
      var w = Bukkit.getWorld(d.value().options().respawnWorld());
      if (w != null) {
        var target = definitions.get(w.getName());
        if (target == null
            || target.value().permission().isBlank()
            || e.getPlayer().hasPermission(target.value().permission()))
          e.setRespawnLocation(w.getSpawnLocation());
      }
    }
  }

  @Override
  public void close() {
    HandlerList.unregisterAll(this);
    busy.clear();
  }
}
