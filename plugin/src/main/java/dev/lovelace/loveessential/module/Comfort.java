package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

/** Temporary vehicles are owned, bounded and removed on every exit path. */
public final class Comfort implements Listener, AutoCloseable {
  public static final Set<String> COMMANDS =
      Set.of(
          "sit",
          "lay",
          "ride",
          "eject",
          "hat",
          "traces",
          "heal",
          "feed",
          "fly",
          "speed",
          "gamemode",
          "repair");

  private record Seat(ArmorStand entity, Location stand, Block block) {}

  private record Lying(Location anchor, Block support, Pose previous, boolean fixed) {}

  private record Step(Location at, long time, boolean left) {}

  private final LoveEssentialPlugin plugin;
  private final Lang lang;
  private final LoveBridge love;
  private final MainThread main;
  private final SqliteStore store;
  private final Gameplay gameplay;
  private final Map<UUID, Seat> seats = new HashMap<>();
  private final Map<UUID, Lying> lying = new HashMap<>();
  private final Map<UUID, Entity> rides = new HashMap<>();
  private final NamespacedKey npcKey;
  private final Map<UUID, Step> steps = new HashMap<>();
  private final Map<UUID, Boolean> traces = new HashMap<>();
  private org.bukkit.scheduler.BukkitTask task;

