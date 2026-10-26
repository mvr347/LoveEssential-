package dev.lovelace.loveessential.module;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import dev.lovelace.lovecore.api.revive.DownedOracle;
import dev.lovelace.lovecore.api.social.ProfileOracle;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.api.Action;
import dev.lovelace.loveessential.api.revive.*;
import dev.lovelace.loveessential.condition.ActionEngine;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * A deadly blow does not kill: the player falls, crawls and waits to be revived, carried or
 * searched. Reverse-engineered from ReviveMe's documented behaviour, with two deliberate
 * differences: nobody is made invulnerable through the entity flag (it is saved in the player data
 * and once left players immortal after quitting), and the final death is an ordinary {@code
 * setHealth(0)}, so drops, keep-inventory and death messages follow the server rules.
 *
 * <p>Everything runs on the server thread. The module is off by default; every handler returns at
 * once while it is disabled.
 */
public final class Revive implements Listener, AutoCloseable, DownedOracle {
  public static final String ID = "loveessential";
  private static final double STEP = 0.1;
  private static final int BAR = 16;

  private final LoveEssentialPlugin plugin;
  private final Lang lang;
  private final LoveBridge love;
  private final ActionEngine actions;
  private final InventoryInspector inventories;
  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> carrying = new HashMap<>();
  private final Map<UUID, UUID> interactionTargets = new HashMap<>();
  private final Map<UUID, DownedDeathEvent.Cause> dying = new HashMap<>();
  private final Map<UUID, Integer> downs = new HashMap<>();
  private final Map<UUID, Long> lastStart = new HashMap<>();
  private final Map<UUID, Long> notified = new HashMap<>();
  private final Map<UUID, Long> recoveryUntil = new HashMap<>();
  private final Set<UUID> dropping = new HashSet<>();
  private final NamespacedKey slowKey, jumpKey, carryKey, pending;
  private BukkitTask task;
  private long ticks;

  private static final class State {
    final UUID id;

    /** Game time of the fall, in revive ticks: real time would drift from the game under lag. */
    final long since;

    double remaining, invulnerable, progress, suicide;
    volatile UUID reviver;
    volatile Map<String, String> placeholderValues = Map.of();
    UUID carrier;
    Runnable refund;
    EntityDamageEvent cause;
    BossBar bar;
    int shownSecond = -1;
    Block crawlCeiling;
    Interaction interaction;
    final Map<UUID, Integer> lastInteractions = new HashMap<>();

    State(UUID id, long since) {
      this.id = id;
      this.since = since;
    }
  }

  public Revive(
      LoveEssentialPlugin plugin,
      Lang lang,
      LoveBridge love,
      ActionEngine actions,
      InventoryInspector inventories) {
    this.plugin = plugin;
    this.lang = lang;
    this.love = love;
    this.actions = actions;
    this.inventories = inventories;
    slowKey = new NamespacedKey(plugin, "downed_slow");
    jumpKey = new NamespacedKey(plugin, "downed_jump");
    carryKey = new NamespacedKey(plugin, "downed_carry");
    pending = new NamespacedKey(plugin, "downed_pending");
  }

