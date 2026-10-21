package dev.lovelace.loveessential.npc;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.api.LoveEssentialService.*;
import dev.lovelace.loveessential.condition.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class NpcService implements Listener, AutoCloseable {
  private final JavaPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final ConditionEngine conditions;
  private final ActionEngine actions;
  private final LoveBridge love;
  private final ConcurrentHashMap<String, NpcDefinition> definitions = new ConcurrentHashMap<>();
  private final Map<String, Integer> numbers = new ConcurrentHashMap<>();

  public int number(String id) {
    return numbers.getOrDefault(id, 0);
  }

  public String resolve(String id) {
    return numbers.entrySet().stream()
        .filter(e -> e.getValue().toString().equals(id))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(id);
  }

  private final Map<String, Live> live = new HashMap<>();
  private final Map<UUID, Long> clicked = new HashMap<>();
  private final Map<String, Long> spawnFailures = new HashMap<>();
  private final Map<String, Hook> hooks = new ConcurrentHashMap<>();

  private record Hook(int priority, InteractionProvider provider) {}

  private enum Trigger {
    RIGHT_CLICK,
    LEFT_CLICK
  }

  private final ExecutorService paths;
  private final NamespacedKey entityKey;
  private final Random random = new Random();
  private BukkitTask task;
  private long tick;
  private boolean closed;
  private int plansThisTick;
  private final Semaphore pathSlots;
  private final ArrayDeque<Capture> captures = new ArrayDeque<>();

  private static final class Capture {
    final Live live;
    final NpcDefinition definition;
    final World world;
    final PathSearch.Cell start, target;
    PathSearch.Cell goal;
    double goalDistance;
    final int x, y, z, width, height;
    final BitSet cells = new BitSet();
    int cursor;

    Capture(Live live, NpcDefinition definition, Location destination, int radius) {
      this.live = live;
      this.definition = definition;
      var at = live.entity.getLocation();
      world = at.getWorld();
      start = new PathSearch.Cell(at.getBlockX(), at.getBlockY(), at.getBlockZ());
      target =
          new PathSearch.Cell(
              destination.getBlockX(), destination.getBlockY(), destination.getBlockZ());
      goal = start;
      goalDistance =
          Math.pow(start.x() - target.x(), 2)
              + Math.pow(start.y() - target.y(), 2)
              + Math.pow(start.z() - target.z(), 2);
      x = start.x() - radius;
      z = start.z() - radius;
      y = Math.max(world.getMinHeight() + 1, start.y() - 4);
      height = Math.min(world.getMaxHeight() - 3, start.y() + 4) - y + 1;
      width = radius * 2 + 1;
    }
  }

  private void cancelCaptures() {
    for (var capture : captures) {
      capture.live.planning = false;
      pathSlots.release();
    }
    captures.clear();
  }

  private DialogService dialogs;
  private List<? extends Player> eligiblePlayers = List.of();
  private final Map<String, Long> trackingFailures = new HashMap<>();
  private Predicate<Player> vanished = p -> false;

  public void vanish(Predicate<Player> check) {
    vanished = check;
  }

  private static final class Live {
    final String key;
    final String id;
    final UUID viewer;
    final long revision;
    final LivingEntity entity;
    final boolean preview;
    final NpcDefinition previewDefinition;
    int point, direction = 1, wait, stuck, failures;
    long pauseUntil;
    boolean planning, finished;
    List<PathSearch.Cell> path = List.of();
    int pathIndex;
    Location last;

    Live(String key, NpcDefinition n, UUID viewer, LivingEntity e, boolean preview) {
      this.key = key;
      id = n.id();
      revision = n.revision();
      this.viewer = viewer;
      entity = e;
      last = e.getLocation();
      this.preview = preview;
      this.previewDefinition = n;
    }
  }

  public NpcService(
      JavaPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      ConditionEngine conditions,
      ActionEngine actions,
      LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.conditions = conditions;
    this.actions = actions;
    this.love = love;
    entityKey = new NamespacedKey(plugin, "npc");
    pathSlots =
        new Semaphore(
            Math.clamp(plugin.getConfig().getInt("npc.pathfinding.max-pending", 8), 1, 32));
    int threads = Math.max(1, Math.min(4, plugin.getConfig().getInt("npc.pathfinding.threads", 2)));
    paths =
        new ThreadPoolExecutor(
            threads,
            threads,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            Thread.ofPlatform().name("love-path-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public void dialogs(DialogService d) {
    dialogs = d;
  }

  public CompletableFuture<Void> load() {
    return store
        .list("npc", NpcDefinition.class)
        .thenCompose(
            list ->
                store
                    .npcNumbers(list.stream().map(SqliteStore.Document::id).toList())
                    .thenApply(
                        allocated -> {
                          numbers.clear();
                          numbers.putAll(allocated);
                          return list;
                        }))
        .thenCompose(
            list ->
                main.call(
                    () -> {
                      definitions.clear();
                      list.forEach(d -> definitions.put(d.id(), d.value().revision(d.revision())));
                      return null;
                    }));
  }

  public void start() {
    MainThread.require();
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    Bukkit.getPluginManager().registerEvents(this, plugin);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
  }

  public void enabled(boolean value) {
    MainThread.require();
    if (value) {
      start();
      return;
    }
    if (task != null) {
      task.cancel();
      task = null;
    }
    HandlerList.unregisterAll(this);
    live.values().forEach(l -> l.entity.remove());
    live.clear();
    clicked.clear();
    eligiblePlayers = List.of();
    trackingFailures.clear();
    spawnFailures.clear();
    cancelCaptures();
  }

  public Optional<NpcDefinition> get(String id) {
    var exact = definitions.get(id);
    return Optional.ofNullable(exact != null ? exact : definitions.get(resolve(id)));
  }

  public Collection<NpcDefinition> all() {
    return List.copyOf(definitions.values());
  }

  public NpcValidator validator(Predicate<String> text) {
    return new NpcValidator(text, conditions::known, actions::known);
  }

  public CompletableFuture<NpcDefinition> save(NpcDefinition npc, long expected) {
    return save(npc, expected, "api");
  }

  public CompletableFuture<NpcDefinition> save(NpcDefinition npc, long expected, String actor) {
    MainThread.require();
    validate(npc);
    return store
        .save("npc", npc.id(), expected, npc.revision(expected + 1), actor)
        .thenCompose(
            revision ->
                store
                    .npcNumbers(List.of(npc.id()))
                    .thenApply(
                        allocated -> {
                          numbers.putAll(allocated);
                          return revision;
                        }))
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      var saved = npc.revision(rev);
                      var previous = definitions.put(npc.id(), saved);
                      remove(npc.id());
                      Bukkit.getPluginManager().callEvent(new NpcChangedEvent(previous, saved));
                      return saved;
                    }));
  }

  public void validate(NpcDefinition n) {
    var errors =
        validator(lang::contains)
            .validate(n, plugin.getConfig().getInt("npc.max-route-points", 128));
    if (!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst());
    if (!plugin.getConfig().getStringList("npc.allowed-types").contains(n.entityType()))
      throw new IllegalArgumentException("validation.entity");
    var type = EntityType.valueOf(n.entityType());
    if (!type.isAlive() || type == EntityType.PLAYER)
      throw new IllegalArgumentException("validation.entity");
    var at = n.spawn().location();
    if (!at.getWorld().getWorldBorder().isInside(at)
        || at.getY() < at.getWorld().getMinHeight()
        || at.getY() >= at.getWorld().getMaxHeight() - 2)
      throw new IllegalArgumentException("validation.position");
    if (!definitions.containsKey(n.id())
        && definitions.size() >= plugin.getConfig().getInt("npc.max-definitions", 1000))
      throw new IllegalArgumentException("validation.limit");
  }

  public CompletableFuture<Void> delete(String id, long revision) {
    return delete(id, revision, "api");
  }

  public CompletableFuture<Void> delete(String id, long revision, String actor) {
    return store
        .delete("npc", id, revision, actor)
        .thenCompose(
            v ->
                main.call(
                    () -> {
                      var previous = definitions.remove(id);
                      numbers.remove(id);
                      spawnFailures.remove(id);
                      trackingFailures
                          .keySet()
                          .removeIf(key -> key.equals(id) || key.startsWith(id + ":"));
                      remove(id);
                      Bukkit.getPluginManager().callEvent(new NpcChangedEvent(previous, null));
                      return null;
                    }));
  }

  public Registration interaction(String id, int priority, InteractionProvider p) {
    var hook = new Hook(priority, p);
    if (hooks.putIfAbsent(id, hook) != null)
      throw new IllegalArgumentException("duplicate-interaction");
    return () -> hooks.remove(id, hook);
  }

  public Optional<String> identify(Entity e) {
    return Optional.ofNullable(
        e.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING));
  }

  public Optional<Entity> entity(String id) {
    return entity(id, null);
  }

  public Optional<Entity> entity(String id, Player viewer) {
    return live.values().stream()
        .filter(
            l ->
                l.id.equals(id)
                    && !l.preview
                    && (l.viewer == null
                        || viewer != null && l.viewer.equals(viewer.getUniqueId())))
        .map(l -> (Entity) l.entity)
        .findFirst();
  }

  public void preview(Player p, NpcDefinition n) {
    preview(p, n, Map.of());
  }

  public void preview(Player p, NpcDefinition n, Map<String, String> texts) {
    MainThread.require();
    String key = "preview:" + p.getUniqueId();
    var old = live.remove(key);
    if (old != null) old.entity.remove();
    var entity = spawn(n, p.getUniqueId(), true);
    if (entity == null) return;
    if (texts.containsKey(n.nameKey()))
      entity.customName(
          net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
              .deserialize(texts.get(n.nameKey())));
    var instance = new Live(key, n, p.getUniqueId(), entity, true);
    live.put(key, instance);
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (live.remove(key, instance)) entity.remove();
            },
            400);
  }

  public void controlPreview(Player p, String op) {
    var l = live.get("preview:" + p.getUniqueId());
    if (l == null) throw new IllegalArgumentException("errors.not-found");
    switch (op) {
      case "pause" -> l.pauseUntil = Long.MAX_VALUE;
      case "resume" -> l.pauseUntil = 0;
      case "step" -> {
        move(l, l.previewDefinition);
        l.pauseUntil = Long.MAX_VALUE;
      }
      default -> throw new IllegalArgumentException("errors.input");
    }
  }

  public void removePreview(Player p) {
    var old = live.remove("preview:" + p.getUniqueId());
    if (old != null) old.entity.remove();
  }

  private LivingEntity spawn(NpcDefinition n, UUID viewer, boolean preview) {
    if (spawnFailures.getOrDefault(n.id(), 0L) > tick) return null;
    try {
      return spawn0(n, viewer, preview);
    } catch (RuntimeException e) {
      spawnFailures.put(n.id(), tick + 200);
      plugin
          .getLogger()
          .log(
              java.util.logging.Level.WARNING,
              lang.text("logs.npc-failed") + " [" + n.id() + "/" + n.entityType() + "]",
              e);
      return null;
    }
  }

  private LivingEntity spawn0(NpcDefinition n, UUID viewer, boolean preview) {
    Location at = n.spawn().location();
    if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4) || !safe(n, at))
      return null;
    var entity =
        at.getWorld()
            .spawnEntity(
                at,
                EntityType.valueOf(n.entityType()),
                CreatureSpawnEvent.SpawnReason.CUSTOM,
                created -> {
                  if (created instanceof LivingEntity living) configure(living, n);
                });
    if (!(entity instanceof LivingEntity e)) {
      entity.remove();
      return null;
    }
    if (preview && viewer != null) {
      var player = Bukkit.getPlayer(viewer);
      if (player != null) player.showEntity(plugin, e);
    }
    return e;
  }

  private void configure(LivingEntity e, NpcDefinition n) {
    e.setPersistent(false);
    e.setRemoveWhenFarAway(false);
    e.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, n.id());
    e.setAI(false);
    e.setInvulnerable(n.flag("invulnerable", true));
    e.setCollidable(n.flag("collidable", false));
    e.setGravity(n.flag("gravity", true));
    e.setGlowing(n.flag("glowing", false));
    e.setSilent(n.flag("silent", true));
    e.customName(lang.render(n.nameKey()));
    e.setCustomNameVisible(n.flag("name-visible", true));
    e.setVisibleByDefault(false);
    if (e instanceof Mannequin m) {
      m.setImmovable(false);
      m.setDescription(
          n.settings().containsKey("description-key")
              ? lang.render(n.option("description-key", ""))
              : null);
      String skin = n.option("skin", "");
      if (!skin.isBlank()) {
        var profile = ResolvableProfile.resolvableProfile();
        try {
          profile.uuid(UUID.fromString(skin));
        } catch (IllegalArgumentException ignored) {
          profile.name(skin);
        }
        m.setProfile(profile.build());
      }
    }
    var scale = e.getAttribute(Attribute.SCALE);
    if (scale != null) scale.setBaseValue(n.number("scale", 1));
    try {
      e.setPose(Pose.valueOf(n.option("pose", "STANDING")), true);
    } catch (IllegalArgumentException ignored) {
    }
    if (e instanceof Ageable age) {
      if (n.flag("baby", false)) age.setBaby();
      else age.setAdult();
    }
    equip(e, n);
  }

  static void equip(LivingEntity e, NpcDefinition n) {
    for (var slot :
        Map.of(
                "main-hand",
                EquipmentSlot.HAND,
                "off-hand",
                EquipmentSlot.OFF_HAND,
                "helmet",
                EquipmentSlot.HEAD,
                "chestplate",
                EquipmentSlot.CHEST,
                "leggings",
                EquipmentSlot.LEGS,
                "boots",
                EquipmentSlot.FEET)
            .entrySet()) {
      String material = n.option(slot.getKey(), "AIR");
      var mat = Material.matchMaterial(material);
      if (mat != null && e.getEquipment() != null) {
        e.getEquipment().setItem(slot.getValue(), new ItemStack(mat));
        // Paper rejects drop chances on non-Mob entities (Mannequin / ArmorStand).
        if (e instanceof Mob) e.getEquipment().setDropChance(slot.getValue(), 0);
      }
    }
  }

  private void tick() {
    if (closed) return;
    tick++;
    plansThisTick = 0;
    capturePaths();
    eligiblePlayers =
        Bukkit.getOnlinePlayers().stream()
            .filter(love::authenticated)
            .filter(p -> !(plugin instanceof LoveEssentialPlugin le) || le.allows("npc", p))
            .filter(p -> !vanished.test(p))
            .toList();
    if (tick % 10 == 0) {
      for (var n : definitions.values()) {
        if (!n.enabled() || !scheduled(n)) continue;
        Location at;
        try {
          at = n.spawn().location();
        } catch (IllegalStateException e) {
          continue;
        }
        double range = n.number("activation-range", 48);
        if (n.flag("personal", false)) {
          for (var p : at.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(at) <= range * range && visible(p, n)) {
              String key = n.id() + ":" + p.getUniqueId();
              if (!live.containsKey(key)
                  && live.size() < plugin.getConfig().getInt("npc.max-active", 200)) {
                var e = spawn(n, p.getUniqueId(), false);
                if (e != null) live.put(key, new Live(key, n, p.getUniqueId(), e, false));
              }
            }
        } else if (!live.containsKey(n.id())
            && live.size() < plugin.getConfig().getInt("npc.max-active", 200)
            && at.getWorld().getPlayers().stream()
                .anyMatch(
                    p -> p.getLocation().distanceSquared(at) <= range * range && visible(p, n))) {
          var e = spawn(n, null, false);
          if (e != null) live.put(n.id(), new Live(n.id(), n, null, e, false));
        }
      }
    }
    var iterator = live.values().iterator();
    while (iterator.hasNext()) {
      Live l = iterator.next();
      var n = definitions.get(l.id);
      if (l.preview) {
        if (!l.entity.isValid()) {
          iterator.remove();
          continue;
        }
        if (l.pauseUntil <= tick) move(l, l.previewDefinition);
        look(l, l.previewDefinition);
        continue;
      }
      if (n == null
          || n.revision() != l.revision
          || !n.enabled()
          || !l.entity.isValid()
          || !scheduled(n)) {
        l.entity.remove();
        iterator.remove();
        continue;
      }
      if (tick % 10 == 0) {
        boolean any = false;
        double range = n.number("activation-range", 48);
        for (var p : Bukkit.getOnlinePlayers()) {
          boolean show =
              p.getWorld().equals(l.entity.getWorld())
                  && p.getLocation().distanceSquared(l.entity.getLocation()) <= range * range
                  && (l.viewer == null || l.viewer.equals(p.getUniqueId()))
                  && visible(p, n);
          if (show) {
            p.showEntity(plugin, l.entity);
            any = true;
          } else p.hideEntity(plugin, l.entity);
        }
        if (!any) {
          l.entity.remove();
          iterator.remove();
          continue;
        }
      }
      if (l.pauseUntil <= tick) move(l, n);
      look(l, n);
    }
  }

  private void look(Live instance, NpcDefinition definition) {
    int interval = Math.max(1, plugin.getConfig().getInt("npc.tracking.update-ticks", 2));
    if (!definition.flag("look-at", true)
        || tick % interval != 0
        || trackingFailures.getOrDefault(instance.key, 0L) > tick) return;
    try {
      Player player = target(instance, definition.number("look-range", 8));
      if (player != null)
        NpcLook.update(
            instance.entity,
            player.getEyeLocation(),
            (float) plugin.getConfig().getDouble("npc.tracking.max-turn-degrees", 30));
    } catch (RuntimeException error) {
      trackingFailures.put(instance.key, tick + 200);
      plugin
          .getLogger()
          .log(
              java.util.logging.Level.WARNING,
              lang.text("logs-ui.npc-tracking-failed") + " [" + definition.id() + "]",
              error);
    }
  }

  private boolean scheduled(NpcDefinition n) {
    if (!n.flag("enabled-hours", false)) return true;
    var world = Bukkit.getWorld(n.spawn().world());
    if (world == null) return false;
    long time = world.getTime();
    double from = n.number("hour-from", 0), to = n.number("hour-to", 23999);
    return from <= to ? time >= from && time <= to : time >= from || time <= to;
  }

  public boolean visible(Player p, NpcDefinition n) {
    return (!(plugin instanceof LoveEssentialPlugin le) || le.allows("npc", p))
        && (!n.flag("require-auth", true) || love.authenticated(p))
        && conditions.test(p, n.visibility()) == Verdict.ALLOW;
  }

  public boolean accessible(Player p, NpcDefinition n) {
    return visible(p, n)
        && conditions.test(p, n.access()) == Verdict.ALLOW
        && p.getWorld().getUID().equals(n.spawn().world());
  }

  private Player target(Live l, double range) {
    Player best = null;
    double distance = range * range;
    Location origin = l.entity.getLocation();
    for (var p : eligiblePlayers) {
      if (!p.isOnline() || !p.getWorld().equals(origin.getWorld())) continue;
      if (l.viewer != null && !l.viewer.equals(p.getUniqueId())) continue;
      double d = p.getLocation().distanceSquared(origin);
      if (d >= distance || !p.canSee(l.entity)) continue;
      if (d < distance) {
        best = p;
        distance = d;
      }
    }
    return best;
  }

  private void move(Live l, NpcDefinition n) {
    if (l.wait > 0) {
      l.wait--;
      return;
    }
    if (l.finished) return;
    var points = n.route().points();
    boolean follow = n.option("movement", "route").equals("follow");
    Location destination;
    Route.Point point = null;
    if (follow) {
      Player p =
          !n.option("follow-player", "").isBlank()
              ? Bukkit.getPlayerExact(n.option("follow-player", ""))
              : target(l, n.number("activation-range", 48));
      if (p == null
          || !p.isOnline()
          || !p.getWorld().equals(l.entity.getWorld())
          || !love.authenticated(p)
          || vanished.test(p)
          || !p.canSee(l.entity)
          || l.viewer != null && !l.viewer.equals(p.getUniqueId())) return;
      destination = p.getLocation();
      if (destination.distanceSquared(l.entity.getLocation())
          <= Math.pow(n.number("follow-distance", 3), 2)) return;
    } else {
      if (points.isEmpty() || n.option("movement", "route").equals("stationary")) return;
      point = points.get(Math.min(l.point, points.size() - 1));
      destination = point.position().location();
      if (!destination.getWorld().equals(l.entity.getWorld())) return;
      Player actor = target(l, n.number("activation-range", 48));
      if (actor == null || conditions.test(actor, point.condition()) != Verdict.ALLOW) return;
      if (destination.distanceSquared(l.entity.getLocation()) <= point.radius() * point.radius()) {
        l.entity.setRotation(point.position().yaw(), point.position().pitch());
        l.wait = point.waitTicks();
        if (!l.preview) actions.execute(actor, point.actions());
        l.path = List.of();
        l.pathIndex = 0;
        switch (n.route().mode()) {
          case ONCE -> {
            if (l.point == points.size() - 1) l.finished = true;
            else l.point++;
          }
          case LOOP -> l.point = (l.point + 1) % points.size();
          case RANDOM -> l.point = random.nextInt(points.size());
          case PING_PONG -> {
            if (points.size() > 1) {
              if (l.point == points.size() - 1) l.direction = -1;
              else if (l.point == 0) l.direction = 1;
              l.point += l.direction;
            }
          }
        }
        return;
      }
    }
    if (l.pathIndex >= l.path.size()) {
      if (!l.planning
          && tick % 10 == 0
          && plansThisTick < plugin.getConfig().getInt("npc.pathfinding.plans-per-tick", 1)) {
        plan(l, n, destination);
        plansThisTick++;
      }
      return;
    }
    var cell = l.path.get(l.pathIndex);
    Location next = new Location(l.entity.getWorld(), cell.x() + .5, cell.y(), cell.z() + .5);
    Location current = l.entity.getLocation();
    var diff = next.toVector().subtract(current.toVector());
    double speed = .10 * (point == null ? n.number("speed", 1) : point.speed());
    if (diff.lengthSquared() < speed * speed) {
      l.pathIndex++;
      return;
    }
    var proposed = current.clone().add(diff.normalize().multiply(speed));
    proposed.setDirection(diff);
    if (cell.y() > current.getY()) proposed.setY(cell.y());
    else if (cell.y() < current.getY()
        && (proposed.getBlockX() != cell.x() || proposed.getBlockZ() != cell.z()))
      proposed.setY(current.getY());
    if (safe(n, proposed) && territory(n, proposed)) {
      openDoor(proposed.getBlock(), n);
      openDoor(proposed.clone().add(0, 1, 0).getBlock(), n);
      l.entity.teleport(proposed);
      l.stuck = 0;
    } else if (++l.stuck > n.number("stuck-ticks", 100)) {
      l.path = List.of();
      l.stuck = 0;
      if (++l.failures >= 3) stuck(l, n);
    }
  }

  private void plan(Live l, NpcDefinition n, Location destination) {
    if (!pathSlots.tryAcquire()) return;
    try {
      int radius = Math.clamp(plugin.getConfig().getInt("npc.pathfinding.radius", 16), 4, 24);
      captures.add(new Capture(l, n, destination, radius));
      l.planning = true;
    } catch (RuntimeException error) {
      pathSlots.release();
      throw error;
    }
  }

  private void capturePaths() {
    long budget =
        (long)
            (Math.clamp(
                    plugin.getConfig().getDouble("npc.pathfinding.capture-budget-ms", 2), 0.25, 10)
                * 1_000_000);
    long deadline = System.nanoTime() + budget;
    while (!captures.isEmpty() && System.nanoTime() < deadline) {
      var c = captures.peek();
      if (live.get(c.live.key) != c.live || !c.live.entity.isValid()) {
        captures.remove();
        c.live.planning = false;
        pathSlots.release();
        continue;
      }
      try {
        int total = c.width * c.width * c.height;
        while (c.cursor < total && System.nanoTime() < deadline) {
          int i = c.cursor++,
              x = c.x + i / (c.width * c.height),
              z = c.z + (i / c.height) % c.width,
              y = c.y + i % c.height;
          if (!c.world.isChunkLoaded(x >> 4, z >> 4)) continue;
          var at = new Location(c.world, x + .5, y, z + .5);
          if (safe(c.definition, at)
              && territory(c.definition, at)
              && (c.world.getBlockAt(x, y - 1, z).getType().isSolid()
                  || !c.definition.flag("avoid-water", true)
                      && c.world.getBlockAt(x, y, z).getType() == Material.WATER)) {
            c.cells.set(i);
            double dx = x - c.target.x(),
                dy = y - c.target.y(),
                dz = z - c.target.z(),
                distance = dx * dx + dy * dy + dz * dz;
            if (distance < c.goalDistance) {
              c.goal = new PathSearch.Cell(x, y, z);
              c.goalDistance = distance;
            }
          }
        }
        if (c.cursor < total) return;
        captures.remove();
        c.cells.set(
            ((c.start.x() - c.x) * c.width + c.start.z() - c.z) * c.height + c.start.y() - c.y);
        var snapshot = new PathSearch.Snapshot(c.x, c.y, c.z, c.width, c.height, c.width, c.cells);
        int limit =
            Math.clamp(plugin.getConfig().getInt("npc.pathfinding.max-nodes", 4096), 64, 16384);
        try {
          paths.execute(
              () -> {
                try {
                  var result = PathSearch.find(snapshot, c.start, c.goal, limit);
                  main.execute(
                      () -> {
                        c.live.planning = false;
                        if (live.get(c.live.key) != c.live || !c.live.entity.isValid()) return;
                        c.live.path = result;
                        c.live.pathIndex = Math.min(1, result.size());
                        if (result.size() < 2) {
                          if (++c.live.failures >= 3) stuck(c.live, c.definition);
                        } else c.live.failures = 0;
                      });
                } finally {
                  pathSlots.release();
                }
              });
        } catch (RejectedExecutionException error) {
          c.live.planning = false;
          pathSlots.release();
        }
      } catch (RuntimeException error) {
        if (captures.peek() == c) captures.remove();
        c.live.planning = false;
        pathSlots.release();
      }
    }
  }

  private boolean territory(NpcDefinition n, Location at) {
    return love.service(dev.lovelace.lovecore.api.territory.TerritoryOracle.class)
        .map(t -> !t.hostileFor(n.owner(), at))
        .orElse(!love.installed("LoveClaims"));
  }

  private void stuck(Live l, NpcDefinition n) {
    switch (n.option("stuck-policy", "PAUSE")) {
      case "SKIP" -> {
        if (!n.route().points().isEmpty()) l.point = (l.point + 1) % n.route().points().size();
        l.failures = 0;
      }
      case "RETURN_HOME" -> {
        var home = n.spawn().location();
        if (n.flag("teleport-on-stuck", false) && safe(n, home) && territory(n, home))
          l.entity.teleport(home);
        l.finished = true;
      }
      default -> l.finished = true;
    }
  }

  private static boolean passable(org.bukkit.block.Block block, NpcDefinition n) {
    if (block.getType() == Material.WATER) return !n.flag("avoid-water", true);
    return block.isPassable()
        || n.flag("can-open-doors", false)
            && block.getBlockData() instanceof org.bukkit.block.data.type.Door;
  }

  private static void openDoor(org.bukkit.block.Block block, NpcDefinition n) {
    if (!n.flag("can-open-doors", false)
        || !(block.getBlockData() instanceof org.bukkit.block.data.type.Door door)) return;
    if (!door.isOpen()) {
      door.setOpen(true);
      block.setBlockData(door);
    }
  }

  private static boolean safe(NpcDefinition n, Location at) {
    World w = at.getWorld();
    if (at.getY() <= w.getMinHeight()
        || at.getY() + 2 >= w.getMaxHeight()
        || !w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return false;
    var feet = w.getBlockAt(at);
    var head = w.getBlockAt(at.clone().add(0, 1, 0));
    return passable(feet, n)
        && passable(head, n)
        && !Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW)
            .contains(feet.getType())
        && !Set.of(
                Material.LAVA,
                Material.FIRE,
                Material.CACTUS,
                Material.MAGMA_BLOCK,
                Material.CAMPFIRE,
                Material.SOUL_CAMPFIRE)
            .contains(w.getBlockAt(at.clone().subtract(0, 1, 0)).getType());
  }

  public void interact(Player p, Entity e) {
    interact(p, e, Trigger.RIGHT_CLICK);
  }

  private void interact(Player p, Entity e, Trigger trigger) {
    String id = identify(e).orElse(null);
    if (id == null) return;
    var n = definitions.get(id);
    if (n == null) return;
    var instance = live.values().stream().filter(l -> l.entity.equals(e)).findFirst().orElse(null);
    if (instance == null
        || instance.preview
        || (instance.viewer != null && !instance.viewer.equals(p.getUniqueId()))) return;
    String mode = n.option("interaction-mode", "BOTH");
    if (mode.equals("RIGHT_CLICK") && trigger != Trigger.RIGHT_CLICK
        || mode.equals("LEFT_CLICK") && trigger != Trigger.LEFT_CLICK) return;
    if (!accessible(p, n)
        || !p.canSee(e)
        || n.flag("line-of-sight", false) && !p.hasLineOfSight(e)
        || p.getLocation().distanceSquared(e.getLocation())
            > Math.pow(n.number("interaction-range", 4), 2)) {
      lang.send(p, "errors.access");
      return;
    }
    long now = System.currentTimeMillis();
    if (now - clicked.getOrDefault(p.getUniqueId(), 0L) < n.number("cooldown-ms", 500)) return;
    clicked.put(p.getUniqueId(), now);
    var event = new NpcInteractEvent(p, n);
    Bukkit.getPluginManager().callEvent(event);
    if (event.isCancelled()) return;
    String soundName = n.option("interaction-sound", "");
    if (!soundName.isBlank()) {
      try {
        p.playSound(
            e.getLocation(),
            Sound.valueOf(soundName.toUpperCase(Locale.ROOT)),
            (float) n.number("interaction-volume", 1),
            (float) n.number("interaction-pitch", 1));
      } catch (IllegalArgumentException ignored) {
        plugin.getLogger().warning(lang.text("logs.npc-sound-failed") + " [" + n.id() + "]");
      }
    }
    if (n.flag("pause-on-interact", true)) instance.pauseUntil = tick + 200;
    love.stat(p, "essential.npc.interactions", 1);
    for (var hook :
        hooks.entrySet().stream()
            .sorted(
                Comparator.<Map.Entry<String, Hook>>comparingInt(h -> h.getValue().priority())
                    .reversed()
                    .thenComparing(Map.Entry::getKey))
            .toList()) {
      try {
        if (hook.getValue().provider().interact(p, n)) return;
      } catch (RuntimeException ex) {
        lang.send(p, "errors.action-unavailable", Map.of("type", hook.getKey()));
        return;
      }
    }
    if (!n.dialog().start().isEmpty()) dialogs.open(p, n);
    else actions.execute(p, n.actions());
  }

  @EventHandler
  public void click(PlayerInteractEntityEvent e) {
    if (identify(e.getRightClicked()).isPresent()) {
      e.setCancelled(true);
      if (e.getHand() == EquipmentSlot.HAND)
        interact(e.getPlayer(), e.getRightClicked(), Trigger.RIGHT_CLICK);
    }
  }

  @EventHandler
  public void damage(EntityDamageEvent e) {
    if (identify(e.getEntity()).isPresent()) {
      var npc = identify(e.getEntity()).flatMap(this::get).orElse(null);
      if (npc == null || npc.flag("invulnerable", true)) e.setCancelled(true);
      if (e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p)
        interact(p, e.getEntity(), Trigger.LEFT_CLICK);
    }
  }

  @EventHandler
  public void death(EntityDeathEvent e) {
    if (identify(e.getEntity()).isPresent()) {
      e.getDrops().clear();
      e.setDroppedExp(0);
    }
  }

  @EventHandler
  public void target(EntityTargetEvent e) {
    if (identify(e.getEntity()).isPresent()) e.setCancelled(true);
  }

  @EventHandler
  public void unload(ChunkUnloadEvent e) {
    live.values()
        .removeIf(
            l -> {
              if (l.entity.getWorld().equals(e.getWorld())
                  && (l.entity.getLocation().getBlockX() >> 4) == e.getChunk().getX()
                  && (l.entity.getLocation().getBlockZ() >> 4) == e.getChunk().getZ()) {
                l.entity.remove();
                return true;
              }
              return false;
            });
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    clicked.remove(e.getPlayer().getUniqueId());
    live.values()
        .removeIf(
            l -> {
              if (e.getPlayer().getUniqueId().equals(l.viewer)) {
                l.entity.remove();
                return true;
              }
              return false;
            });
  }

  public String debug(String id) {
    return live.values().stream()
        .filter(l -> l.id.equals(id))
        .map(
            l ->
                "point="
                    + l.point
                    + ", pending="
                    + l.planning
                    + ", path="
                    + l.pathIndex
                    + "/"
                    + l.path.size()
                    + ", stopped="
                    + l.finished
                    + ", failures="
                    + l.failures)
        .findFirst()
        .orElse("UNLOADED");
  }

  private void remove(String id) {
    live.values()
        .removeIf(
            l -> {
              if (l.id.equals(id)) {
                l.entity.remove();
                return true;
              }
              return false;
            });
    if (dialogs != null) dialogs.invalidate(id);
  }

  @Override
  public void close() {
    closed = true;
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    live.values().forEach(l -> l.entity.remove());
    live.clear();
    clicked.clear();
    eligiblePlayers = List.of();
    trackingFailures.clear();
    spawnFailures.clear();
    cancelCaptures();
    hooks.clear();
    paths.shutdownNow();
  }
}