  public Comfort(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      Gameplay gameplay) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.gameplay = gameplay;
    this.npcKey = new NamespacedKey(plugin, "npc");
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getOnlinePlayers().forEach(this::load);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::maintain, 1, 1);
  }

  private void load(Player p) {
    store
        .profile(p.getUniqueId(), "loveessential.traces")
        .whenComplete(
            (v, e) ->
                main.execute(
                    () -> {
                      if (p.isOnline() && e == null)
                        traces.put(
                            p.getUniqueId(),
                            Boolean.parseBoolean(
                                v.orElse(
                                    Boolean.toString(
                                        plugin
                                            .getConfig()
                                            .getBoolean("traces.default-enabled", true)))));
                    }));
  }

  public void command(Player p, String command, String[] args) {
    MainThread.require();
    String module =
        Set.of("sit", "lay", "ride", "eject", "hat").contains(command)
            ? "sitride"
            : command.equals("traces") ? "traces" : "utilities";
    plugin.requireAccess(module, p);
    boolean ejecting =
        command.equals("eject")
            || command.equals("ride")
                && args.length == 1
                && Set.of("eject", "сбросить").contains(args[0].toLowerCase(Locale.ROOT));
    if (!love.authenticated(p)
        || !Set.of("sit", "lay", "ride", "eject", "hat", "traces").contains(command)
            && !p.hasPermission("loveessential." + command))
      throw new IllegalArgumentException("errors.permission");
    switch (command) {
      case "eject" -> {
        if (args.length != 0) throw new IllegalArgumentException("comfort.usage");
        eject(p);
      }
      case "lay" -> {
        if (args.length != 0) throw new IllegalArgumentException("comfort.usage");
        if (lying.containsKey(p.getUniqueId())) unlay(p);
        else lay(p);
      }
      case "sit" -> {
        unlay(p);
        if (seats.containsKey(p.getUniqueId())) {
          release(p, true);
          return;
        }
        if (args.length != 0) throw new IllegalArgumentException("comfort.usage");
        var at = p.getLocation();
        Block block = at.clone().subtract(0, .05, 0).getBlock();
        if (!block.getType().isSolid()
            && block.getType() != Material.SNOW
            && !block.getType().name().endsWith("CARPET"))
          block = at.clone().subtract(0, 1, 0).getBlock();
        sit(p, block, false);
      }
      case "ride" -> {
        if (ejecting) eject(p);
        else {
          unlay(p);
          ride(p, args);
        }
      }
      case "hat" -> hat(p);
      case "traces" -> {
        if (args.length > 1 || args.length == 1 && !Set.of("on", "off").contains(args[0]))
          throw new IllegalArgumentException("comfort.usage");
        boolean value =
            args.length == 0 ? !traces.getOrDefault(p.getUniqueId(), true) : args[0].equals("on");
        store
            .profile(p.getUniqueId(), "loveessential.traces", Boolean.toString(value))
            .whenComplete(
                (v, e) ->
                    main.execute(
                        () -> {
                          if (e != null) lang.send(p, "errors.storage");
                          else {
                            traces.put(p.getUniqueId(), value);
                            lang.send(p, value ? "comfort.traces-on" : "comfort.traces-off");
                          }
                        }));
      }
      case "heal" -> {
        p.setHealth(
            Objects.requireNonNull(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH))
                .getValue());
        p.setFireTicks(0);
        lang.send(p, "comfort.healed");
      }
      case "feed" -> {
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setExhaustion(0);
        lang.send(p, "comfort.fed");
      }
      case "fly" -> {
        boolean on = !p.getAllowFlight();
        p.setAllowFlight(on);
        if (!on) p.setFlying(false);
        lang.send(p, on ? "comfort.fly-on" : "comfort.fly-off");
      }
      case "speed" -> {
        if (args.length != 1) throw new IllegalArgumentException("comfort.usage");
        int value = Integer.parseInt(args[0]);
        if (value < 0 || value > 10) throw new IllegalArgumentException("comfort.usage");
        if (p.isFlying()) p.setFlySpeed(value / 10f);
        else p.setWalkSpeed(value / 10f);
        lang.send(p, "common.done");
      }
      case "gamemode" -> {
        if (args.length != 1) throw new IllegalArgumentException("comfort.usage");
        var mode =
            switch (args[0].toLowerCase(Locale.ROOT)) {
              case "0", "survival", "s" -> GameMode.SURVIVAL;
              case "1", "creative", "c" -> GameMode.CREATIVE;
              case "2", "adventure", "a" -> GameMode.ADVENTURE;
              case "3", "spectator", "sp" -> GameMode.SPECTATOR;
              default -> throw new IllegalArgumentException("comfort.usage");
            };
        p.setGameMode(mode);
        lang.send(p, "common.done");
      }
      case "repair" -> {
        var item = p.getInventory().getItemInMainHand();
        if (!(item.getItemMeta() instanceof Damageable d) || !d.hasDamage())
          throw new IllegalArgumentException("comfort.repair-item");
        d.setDamage(0);
        item.setItemMeta(d);
        lang.send(p, "common.done");
      }
      default -> throw new IllegalArgumentException("errors.input");
    }
  }

  private void hat(Player p) {
    var inv = p.getInventory();
    var hand = inv.getItemInMainHand();
    var helmet = inv.getHelmet();
    if (hand.getType().isAir() || hand.getAmount() != 1)
      throw new IllegalArgumentException("comfort.hat-single");
    if (helmet != null
        && helmet.containsEnchantment(org.bukkit.enchantments.Enchantment.BINDING_CURSE)
        && p.getGameMode() != GameMode.CREATIVE)
      throw new IllegalArgumentException("comfort.hat-binding");
    inv.setHelmet(hand.clone());
    inv.setItemInMainHand(helmet == null ? new ItemStack(Material.AIR) : helmet.clone());
    lang.send(p, "comfort.hat-done");
  }

  public static double surface(Block block, double x, double z) {
    return surface(block.getCollisionShape().getBoundingBoxes(), block.getY(), x, z);
  }

  public static double surface(
      Collection<org.bukkit.util.BoundingBox> boxes, int blockY, double x, double z) {
    double best = Double.NEGATIVE_INFINITY;
    for (var box : boxes)
      if (x >= box.getMinX() - 1e-6
          && x <= box.getMaxX() + 1e-6
          && z >= box.getMinZ() - 1e-6
          && z <= box.getMaxZ() + 1e-6) best = Math.max(best, box.getMaxY());
    if (!Double.isFinite(best)) throw new IllegalArgumentException("comfort.seat-surface");
    return blockY + best;
  }

  public void sit(Player p, Block block, boolean clicked) {
    plugin.requireAccess("sitride", p);
    unlay(p);
    if (p.isInsideVehicle() || p.isFlying() || p.isSwimming() || p.isGliding() || love.combat(p))
      throw new IllegalArgumentException("comfort.seat-state");
    if (!love.territorialAccess(p, block.getLocation()))
      throw new IllegalArgumentException("errors.territory");
    double x = clicked ? .5 : p.getLocation().getX() - block.getX(),
        z = clicked ? .5 : p.getLocation().getZ() - block.getZ();
    if (clicked
        && block.getBlockData() instanceof Stairs stairs
        && stairs.getHalf() == Bisected.Half.BOTTOM) {
      switch (stairs.getFacing()) {
        case NORTH -> z = .8;
        case SOUTH -> z = .2;
        case EAST -> x = .2;
        case WEST -> x = .8;
        default -> {}
      }
    }
    double y = surface(block, x, z);
    var at = new Location(block.getWorld(), block.getX() + x, y, block.getZ() + z, p.getYaw(), 0);
    if (!clear(at, 1.65)) throw new IllegalArgumentException("comfort.seat-space");
    if (seats.values().stream()
        .anyMatch(
            s ->
                s.entity().getWorld().equals(at.getWorld())
                    && s.entity().getLocation().distanceSquared(at) < .25))
      throw new IllegalArgumentException("comfort.seat-occupied");
    // Marker armor stands have zero height. Their passenger attachment is at their origin.
    var seat =
        at.getWorld()
            .spawn(
                at,
                ArmorStand.class,
                s -> {
                  s.setPersistent(false);
                  s.setVisible(false);
                  s.setGravity(false);
                  s.setMarker(true);
                  s.setInvulnerable(true);
                  s.setSilent(true);
                });
    if (!seat.addPassenger(p)) {
      seat.remove();
      throw new IllegalArgumentException("comfort.seat-state");
    }
    seats.put(p.getUniqueId(), new Seat(seat, at.clone(), block));
  }

  private void lay(Player p) {
    if (p.isInsideVehicle()
        || p.isFlying()
        || p.isSwimming()
        || p.isGliding()
        || p.isSleeping()
        || p.hasFixedPose()
        || love.combat(p)
        || !p.getPassengers().isEmpty()) throw new IllegalArgumentException("comfort.seat-state");
    release(p, true);
    var at = p.getLocation();
    Block block = at.clone().subtract(0, .05, 0).getBlock();
    if (block.isPassable()) block = at.clone().subtract(0, 1, 0).getBlock();
    if (!love.territorialAccess(p, block.getLocation()))
      throw new IllegalArgumentException("errors.territory");
    double y = surface(block, at.getX() - block.getX(), at.getZ() - block.getZ());
    at.setY(y);
    if (!clear(at, 1.8)) throw new IllegalArgumentException("comfort.seat-space");
    lying.put(p.getUniqueId(), new Lying(at.clone(), block, p.getPose(), p.hasFixedPose()));
    p.setPose(Pose.SLEEPING, true);
  }

  private void unlay(Player p) {
    var state = lying.remove(p.getUniqueId());
    if (state != null) p.setPose(state.previous(), state.fixed());
  }

  private void eject(Player carrier) {
    boolean found = false;
    for (var passenger : List.copyOf(carrier.getPassengers())) {
      if (passenger instanceof Player rider && rides.get(rider.getUniqueId()) == carrier) {
        if (rider.leaveVehicle()) {
          rides.remove(rider.getUniqueId());
          found = true;
        }
      }
    }
    if (!found) throw new IllegalArgumentException("comfort.no-passenger");
    lang.send(carrier, "comfort.passenger-ejected");
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void hitPassenger(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player carrier
        && e.getEntity() instanceof Player rider
        && rides.get(rider.getUniqueId()) == carrier
        && rider.getVehicle() == carrier) {
      e.setCancelled(true);
      if (rider.leaveVehicle()) rides.remove(rider.getUniqueId());
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void lyingMove(PlayerMoveEvent e) {
    if (e.getTo() == null
        || e instanceof PlayerTeleportEvent
        || !lying.containsKey(e.getPlayer().getUniqueId())) return;
    var anchor = lying.get(e.getPlayer().getUniqueId()).anchor().clone();
    anchor.setYaw(e.getTo().getYaw());
    anchor.setPitch(e.getTo().getPitch());
    e.setTo(anchor);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void standUp(PlayerToggleSneakEvent e) {
    if (e.isSneaking()) unlay(e.getPlayer());
  }

  private void ride(Player p, String[] args) {
    if (rides.containsKey(p.getUniqueId())) {
      p.leaveVehicle();
      rides.remove(p.getUniqueId());
      return;
    }
    if (p.isInsideVehicle() || p.isFlying() || love.combat(p))
      throw new IllegalArgumentException("comfort.seat-state");
    double range = Math.clamp(plugin.getConfig().getDouble("sitride.ride-range", 4), 1, 8);
    Entity target;
    if (args.length == 1) {
      target = Bukkit.getPlayerExact(args[0]);
      if (target == null) throw new IllegalArgumentException("errors.not-found");
    } else if (args.length == 0) {
      var ray =
          p.getWorld()
              .rayTraceEntities(
                  p.getEyeLocation(),
                  p.getEyeLocation().getDirection(),
                  range,
                  .35,
                  e -> e instanceof LivingEntity && !e.equals(p) && !(e instanceof ArmorStand));
      target = ray == null ? null : ray.getHitEntity();
    } else throw new IllegalArgumentException("comfort.usage");
    if (target == null
        || !target.getWorld().equals(p.getWorld())
        || target.getLocation().distanceSquared(p.getLocation()) > range * range
        || !p.hasLineOfSight(target)
        || target instanceof ArmorStand
        || !target.isValid()
        || !target.getPassengers().isEmpty()
        || target.isInsideVehicle()
        || !rideable(target)) throw new IllegalArgumentException("comfort.ride-target");
    if (target instanceof Player other
        && (!p.canSee(other)
            || !love.authenticated(other)
            || gameplay.vanished(other)
            || !plugin.getConfig().getBoolean("sitride.player-riding-enabled", true)
            || love.combat(other)
            || airborne(other))) throw new IllegalArgumentException("comfort.ride-consent");
    if (!love.territorialAccess(p, target.getLocation()))
      throw new IllegalArgumentException("errors.territory");
    if (!target.addPassenger(p)) throw new IllegalArgumentException("comfort.ride-target");
    rides.put(p.getUniqueId(), target);
  }

  /**
   * Players and ground mobs only. Flying mobs would turn /ride into free flight that ignores claim
   * borders, and NPCs are scripted entities that must not be dragged around by a passenger.
   */
  private boolean rideable(Entity target) {
    return !target.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)
        && rideableType(
            target.getType(), plugin.getConfig().getStringList("sitride.ride-denied-types"));
  }

  static boolean rideableType(EntityType type, Collection<String> denied) {
    return type == EntityType.PLAYER || denied.stream().noneMatch(n -> n.equalsIgnoreCase(type.name()));
  }

  /** A flying or gliding carrier would take the rider over claim borders and out of reach. */
  private static boolean airborne(Player carrier) {
    return carrier.isFlying() || carrier.isGliding();
  }

  private static boolean clear(Location at, double height) {
    var body =
        new org.bukkit.util.BoundingBox(
            at.getX() - .29,
            at.getY() + .01,
            at.getZ() - .29,
            at.getX() + .29,
            at.getY() + height,
            at.getZ() + .29);
    for (int x = (int) Math.floor(body.getMinX()); x <= Math.floor(body.getMaxX()); x++)
      for (int y = (int) Math.floor(body.getMinY()); y <= Math.floor(body.getMaxY()); y++)
        for (int z = (int) Math.floor(body.getMinZ()); z <= Math.floor(body.getMaxZ()); z++) {
          var block = at.getWorld().getBlockAt(x, y, z);
          for (var shape : block.getCollisionShape().getBoundingBoxes())
            if (body.overlaps(shape.clone().shift(x, y, z))) return false;
        }
    return true;
  }

  private void release(Player p, boolean restore) {
    var seat = seats.remove(p.getUniqueId());
    if (seat == null) return;
    seat.entity().remove();
    if (restore && p.isOnline()) {
      var at = seat.stand().clone();
      if (clear(at, 1.8)) p.teleport(at);
    }
  }

  private void maintain() {
    for (var entry : List.copyOf(lying.entrySet())) {
      var p = Bukkit.getPlayer(entry.getKey());
      var state = entry.getValue();
      if (p == null) lying.remove(entry.getKey());
      else if (!plugin.environmentAllows("sitride", p)
          || !love.authenticated(p)
          || love.combat(p)
          || state.support().isPassable()
          || !p.getWorld().equals(state.anchor().getWorld())
          || p.isInsideVehicle()) unlay(p);
      else if (p.getPose() != Pose.SLEEPING || !p.hasFixedPose()) p.setPose(Pose.SLEEPING, true);
    }
    for (UUID id : new ArrayList<>(seats.keySet())) {
      var p = Bukkit.getPlayer(id);
      var seat = seats.get(id);
      if (p == null
          || !plugin.environmentAllows("sitride", p)
          || !love.authenticated(p)
          || !seat.entity().isValid()
          || !seat.entity().getPassengers().contains(p)
          || seat.block().isPassable()
          || love.combat(p)) {
        if (p != null) release(p, true);
        else {
          seats.remove(id);
          seat.entity().remove();
        }
      }
    }
    rides
        .entrySet()
        .removeIf(
            e -> {
              var p = Bukkit.getPlayer(e.getKey());
              var target = e.getValue();
              boolean stop =
                  p == null
                      || !plugin.environmentAllows("sitride", p)
                      || !target.isValid()
                      || !target.getPassengers().contains(p)
                      || !love.authenticated(p)
                      || love.combat(p)
                      || !love.territorialAccess(p, target.getLocation())
                      || target instanceof Player other
                          && (!love.authenticated(other)
                              || !plugin
                                  .getConfig()
                                  .getBoolean("sitride.player-riding-enabled", true)
                              || gameplay.vanished(other)
                              || love.combat(other)
                              || airborne(other));
              if (stop && p != null && target.getPassengers().contains(p)) p.leaveVehicle();
              return stop;
            });
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void click(PlayerInteractEvent e) {
    var p = e.getPlayer();
    var b = e.getClickedBlock();
    if (!plugin.ready()
        || !plugin.allows("sitride", p)
        || !plugin.getConfig().getBoolean("sitride.click-to-sit", true)
        || e.getAction() != Action.RIGHT_CLICK_BLOCK
        || e.getHand() != EquipmentSlot.HAND
        || b == null
        || p.isSneaking()
        || !p.getInventory().getItemInMainHand().getType().isAir()
        || !love.authenticated(p)) return;
    String type = b.getType().name();
    if (!(type.endsWith("_STAIRS") || type.endsWith("_SLAB") || type.endsWith("CARPET"))) return;
    try {
      sit(p, b, true);
      e.setCancelled(true);
    } catch (IllegalArgumentException ex) {
      lang.send(p, ex.getMessage());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    var p = e.getPlayer();
    var to = e.getTo();
    if (to == null
        || e instanceof PlayerTeleportEvent
        || !e.getFrom().getWorld().equals(to.getWorld())
        || e.getFrom().distanceSquared(to) < .0001) return;
    if (!plugin.allows("traces", p)
        || !traces.getOrDefault(p.getUniqueId(), false)
        || !love.authenticated(p)
        || gameplay.vanished(p)
        || p.isInsideVehicle()
        || p.isFlying()
        || p.isSwimming()
        || p.isGliding()
        || !p.isOnGround()) return;
    long now = System.currentTimeMillis();
    var old = steps.get(p.getUniqueId());
    double stride = Math.clamp(plugin.getConfig().getDouble("traces.stride", .65), .2, 3);
    if (old != null
        && old.at().getWorld().equals(to.getWorld())
        && (now - old.time()
                < Math.clamp(plugin.getConfig().getLong("traces.interval-ms", 180), 50, 5000)
            || old.at().distanceSquared(to) < stride * stride)) return;
    boolean left = old == null || !old.left();
    steps.put(p.getUniqueId(), new Step(to.clone(), now, left));
    var ground = to.clone().subtract(0, .01, 0).getBlock();
    if (ground.isLiquid()) return;
    double y;
    try {
      y = surface(ground, to.getX() - ground.getX(), to.getZ() - ground.getZ());
    } catch (IllegalArgumentException ex) {
      return;
    }
    double angle = Math.toRadians(to.getYaw());
    double offset = left ? .13 : -.13;
    var at = to.clone();
    at.setY(y + .025);
    at.add(Math.cos(angle) * offset, 0, Math.sin(angle) * offset);
    int rgb;
    try {
      rgb = Integer.parseInt(plugin.getConfig().getString("traces.color", "78644B"), 16);
    } catch (NumberFormatException ex) {
      rgb = 0x78644B;
    }
    var dust = new Particle.DustOptions(Color.fromRGB(rgb & 0xffffff), .45f);
    double radius = Math.clamp(plugin.getConfig().getDouble("traces.view-distance", 24), 4, 64);
    for (var viewer : p.getWorld().getPlayers())
      if (viewer.canSee(p)
          && love.authenticated(viewer)
          && viewer.getLocation().distanceSquared(at) < radius * radius)
        viewer.spawnParticle(Particle.DUST, at, 2, .025, 0, .025, 0, dust);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    load(e.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void dismount(EntityDismountEvent e) {
    if (e.getEntity() instanceof Player p && seats.containsKey(p.getUniqueId())) {
      var expected = seats.get(p.getUniqueId());
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (seats.get(p.getUniqueId()) == expected) release(p, true);
              });
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    if (e.getEntity() instanceof Player p) {
      release(p, true);
      unlay(p);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent e) {
    unlay(e.getPlayer());
    release(e.getPlayer(), false);
    steps.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    unlay(e.getPlayer());
    release(e.getPlayer(), false);
    for (var rider : List.copyOf(e.getPlayer().getPassengers())) {
      if (rider instanceof Player && rides.get(rider.getUniqueId()) == e.getPlayer()) {
        rider.leaveVehicle();
        rides.remove(rider.getUniqueId());
      }
    }
    rides.remove(e.getPlayer().getUniqueId());
    steps.remove(e.getPlayer().getUniqueId());
    traces.remove(e.getPlayer().getUniqueId());
  }

  public void close() {
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    for (UUID id : List.copyOf(lying.keySet())) {
      var p = Bukkit.getPlayer(id);
      if (p != null) unlay(p);
    }
    lying.clear();
    seats.values().forEach(s -> s.entity().remove());
    seats.clear();
    rides.forEach(
        (id, target) -> {
          var p = Bukkit.getPlayer(id);
          if (p != null && target.getPassengers().contains(p)) p.leaveVehicle();
        });
    rides.clear();
    steps.clear();
    traces.clear();
  }
}