  public void start() {
    dev.lovelace.loveessential.MainThread.require();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2, 2);
  }

  @Override
  public void close() {
    dev.lovelace.loveessential.MainThread.require();
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    recoveryUntil.clear();
    // A shutdown is not a death: release everybody and mark them so health is fixed on next join.
    for (var id : List.copyOf(states.keySet())) {
      var p = Bukkit.getPlayer(id);
      var state = states.remove(id);
      if (state != null) removeInteraction(state);
      if (p != null && state != null) {
        p.getPersistentDataContainer().set(pending, PersistentDataType.BYTE, (byte) 1);
        refund(state);
        release(p, state);
      }
    }
  }

  /**
   * Rejects an unknown pose, an unreadable sound line or a sound that does not exist, naming the
   * key.
   */
  public static void validate(org.bukkit.configuration.ConfigurationSection c) {
    String pose = c.getString("revive.pose", "SLEEPING");
    if (!pose.equalsIgnoreCase("SLEEPING") && !pose.equalsIgnoreCase("SWIMMING"))
      throw new dev.lovelace.loveessential.config.ConfigError("revive.pose: SLEEPING или SWIMMING");
    var sounds = c.getConfigurationSection("revive.sounds");
    if (sounds == null) return;
    for (String name : sounds.getKeys(false)) {
      String key = "revive.sounds." + name;
      ReviveRules.Cue cue;
      try {
        cue = ReviveRules.cue(sounds.getString(name, ""));
      } catch (IllegalArgumentException e) {
        throw new dev.lovelace.loveessential.config.ConfigError(
            key + ": ожидается «название громкость высота», высота 0.5–2.0, громкость 0–10");
      }
      if (cue != null && SoundNames.find(cue.name()) == null)
        throw new dev.lovelace.loveessential.config.ConfigError(
            key + ": неизвестный звук «" + cue.name() + "»");
    }
  }

  // ---------------------------------------------------------------- settings

  private boolean enabled() {
    return plugin.module("revive");
  }

  private String key(String path) {
    return "revive." + path;
  }

  private double number(String path, double fallback, double min, double max) {
    return Math.clamp(plugin.getConfig().getDouble(key(path), fallback), min, max);
  }

  private boolean flag(String path, boolean fallback) {
    return plugin.getConfig().getBoolean(key(path), fallback);
  }

  /** Pose sent to other players; the local client also needs a low ceiling to stay crawling. */
  private Pose pose() {
    return "SWIMMING".equalsIgnoreCase(plugin.getConfig().getString(key("pose"), "SLEEPING"))
        ? Pose.SWIMMING
        : Pose.SLEEPING;
  }

  private boolean blocked(String action) {
    return plugin.getConfig().getStringList(key("blocked-actions")).contains(action);
  }

  // ------------------------------------------------------------------ oracle

  @Override
  public String providerId() {
    return ID;
  }

  @Override
  public boolean downed(UUID playerId) {
    return states.containsKey(playerId);
  }

  @Override
  public boolean reviving(UUID playerId) {
    var state = states.get(playerId);
    return state != null && state.reviver != null;
  }

  /** Values for PlaceholderAPI: {@code %loveessential_revive_status%} and friends. */
  public Map<String, String> values(UUID id) {
    var state = states.get(id);
    if (state == null)
      return Map.of(
          "revive_status", lang.text("revive.status-none"),
          "revive_death_delay", "0",
          "revive_invulnerability", "0",
          "revive_reviver", "",
          "revive_suicide", "0",
          "revive_bar", "");
    return state.placeholderValues;
  }

  /** Captured on the server thread; PAPI may read the immutable result from async chat. */
  private void publishValues(State state) {
    dev.lovelace.loveessential.MainThread.require();
    var reviver = state.reviver == null ? null : Bukkit.getPlayer(state.reviver);
    state.placeholderValues =
        Map.of(
            "revive_status", lang.text("revive.status-downed"),
            "revive_death_delay", Integer.toString((int) Math.ceil(state.remaining)),
            "revive_invulnerability",
                Integer.toString((int) Math.ceil(Math.max(0, state.invulnerable))),
            "revive_reviver", reviver == null ? "" : reviver.getName(),
            "revive_suicide",
                Integer.toString((int) Math.round(100 * state.suicide / suicideCount())),
            "revive_bar", plainBar(state.progress / required()));
  }

  private static String plainBar(double fraction) {
    int filled = (int) Math.round(Math.clamp(fraction, 0, 1) * BAR);
    return "█".repeat(filled) + "░".repeat(BAR - filled);
  }

  private static Component bar(double fraction) {
    int filled = (int) Math.round(Math.clamp(fraction, 0, 1) * BAR);
    return Component.text("█".repeat(filled), NamedTextColor.GREEN)
        .append(Component.text("░".repeat(BAR - filled), NamedTextColor.DARK_GRAY));
  }

  private double required() {
    return number("reliving-count", 10, 1, 600);
  }

  private double suicideCount() {
    return number("suicide-count", 5, 1, 600);
  }

  // ------------------------------------------------------------------ feedback

  /**
   * Plays {@code revive.sounds.<name>} at a place; {@code pitchAdd} raises it, for a rising tick.
   */
  private void cue(String name, Location at, float pitchAdd) {
    if (!flag("display.sounds", true) || at.getWorld() == null) return;
    ReviveRules.Cue cue;
    try {
      cue = ReviveRules.cue(plugin.getConfig().getString(key("sounds." + name), ""));
    } catch (IllegalArgumentException e) {
      return;
    }
    var sound = cue == null ? null : SoundNames.find(cue.name());
    if (sound != null)
      at.getWorld().playSound(at, sound, cue.volume(), Math.clamp(cue.pitch() + pitchAdd, .5f, 2f));
  }

  private void cue(String name, Location at) {
    cue(name, at, 0f);
  }

  /**
   * The action bar of a downed player. It is sent every step because the client drops it after a
   * few seconds, and the choice is made again every step, so when a revive is interrupted or the
   * player starts giving up the old text is replaced at once.
   */
  private void status(Player p, State state) {
    boolean canGiveUp = (ticks - state.since) * STEP >= number("suicide-delay", 5, 0, 600);
    switch (ReviveRules.bar(
        state.reviver != null, state.carrier != null, state.suicide, canGiveUp)) {
      case SUICIDE ->
          p.sendActionBar(
              lang.renderText(
                  lang.text("revive.action-suicide"),
                  Map.of(),
                  Map.of("bar", bar(state.suicide / suicideCount()))));
      case REVIVING -> {
        var helper = state.reviver == null ? null : Bukkit.getPlayer(state.reviver);
        if (helper != null)
          p.sendActionBar(
              lang.renderText(
                  lang.text("revive.bar-reviving"),
                  Map.of("reviver", helper.getName()),
                  Map.of("bar", bar(state.progress / required()))));
      }
      case CARRIED -> {
        var carrier = state.carrier == null ? null : Bukkit.getPlayer(state.carrier);
        if (carrier != null)
          p.sendActionBar(
              lang.render("revive.action-carried", Map.of("carrier", carrier.getName())));
      }
      case GIVE_UP -> p.sendActionBar(lang.render("revive.action-giveup"));
      case WAIT ->
          p.sendActionBar(
              lang.render(
                  "revive.action-wait",
                  Map.of(
                      "seconds",
                      Long.toString(
                          Math.max(
                              1,
                              (long)
                                  Math.ceil(
                                      number("suicide-delay", 5, 0, 600)
                                          - (ticks - state.since) * STEP))))));
    }
  }

  // ------------------------------------------------------------------ falling

  private boolean eligible(Player p, EntityDamageEvent e) {
    if (!enabled()
        || !plugin.allows("revive", p)
        || !p.hasPermission("loveessential.revive.downable")
        || p.isInvulnerable()
        || p.getGameMode() == GameMode.CREATIVE
        || p.getGameMode() == GameMode.SPECTATOR
        || e != null
            && plugin
                .getConfig()
                .getStringList(key("instant-kill-causes"))
                .contains(e.getCause().name())) return false;
    if (love.service(dev.lovelace.lovecore.api.auth.AuthOracle.class).isPresent()
        && !love.authenticated(p)) return false;
    if (flag("totem-first", true)
        && (p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
            || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING))
      return false;
    int max = (int) number("max-downs-per-life", 0, 0, 1000);
    if (max > 0 && downs.getOrDefault(p.getUniqueId(), 0) >= max) return false;
    double range = number("nearby-check-range", 0, 0, 256);
    if (range > 0
        && p.getWorld().getNearbyPlayers(p.getLocation(), range).stream()
            .noneMatch(
                o ->
                    !o.equals(p)
                        && !states.containsKey(o.getUniqueId())
                        && o.getGameMode() != GameMode.SPECTATOR)) return false;
    return true;
  }

  private boolean lethal(Player p, EntityDamageEvent e) {
    return p.getHealth() + p.getAbsorptionAmount() - e.getFinalDamage() <= 0;
  }

  /** Puts the player on the ground. Returns false when a listener refused. */
  private boolean down(Player p, EntityDamageEvent cause) {
    var event = new DownedEvent(p, cause);
    Bukkit.getPluginManager().callEvent(event);
    if (event.isCancelled()) return false;
    if (cause != null) cause.setCancelled(true);
    var state = new State(p.getUniqueId(), ticks);
    state.cause = cause;
    state.remaining = number("death-delay", 75, 1, 3600);
    state.invulnerable = number("invulnerability-delay", 15, 0, 600);
    publishValues(state);
    states.put(p.getUniqueId(), state);
    downs.merge(p.getUniqueId(), 1, Integer::sum);
    var max = p.getAttribute(Attribute.MAX_HEALTH);
    p.setHealth(Math.min(number("downed-health", 1, 0.5, 40), max == null ? 20 : max.getValue()));
    p.setFireTicks(0);
    p.setFallDistance(0);
    apply(p, state);
    clearMobTargets(p);
    p.showTitle(
        Title.title(
            lang.render("revive.title"),
            lang.render("revive.subtitle"),
            Title.Times.times(
                Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
    cue("down", p.getLocation());
    fire("down", p, null);
    return true;
  }

  private void apply(Player p, State state) {
    modifier(p, Attribute.MOVEMENT_SPEED, slowKey, number("crawl-speed", .25, 0, 1) - 1);
    modifier(p, Attribute.JUMP_STRENGTH, jumpKey, -1);
    p.setSprinting(false);
    p.setGliding(false);
    updatePose(p, state);
    updateInteraction(p, state);
    p.closeInventory();
    if (flag("display.bossbar", true)) {
      state.bar =
          BossBar.bossBar(bossTitle(state), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
      p.showBossBar(state.bar);
    }
  }

  /**
   * The client calculates its own pose and ignores a forced sleeping metadata pose on dry land. A
   * private ceiling makes it use vanilla crawling without changing any real world blocks.
   */
  private void updatePose(Player p, State state) {
    if (p.getPose() != pose() || !p.hasFixedPose()) p.setPose(pose(), true);
    if (!p.isSwimming()) p.setSwimming(true);
    Block next = state.carrier == null ? p.getLocation().getBlock().getRelative(0, 1, 0) : null;
    if (next != null && !next.getType().isAir()) next = null;
    if (!Objects.equals(next, state.crawlCeiling)) {
      restoreCeiling(p, state);
      state.crawlCeiling = next;
      if (next != null) p.sendBlockChange(next.getLocation(), Material.BARRIER.createBlockData());
    } else if (next != null && ticks % 10 == 0) {
      // Chunk refreshes and real block updates can overwrite a client-only block.
      p.sendBlockChange(next.getLocation(), Material.BARRIER.createBlockData());
    }
  }

  private void restoreCeiling(Player p, State state) {
    if (state.crawlCeiling == null) return;
    Block old = state.crawlCeiling;
    state.crawlCeiling = null;
    if (p.getWorld().equals(old.getWorld())
        && old.getWorld().isChunkLoaded(old.getX() >> 4, old.getZ() >> 4))
      p.sendBlockChange(old.getLocation(), old.getBlockData());
  }

  private Location bodyCenter(Player p, State state) {
    var at = p.getLocation();
    if (state.carrier == null && pose() == Pose.SLEEPING) {
      var facing = at.clone();
      facing.setPitch(0);
      at.add(facing.getDirection().multiply(number("interaction.forward-offset", .65, -2, 2)));
    }
    return at;
  }

  private void updateInteraction(Player p, State state) {
    Location at = bodyCenter(p, state);
    if (state.interaction == null || !state.interaction.isValid()) {
      removeInteraction(state);
      state.interaction =
          p.getWorld()
              .spawn(
                  at,
                  Interaction.class,
                  entity -> {
                    entity.setInteractionWidth((float) number("interaction.width", 1.6, .6, 3));
                    entity.setInteractionHeight((float) number("interaction.height", .9, .3, 2));
                    entity.setResponsive(true);
                    entity.setPersistent(false);
                  });
      interactionTargets.put(state.interaction.getUniqueId(), p.getUniqueId());
    } else if (!state.interaction.getLocation().equals(at)) {
      state.interaction.teleport(at);
    }
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      boolean visible =
          !viewer.equals(p) && viewer.getWorld().equals(p.getWorld()) && viewer.canSee(p);
      if (visible && !viewer.canSee(state.interaction))
        viewer.showEntity(plugin, state.interaction);
      else if (!visible && viewer.canSee(state.interaction))
        viewer.hideEntity(plugin, state.interaction);
    }
  }

  private void removeInteraction(State state) {
    if (state.interaction == null) return;
    interactionTargets.remove(state.interaction.getUniqueId());
    state.interaction.remove();
    state.interaction = null;
  }

  private Player interactionTarget(Entity entity) {
    if (entity instanceof Player p) return p;
    UUID id = interactionTargets.get(entity.getUniqueId());
    return id == null ? null : Bukkit.getPlayer(id);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void attackInteraction(PrePlayerAttackEntityEvent e) {
    if (!enabled() || (e.willAttack() && e.isCancelled())) return;
    if (!interactionTargets.containsKey(e.getAttacked().getUniqueId())) return;
    Player target = interactionTarget(e.getAttacked());
    e.setCancelled(true);
    if (target == null
        || target.equals(e.getPlayer())
        || !states.containsKey(target.getUniqueId())
        || !e.getPlayer().canSee(target)
        || e.getPlayer().getWorld() != target.getWorld()
        || !e.getPlayer().hasLineOfSight(target)
        || e.getPlayer().getLocation().distanceSquared(target.getLocation()) > 36) return;
    // Interaction entities are not damageable (willAttack=false and initially cancelled).
    // A real attack preserves weapon damage, cooldown, enchants and ordinary PvP protection events.
    e.getPlayer().attack(target);
  }

  private static void modifier(Player p, Attribute attribute, NamespacedKey key, double amount) {
    AttributeInstance instance = p.getAttribute(attribute);
    if (instance == null) return;
    instance.removeModifier(key);
    instance.addTransientModifier(
        new AttributeModifier(
            key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
  }

  private Component bossTitle(State state) {
    return lang.render(
        "revive.bar-downed", Map.of("seconds", Integer.toString((int) Math.ceil(state.remaining))));
  }

  /** Removes every effect of the downed state from the player; the state itself is already gone. */
  private void release(Player p, State state) {
    inventories.invalidate(p);
    removeInteraction(state);
    for (var entry :
        Map.of(Attribute.MOVEMENT_SPEED, slowKey, Attribute.JUMP_STRENGTH, jumpKey).entrySet()) {
      AttributeInstance instance = p.getAttribute(entry.getKey());
      if (instance != null) instance.removeModifier(entry.getValue());
    }
    restoreCeiling(p, state);
    p.setSwimming(false);
    p.setPose(Pose.STANDING, false);
    if (state.bar != null) p.hideBossBar(state.bar);
    if (state.carrier != null) {
      var carrier = Bukkit.getPlayer(state.carrier);
      carrying.remove(state.carrier);
      if (carrier != null) {
        detach(carrier, p);
        unslow(carrier);
        carrier.sendActionBar(Component.empty());
      }
    }
    if (state.reviver != null) {
      var helper = Bukkit.getPlayer(state.reviver);
      if (helper != null) helper.sendActionBar(Component.empty());
    }
    p.sendActionBar(Component.empty());
  }

  private void unslow(Player carrier) {
    AttributeInstance instance = carrier.getAttribute(Attribute.MOVEMENT_SPEED);
    if (instance != null) instance.removeModifier(carryKey);
  }

  private void detach(Player carrier, Player downed) {
    dropping.add(downed.getUniqueId());
    try {
      carrier.removePassenger(downed);
    } finally {
      dropping.remove(downed.getUniqueId());
    }
  }

  // --------------------------------------------------------------------- tick

  private void tick() {
    ticks++;
    recoveryUntil.values().removeIf(until -> until <= ticks);
    if (!enabled()) {
      recoveryUntil.clear();
      for (var id : List.copyOf(states.keySet())) {
        var p = Bukkit.getPlayer(id);
        var state = states.remove(id);
        if (state != null) removeInteraction(state);
        if (p != null && state != null) {
          refund(state);
          release(p, state);
        }
      }
      return;
    }
    boolean second = ticks % 10 == 0;
    for (var state : List.copyOf(states.values())) {
      var p = Bukkit.getPlayer(state.id);
      if (p == null || !p.isOnline() || p.isDead()) {
        states.remove(state.id);
        removeInteraction(state);
        continue;
      }
      updatePose(p, state);
      updateInteraction(p, state);
      if (p.isGliding()) p.setGliding(false);
      state.invulnerable = Math.max(0, state.invulnerable - STEP);
      if (!checkCarrier(p, state)) continue;
      revive(p, state);
      if (!states.containsKey(state.id)) continue;
      suicide(p, state);
      if (!states.containsKey(state.id)) continue;
      boolean paused =
          ReviveRules.paused(
              state.carrier != null,
              state.reviver != null,
              flag("carry.pause-timer", true),
              flag("revive-pause-timer", true));
      if (!paused) state.remaining -= STEP;
      int left = (int) Math.ceil(state.remaining);
      if (second) {
        Bukkit.getPluginManager().callEvent(new DownedDelayCountEvent(p, Math.max(0, left)));
        if (flag("display.particles", true))
          p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.2, 0), 1);
        hint(p);
      }
      if (state.bar != null && left != state.shownSecond) {
        state.shownSecond = left;
        state.bar.name(bossTitle(state));
        state.bar.progress(
            (float) Math.clamp(state.remaining / number("death-delay", 75, 1, 3600), 0, 1));
      }
      publishValues(state);
      status(p, state);
      if (state.remaining <= 0) expire(p, state);
    }
  }

  private void hint(Player downed) {
    double radius = number("display.hint-radius", 8, 0, 64);
    if (radius <= 0) return;
    var message = lang.render("revive.hint-nearby", Map.of("downed", downed.getName()));
    for (var near : downed.getWorld().getNearbyPlayers(downed.getLocation(), radius))
      if (!near.equals(downed)
          && !states.containsKey(near.getUniqueId())
          && !near.getUniqueId().equals(states.get(downed.getUniqueId()).reviver)
          && near.hasPermission("loveessential.revive.reviver")) near.sendActionBar(message);
  }

  private void expire(Player p, State state) {
    if ("revive".equalsIgnoreCase(plugin.getConfig().getString(key("death-count-action"), "kill")))
      complete(p, state, null);
    else if (!kill(p, DownedDeathEvent.Cause.TIMER)) state.remaining = 1;
  }

  // ------------------------------------------------------------------ reviving

  private boolean checkCarrier(Player p, State state) {
    if (state.carrier == null) return true;
    var carrier = Bukkit.getPlayer(state.carrier);
    if (carrier == null
        || !carrier.isOnline()
        || carrier.isDead()
        || !carrier.getPassengers().contains(p)) {
      carrying.remove(state.carrier);
      state.carrier = null;
      restoreAfterDrop(p, state);
      if (carrier != null) {
        unslow(carrier);
        carrier.sendActionBar(Component.empty());
      }
      cue("drop", p.getLocation());
      return true;
    }
    carrier.sendActionBar(lang.render("revive.action-carry", Map.of("downed", p.getName())));
    return true;
  }

  private void revive(Player p, State state) {
    Player helper = state.reviver == null ? null : Bukkit.getPlayer(state.reviver);
    if (helper != null && !stillWorking(helper, p)) {
      stop(p, state, helper);
      helper = null;
    }
    if (helper == null && state.carrier == null) {
      var candidate = nearest(p);
      if (candidate != null) helper = begin(p, state, candidate);
    }
    if (helper == null) {
      state.progress = 0;
      return;
    }
    double required = required();
    state.progress += STEP * speed(helper);
    Bukkit.getPluginManager().callEvent(new RevivingEvent(p, helper, state.progress, required));
    var bar = bar(state.progress / required);
    var percent =
        Integer.toString((int) Math.min(100, Math.round(100 * state.progress / required)));
    helper.sendActionBar(
        lang.renderText(
            lang.text("revive.action-reviving"),
            Map.of("downed", p.getName(), "percent", percent),
            Map.of("bar", bar)));
    if (ticks % 10 == 0)
      cue("tick", p.getLocation(), (float) Math.min(1, state.progress / required) * .8f);
    if (state.progress >= required) complete(p, state, helper);
  }

  private boolean stillWorking(Player helper, Player downed) {
    return helper.isOnline()
        && !helper.isDead()
        && helper.isSneaking()
        && !states.containsKey(helper.getUniqueId())
        && helper.getWorld().equals(downed.getWorld())
        && helper.getLocation().distance(downed.getLocation())
            <= number("reliving-distance-max", 5, 1, 32);
  }

  private Player nearest(Player downed) {
    double min = number("reliving-distance-min", 1.3, .5, 16);
    Player best = null;
    double bestDistance = Double.MAX_VALUE;
    for (var o : downed.getWorld().getNearbyPlayers(downed.getLocation(), min)) {
      double distance = o.getLocation().distance(downed.getLocation());
      if (o.equals(downed)
          || !o.isSneaking()
          || o.getGameMode() == GameMode.SPECTATOR
          || states.containsKey(o.getUniqueId())
          || !o.hasPermission("loveessential.revive.reviver")
          || distance > min
          || distance >= bestDistance) continue;
      best = o;
      bestDistance = distance;
    }
    return best;
  }

  private double speed(Player helper) {
    double best = 1;
    var ranks = plugin.getConfig().getConfigurationSection(key("ranks"));
    if (ranks != null)
      for (var rank : ranks.getKeys(false))
        if (helper.hasPermission("loveessential.revive.speed." + rank))
          best = Math.max(best, Math.clamp(ranks.getDouble(rank, 1), .1, 20));
    return best;
  }

  /** Starts reviving unless something forbids it; tells the helper why, at most every 3 seconds. */
  private Player begin(Player downed, State state, Player helper) {
    String refusal = refusal(downed, helper);
    if (refusal != null) {
      long now = System.currentTimeMillis();
      if (now - notified.getOrDefault(helper.getUniqueId(), 0L) > 3000) {
        notified.put(helper.getUniqueId(), now);
        helper.sendActionBar(lang.render(refusal, refusalVars(helper)));
      }
      return null;
    }
    var event = new StartRevivingEvent(downed, helper);
    Bukkit.getPluginManager().callEvent(event);
    if (event.isCancelled()) return null;
    state.refund = charge(helper);
    state.reviver = helper.getUniqueId();
    state.progress = 0;
    lastStart.put(helper.getUniqueId(), System.currentTimeMillis());
    cue("start", downed.getLocation());
    fire("start", downed, helper);
    return helper;
  }

  private Map<String, String> refusalVars(Player helper) {
    long wait =
        (long) number("start-cooldown", 0, 0, 3600) * 1000
            - (System.currentTimeMillis() - lastStart.getOrDefault(helper.getUniqueId(), 0L));
    return Map.of("seconds", Long.toString(Math.max(1, wait / 1000)), "price", priceText());
  }

  private String refusal(Player downed, Player helper) {
    if (flag("restrictions.deny-in-combat", false) && love.combat(helper))
      return "revive.refused-combat";
    if (flag("restrictions.clan-only", false)
        && !love.service(ProfileOracle.class)
            .map(o -> o.areClanmates(downed.getUniqueId(), helper.getUniqueId()))
            .orElse(true)) return "revive.refused-clan";
    if (flag("restrictions.respect-claims", false)
        && !love.territorialAccess(helper, downed.getLocation())) return "revive.refused-claim";
    long cooldown = (long) number("start-cooldown", 0, 0, 3600) * 1000;
    if (cooldown > 0
        && System.currentTimeMillis() - lastStart.getOrDefault(helper.getUniqueId(), 0L) < cooldown)
      return "revive.refused-cooldown";
    if (!canPay(helper)) return "revive.no-funds";
    return null;
  }

  private void stop(Player downed, State state, Player helper) {
    Bukkit.getPluginManager().callEvent(new StopRevivingEvent(downed, helper));
    if (flag("price.refund-on-stop", true)) refund(state);
    state.refund = null;
    state.reviver = null;
    state.progress = 0;
    helper.sendActionBar(Component.empty());
    cue("stop", downed.getLocation());
    status(downed, state);
    fire("stop", downed, helper);
  }

  private void refund(State state) {
    if (state.refund != null && flag("price.refund-on-stop", true)) state.refund.run();
    state.refund = null;
  }

  private void complete(Player p, State state, Player helper) {
    states.remove(p.getUniqueId());
    state.refund = null;
    release(p, state);
    var max = p.getAttribute(Attribute.MAX_HEALTH);
    p.setHealth(Math.min(number("revived-health", 4, 0.5, 40), max == null ? 20 : max.getValue()));
    long protection = (long) Math.ceil(number("revived-protection-seconds", 2, 0, 60) / STEP);
    if (protection > 0) recoveryUntil.put(p.getUniqueId(), ticks + protection);
    clearMobTargets(p);
    Bukkit.getPluginManager().callEvent(new ReviveEvent(p, helper));
    p.sendMessage(
        helper == null
            ? lang.render("revive.revived")
            : lang.render("revive.revived-by", Map.of("reviver", helper.getName())));
    if (helper != null) {
      helper.sendMessage(lang.render("revive.revived-helper", Map.of("downed", p.getName())));
      helper.sendActionBar(Component.empty());
    }
    cue("revived", p.getLocation());
    cue("revived-extra", p.getLocation());
    fire("revive", p, helper);
  }

  // ------------------------------------------------------------------- price

  private String priceType() {
    return plugin.getConfig().getString(key("price.type"), "none").toLowerCase(Locale.ROOT);
  }

  private long priceAmount() {
    return (long) number("price.amount", 0, 0, 1_000_000);
  }

  private boolean free(Player helper) {
    return priceType().equals("none")
        || priceAmount() <= 0
        || helper.hasPermission("loveessential.revive.free");
  }

  private Material priceMaterial() {
    var material =
        Material.matchMaterial(plugin.getConfig().getString(key("price.material"), "GOLDEN_APPLE"));
    return material == null || !material.isItem() ? Material.GOLDEN_APPLE : material;
  }

  private String priceText() {
    String amount = Long.toString(priceAmount());
    return switch (priceType()) {
      case "money" -> PlainText.of(lang.render("revive.price-money", Map.of("amount", amount)));
      case "level" -> PlainText.of(lang.render("revive.price-level", Map.of("amount", amount)));
      case "item" ->
          PlainText.of(
              lang.render(
                  "revive.price-item",
                  Map.of(
                      "amount", amount, "item", priceMaterial().name().toLowerCase(Locale.ROOT))));
      default -> "";
    };
  }

  private boolean canPay(Player helper) {
    if (free(helper)) return true;
    long amount = priceAmount();
    return switch (priceType()) {
      case "money" -> love.economy().map(e -> e.has(helper, amount)).orElse(false);
      case "level" -> helper.getLevel() >= amount;
      case "item" ->
          helper.getInventory().containsAtLeast(new ItemStack(priceMaterial()), (int) amount);
      default -> true;
    };
  }

  /** Takes the price and returns the undo; null when nothing was taken. */
  private Runnable charge(Player helper) {
    if (free(helper)) return null;
    long amount = priceAmount();
    switch (priceType()) {
      case "money" -> {
        var economy = love.economy();
        if (economy.isEmpty() || !economy.get().charge(helper, amount)) return null;
        return () -> economy.get().give(helper, amount);
      }
      case "level" -> {
        helper.giveExpLevels((int) -amount);
        return () -> helper.giveExpLevels((int) amount);
      }
      case "item" -> {
        var stack = new ItemStack(priceMaterial(), (int) amount);
        helper.getInventory().removeItem(stack);
        return () ->
            helper
                .getInventory()
                .addItem(stack.clone())
                .values()
                .forEach(rest -> helper.getWorld().dropItemNaturally(helper.getLocation(), rest));
      }
      default -> {
        return null;
      }
    }
  }

  // ------------------------------------------------------------------ dying

  private void suicide(Player p, State state) {
    double after = number("suicide-delay", 5, 0, 600);
    if (state.carrier != null || !p.isSneaking() || (ticks - state.since) * STEP < after) {
      state.suicide = 0;
      return;
    }
    state.suicide += STEP;
    if (ticks % 10 == 0)
      cue("giveup-tick", p.getLocation(), (float) (state.suicide / suicideCount()) * .6f);
    if (state.suicide >= suicideCount()) giveUp(p);
  }

  private void giveUp(Player p) {
    var event = new DownedSuicideEvent(p);
    Bukkit.getPluginManager().callEvent(event);
    var state = states.get(p.getUniqueId());
    if (event.isCancelled()) {
      if (state != null) state.suicide = 0;
      return;
    }
    kill(p, DownedDeathEvent.Cause.SUICIDE);
  }

  /** Ends a downed state with a real death. Returns false when a listener refused. */
  private boolean kill(Player p, DownedDeathEvent.Cause cause) {
    var state = states.get(p.getUniqueId());
    if (state == null) return false;
    var event = new DownedDeathEvent(p, cause);
    Bukkit.getPluginManager().callEvent(event);
    // A player who left cannot be kept alive: only the other causes may be refused.
    if (event.isCancelled() && cause != DownedDeathEvent.Cause.QUIT) return false;
    states.remove(p.getUniqueId());
    refund(state);
    release(p, state);
    dying.put(p.getUniqueId(), cause);
    if (state.cause != null) p.setLastDamageCause(state.cause);
    try {
      p.setHealth(0);
    } finally {
      dying.remove(p.getUniqueId());
    }
    fire("death", p, null);
    return true;
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void death(PlayerDeathEvent e) {
    var p = e.getEntity();
    var cause = dying.get(p.getUniqueId());
    var leftover = states.remove(p.getUniqueId());
    if (leftover != null) {
      // Killed by damage after the invulnerability window: still a downed death.
      Bukkit.getPluginManager().callEvent(new DownedDeathEvent(p, DownedDeathEvent.Cause.DAMAGE));
      refund(leftover);
      release(p, leftover);
      cause = DownedDeathEvent.Cause.DAMAGE;
    }
    downs.remove(p.getUniqueId());
    recoveryUntil.remove(p.getUniqueId());
    if (cause == null) return;
    if (!deluxeOwnsInventory()
        && flag("keep-inventory", false)
        && (cause == DownedDeathEvent.Cause.TIMER || cause == DownedDeathEvent.Cause.SUICIDE)) {
      e.setKeepInventory(true);
      e.getDrops().clear();
      e.setKeepLevel(true);
      e.setDroppedExp(0);
    }
    if (!flag("death-message", true)) e.deathMessage(null);
  }

  private boolean deluxeOwnsInventory() {
    return flag("deluxecombat-inventory", true)
        && Bukkit.getPluginManager().isPluginEnabled("DeluxeCombat");
  }

  private boolean recovering(Player p) {
    return enabled() && recoveryUntil.getOrDefault(p.getUniqueId(), 0L) > ticks;
  }

  /** Early damage cancellation without persisting vanilla invulnerability. */
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void recoveryDamage(EntityDamageEvent e) {
    if (e.getEntity() instanceof Player p && recovering(p)) e.setCancelled(true);
  }

  // ----------------------------------------------------------------- damage

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    if (!(e.getEntity() instanceof Player p) || !enabled()) return;
    if (e instanceof EntityDamageByEntityEvent by) {
      var attacker = by.getDamager() instanceof Player a ? a : null;
      if (attacker != null && states.containsKey(attacker.getUniqueId()) && blocked("attack")) {
        e.setCancelled(true);
        return;
      }
      if (attacker != null
          && flag("carry.left-click", false)
          && states.containsKey(p.getUniqueId())
          && pickUp(attacker, p)) {
        e.setCancelled(true);
        return;
      }
    }
    var carried = carrying.get(p.getUniqueId());
    if (carried != null && flag("carry.drop-on-damage", true)) {
      var downed = Bukkit.getPlayer(carried);
      if (downed != null) drop(p, downed);
    }
    var state = states.get(p.getUniqueId());
    // Damage to the one who is carried throws both to the ground, as damage to the carrier does.
    if (state != null && state.carrier != null && flag("carry.drop-on-damage", true)) {
      var carrier = Bukkit.getPlayer(state.carrier);
      if (carrier != null) drop(carrier, p);
    }
    if (state != null) {
      if (state.invulnerable > 0) e.setCancelled(true);
      else state.cause = e;
      return;
    }
    if (lethal(p, e) && eligible(p, e)) down(p, e);
  }

  @EventHandler(ignoreCancelled = true)
  public void regain(EntityRegainHealthEvent e) {
    if (e.getEntity() instanceof Player p && states.containsKey(p.getUniqueId()))
      e.setCancelled(true);
  }

  // ------------------------------------------------------------------- carry

  private boolean pickUp(Player carrier, Player downed) {
    var state = states.get(downed.getUniqueId());
    if (state == null
        || !flag("carry.enabled", true)
        || state.carrier != null
        || states.containsKey(carrier.getUniqueId())
        || carrying.containsKey(carrier.getUniqueId())
        || carrier.isInsideVehicle()
        || !carrier.getPassengers().isEmpty()
        || !carrier.hasPermission("loveessential.revive.picker")
        || !downed.hasPermission("loveessential.revive.pickable")) return false;
    var event = new PickupDownedEvent(downed, carrier);
    Bukkit.getPluginManager().callEvent(event);
    if (event.isCancelled()) return false;
    var helper = state.reviver == null ? null : Bukkit.getPlayer(state.reviver);
    if (!carrier.addPassenger(downed)) return false;
    if (helper != null) stop(downed, state, helper);
    state.carrier = carrier.getUniqueId();
    updatePose(downed, state);
    updateInteraction(downed, state);
    carrying.put(carrier.getUniqueId(), downed.getUniqueId());
    double slow = number("carry.slow", .15, 0, .9);
    if (slow > 0) modifier(carrier, Attribute.MOVEMENT_SPEED, carryKey, -slow);
    cue("pickup", downed.getLocation());
    fire("pickup", downed, carrier);
    return true;
  }

  private void drop(Player carrier, Player downed) {
    var state = states.get(downed.getUniqueId());
    carrying.remove(carrier.getUniqueId());
    if (state != null) state.carrier = null;
    detach(carrier, downed);
    if (state != null) restoreAfterDrop(downed, state);
    unslow(carrier);
    carrier.sendActionBar(Component.empty());
    cue("drop", downed.getLocation());
    Bukkit.getPluginManager().callEvent(new DropDownedEvent(downed, carrier));
    fire("drop", downed, carrier);
  }

  private void restoreAfterDrop(Player p, State state) {
    restoreCeiling(p, state);
    p.setPose(Pose.STANDING, false);
    updatePose(p, state);
    updateInteraction(p, state);
    // Dismount processing may reset the pose/location after removePassenger returns.
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!enabled()
                  || states.get(p.getUniqueId()) != state
                  || state.carrier != null
                  || !p.isOnline()
                  || p.isDead()) return;
              restoreCeiling(p, state);
              p.setPose(Pose.STANDING, false);
              updatePose(p, state);
              updateInteraction(p, state);
            });
  }

  @EventHandler
  public void sneak(PlayerToggleSneakEvent e) {
    if (!e.isSneaking() || !enabled()) return;
    var downed = carrying.get(e.getPlayer().getUniqueId());
    if (downed == null) return;
    var target = Bukkit.getPlayer(downed);
    if (target != null) drop(e.getPlayer(), target);
    else carrying.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler(ignoreCancelled = true)
  public void dismount(EntityDismountEvent e) {
    if (e.getEntity() instanceof Player p
        && states.containsKey(p.getUniqueId())
        && !dropping.contains(p.getUniqueId())
        && states.get(p.getUniqueId()).carrier != null) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void pearl(ProjectileLaunchEvent e) {
    if (e.getEntity().getType() == EntityType.ENDER_PEARL
        && e.getEntity().getShooter() instanceof Player p
        && (carrying.containsKey(p.getUniqueId()) || states.containsKey(p.getUniqueId())))
      e.setCancelled(true);
  }

  // ------------------------------------------------------------------- steal

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void interactEntity(PlayerInteractEntityEvent e) {
    handleEntityClick(e);
  }

  // Bukkit gives the precise-hit event its own handler list.
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void interactAtEntity(PlayerInteractAtEntityEvent e) {
    handleEntityClick(e);
  }

  private void handleEntityClick(PlayerInteractEntityEvent e) {
    if (!enabled()) return;
    var p = e.getPlayer();
    if (states.containsKey(p.getUniqueId()) && blocked("items")) {
      e.setCancelled(true);
      return;
    }
    Player victim = interactionTarget(e.getRightClicked());
    if (e.getHand() == EquipmentSlot.HAND && victim != null && interactDowned(p, victim))
      e.setCancelled(true);
  }

  private boolean interactDowned(Player p, Player victim) {
    State state = states.get(victim.getUniqueId());
    if (!enabled()
        || state == null
        || p.equals(victim)
        || states.containsKey(p.getUniqueId())
        || !p.canSee(victim)
        || !p.getWorld().equals(victim.getWorld())
        || !p.hasLineOfSight(victim)
        || p.getLocation().distanceSquared(victim.getLocation()) > 36) return false;
    int now = Bukkit.getCurrentTick();
    state.lastInteractions.values().removeIf(tick -> tick != now);
    if (state.lastInteractions.containsKey(p.getUniqueId())) return true;
    if (!p.isSneaking()) {
      // Right click picks the downed player up, as ReviveMe does.
      if (!pickUp(p, victim)) return false;
      state.lastInteractions.put(p.getUniqueId(), now);
      return true;
    }
    if (!flag("steal.enabled", true)
        || !p.hasPermission("loveessential.revive.robber")
        || !victim.hasPermission("loveessential.revive.stealable")) return false;
    try {
      inventories.rob(
          p,
          victim,
          (robber, target) ->
              enabled()
                  && states.containsKey(target.getUniqueId())
                  && !states.containsKey(robber.getUniqueId())
                  && robber.hasPermission("loveessential.revive.robber")
                  && target.hasPermission("loveessential.revive.stealable")
                  && robber.canSee(target)
                  && robber.getWorld().equals(target.getWorld())
                  && robber.getLocation().distance(target.getLocation()) <= 6);
    } catch (IllegalArgumentException refused) {
      // The window could not open (for example the robber is not authenticated): nothing to do.
    }
    state.lastInteractions.put(p.getUniqueId(), now);
    return true;
  }

  // ------------------------------------------------------------ restrictions

  private boolean downedAndBlocked(Player p, String action) {
    return enabled() && states.containsKey(p.getUniqueId()) && blocked(action);
  }

  @EventHandler(ignoreCancelled = true)
  public void blocks(BlockBreakEvent e) {
    if (downedAndBlocked(e.getPlayer(), "blocks")) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void blocks(BlockPlaceEvent e) {
    if (downedAndBlocked(e.getPlayer(), "blocks")) e.setCancelled(true);
  }

  @EventHandler
  public void interact(PlayerInteractEvent e) {
    if (downedAndBlocked(e.getPlayer(), "items")) {
      e.setUseItemInHand(Event.Result.DENY);
      e.setUseInteractedBlock(Event.Result.DENY);
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void swimming(EntityToggleSwimEvent e) {
    if (!e.isSwimming() && enabled() && states.containsKey(e.getEntity().getUniqueId()))
      e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void drop(PlayerDropItemEvent e) {
    if (downedAndBlocked(e.getPlayer(), "drop")) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void pickup(EntityPickupItemEvent e) {
    if (e.getEntity() instanceof Player p && downedAndBlocked(p, "pickup")) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void arrows(PlayerPickupArrowEvent e) {
    if (downedAndBlocked(e.getPlayer(), "pickup")) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void experience(PlayerPickupExperienceEvent e) {
    if (downedAndBlocked(e.getPlayer(), "pickup")) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void craft(CraftItemEvent e) {
    if (e.getWhoClicked() instanceof Player p && downedAndBlocked(p, "craft")) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void open(InventoryOpenEvent e) {
    if (e.getPlayer() instanceof Player p && downedAndBlocked(p, "inventory")) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void glide(EntityToggleGlideEvent e) {
    if (e.getEntity() instanceof Player p && e.isGliding() && downedAndBlocked(p, "glide"))
      e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void sprint(PlayerToggleSprintEvent e) {
    if (e.isSprinting() && downedAndBlocked(e.getPlayer(), "sprint")) e.setCancelled(true);
  }

  @EventHandler(ignoreCancelled = true)
  public void shoot(ProjectileLaunchEvent e) {
    if (e.getEntity().getShooter() instanceof Player p && downedAndBlocked(p, "attack"))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void command(PlayerCommandPreprocessEvent e) {
    if (!downedAndBlocked(e.getPlayer(), "commands")) return;
    String line = e.getMessage().startsWith("/") ? e.getMessage().substring(1) : e.getMessage();
    String root = line.split(" ", 2)[0].toLowerCase(Locale.ROOT);
    if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
    var config = plugin.getConfig();
    if (config.getStringList(key("allowed-commands")).contains(root)) return;
    var blockedCommands = config.getStringList(key("blocked-commands"));
    if (blockedCommands.contains("*") || blockedCommands.contains(root)) {
      e.setCancelled(true);
      lang.send(e.getPlayer(), "revive.refused");
    }
  }

  private void clearMobTargets(Player p) {
    if (!flag("mobs-ignore", true)) return;
    for (Entity entity : p.getNearbyEntities(32, 32, 32))
      if (entity instanceof org.bukkit.entity.Mob mob && p.equals(mob.getTarget()))
        mob.setTarget(null);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void bed(PlayerBedEnterEvent e) {
    if (enabled() && states.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void respawnPoint(com.destroystokyo.paper.event.player.PlayerSetSpawnEvent e) {
    if (enabled()
        && states.containsKey(e.getPlayer().getUniqueId())
        && e.getCause()
            != com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN
        && e.getLocation() != null) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void target(EntityTargetEvent e) {
    if (enabled()
        && flag("mobs-ignore", true)
        && e.getTarget() instanceof Player p
        && (states.containsKey(p.getUniqueId()) || recovering(p))) e.setCancelled(true);
  }

  @EventHandler
  public void gameMode(PlayerGameModeChangeEvent e) {
    var state = states.get(e.getPlayer().getUniqueId());
    if (state == null
        || e.getNewGameMode() != GameMode.CREATIVE && e.getNewGameMode() != GameMode.SPECTATOR)
      return;
    states.remove(e.getPlayer().getUniqueId());
    refund(state);
    release(e.getPlayer(), state);
  }

  // ---------------------------------------------------------------- sessions

  /** Native combat-logout corpse accepted: release effects, but leave all items to DeluxeCombat. */
  public boolean handoffCombatLogout(Player p) {
    if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
    var state = states.remove(p.getUniqueId());
    if (state == null) return false;
    refund(state);
    release(p, state);
    p.getPersistentDataContainer().set(pending, PersistentDataType.BYTE, (byte) 1);
    Bukkit.getPluginManager().callEvent(new DownedQuitEvent(p, false));
    fire("quit", p, null);
    return true;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void quit(PlayerQuitEvent e) {
    var p = e.getPlayer();
    var carried = carrying.remove(p.getUniqueId());
    if (carried != null) {
      unslow(p);
      var downed = Bukkit.getPlayer(carried);
      var state = downed == null ? null : states.get(downed.getUniqueId());
      if (state != null) {
        state.carrier = null;
        detach(p, downed);
        restoreAfterDrop(downed, state);
      }
    }
    var state = states.get(p.getUniqueId());
    recoveryUntil.remove(p.getUniqueId());
    downs.remove(p.getUniqueId());
    lastStart.remove(p.getUniqueId());
    notified.remove(p.getUniqueId());
    if (state == null) return;
    boolean killed = flag("disconnect-kill", true);
    Bukkit.getPluginManager().callEvent(new DownedQuitEvent(p, killed));
    fire("quit", p, null);
    if (killed) kill(p, DownedDeathEvent.Cause.QUIT);
    else {
      states.remove(p.getUniqueId());
      refund(state);
      release(p, state);
      p.getPersistentDataContainer().set(pending, PersistentDataType.BYTE, (byte) 1);
    }
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    var p = e.getPlayer();
    if (!p.getPersistentDataContainer().has(pending, PersistentDataType.BYTE)) return;
    p.getPersistentDataContainer().remove(pending);
    var max = p.getAttribute(Attribute.MAX_HEALTH);
    p.setHealth(Math.min(number("revived-health", 4, 0.5, 40), max == null ? 20 : max.getValue()));
  }

  // ------------------------------------------------------------------ events

  /** Runs the actions configured for an event: roles are {@code downed} and {@code actor}. */
  private void fire(String event, Player downed, Player actor) {
    run(event, "downed", downed);
    if (actor != null) run(event, "actor", actor);
  }

  private void run(String event, String role, Player target) {
    var maps = plugin.getConfig().getMapList(key("events." + event + "." + role));
    if (maps.isEmpty()) return;
    var list = new ArrayList<Action>();
    for (var map : maps) {
      var args = new HashMap<String, String>();
      String type = "";
      for (var entry : map.entrySet()) {
        if (String.valueOf(entry.getKey()).equals("type")) type = String.valueOf(entry.getValue());
        else args.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
      }
      list.add(new Action(type, args));
    }
    actions.execute(target, list);
  }

  // ---------------------------------------------------------------- commands

  /** {@code /revive}: suicide for the downed, and administrative revive, down and kill. */
  public void command(CommandSender sender, String[] args) {
    if (!enabled()) throw new IllegalArgumentException("errors.module-disabled");
    String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
    switch (sub) {
      case "suicide" -> {
        if (!(sender instanceof Player p)
            || !p.hasPermission("loveessential.revive.command.suicide"))
          throw new IllegalArgumentException("errors.permission");
        var state = states.get(p.getUniqueId());
        if (state == null) throw new IllegalArgumentException("revive.command-not-downed");
        double wait = number("suicide-delay", 5, 0, 600) - (ticks - state.since) * STEP;
        if (wait > 0) {
          lang.send(
              p,
              "revive.command-not-yet",
              Map.of("seconds", Long.toString((long) Math.ceil(wait))));
          return;
        }
        giveUp(p);
      }
      case "revive", "down", "kill" -> {
        if (!sender.hasPermission("loveessential.revive.command." + sub))
          throw new IllegalArgumentException("errors.permission");
        if (args.length != 2) throw new IllegalArgumentException("revive.command-not-found");
        var target = Bukkit.getPlayerExact(args[1]);
        if (target == null) throw new IllegalArgumentException("revive.command-not-found");
        var state = states.get(target.getUniqueId());
        switch (sub) {
          case "revive" -> {
            if (state == null) throw new IllegalArgumentException("revive.command-not-downed");
            refund(state);
            complete(target, state, null);
          }
          case "down" -> {
            if (state != null) throw new IllegalArgumentException("revive.command-already-downed");
            if (!down(target, null)) throw new IllegalArgumentException("revive.refused");
          }
          default -> {
            if (state == null) throw new IllegalArgumentException("revive.command-not-downed");
            kill(target, DownedDeathEvent.Cause.COMMAND);
          }
        }
        lang.send(sender, "revive.command-done");
      }
      default ->
          lang.lines("revive.command-usage")
              .forEach(line -> sender.sendMessage(lang.renderText(line, Map.of())));
    }
  }

  /**
   * Names for tab completion of {@code /revive <sub> <player>}: only downed players for
   * revive/kill.
   */
  public List<String> downedNames() {
    return states.keySet().stream()
        .map(Bukkit::getPlayer)
        .filter(Objects::nonNull)
        .map(Player::getName)
        .sorted()
        .toList();
  }

  /** Test hook: forces the downed state without damage. */
  boolean force(Player p) {
    return down(p, null);
  }
}
