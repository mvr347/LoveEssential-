package dev.lovelace.loveessential.npc;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.HologramService;
import dev.lovelace.loveessential.api.NpcDefinition;
import dev.lovelace.loveessential.api.NpcInteractEvent;
import dev.lovelace.loveessential.api.Position;
import dev.lovelace.lovecore.api.npc.NpcClick;
import dev.lovelace.lovecore.api.npc.NpcClickEvent;
import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.lovecore.api.npc.NpcProvider;
import dev.lovelace.lovecore.api.npc.NpcSpawnEvent;
import dev.lovelace.lovecore.api.npc.NpcSpec;
import dev.lovelace.lovecore.api.presence.SkinTexture;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * LoveCore {@link NpcProvider} over LoveEssential NPCs. A plugin that picks {@code loveessential}
 * in its config gets the same calls and the same click/spawn events as with Citizens.
 *
 * <p>NPCs created through the contract are ordinary definitions with an owner of all zeros and an
 * {@code ext-} id; their name, skin, marks and hologram live in the definition's settings, so they
 * survive restarts and show up in {@code /le npc}. Changes are saved one after another per NPC, so
 * two quick calls never fight over the revision.
 */
public final class LoveEssentialNpcProvider implements NpcProvider, Listener, AutoCloseable {
  public static final String ID = "loveessential";
  private static final UUID OWNER = new UUID(0, 0);
  private static final double HOLOGRAM_HEIGHT = 2.35;
  private static final MiniMessage MINI = MiniMessage.miniMessage();

  private final JavaPlugin plugin;
  private final MainThread main;
  /** Bound before publication once the stored definitions have loaded. */
  private volatile NpcService npcs;

  private volatile HologramService holograms;
  private final Set<String> deleting = ConcurrentHashMap.newKeySet();
  private final Map<String, CompletableFuture<?>> tails = new ConcurrentHashMap<>();
  /** Latest requested definition per id, so reads right after a write see the write. */
  private final Map<String, NpcDefinition> optimistic = new ConcurrentHashMap<>();

  public LoveEssentialNpcProvider(JavaPlugin plugin, MainThread main) {
    this.plugin = plugin;
    this.main = main;
  }

  public LoveEssentialNpcProvider(
      JavaPlugin plugin, MainThread main, NpcService npcs, HologramService holograms) {
    this(plugin, main);
    bind(npcs, holograms);
  }

  /**
   * Binds loaded data before publication in LoveCore. An unbound adapter is not published: an
   * empty lookup must mean an absent NPC rather than a database that has not loaded yet.
   */
  public void bind(NpcService npcs, HologramService holograms) {
    this.holograms = holograms;
    this.npcs = npcs;
  }

  public boolean bound() {
    return npcs != null;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    npcs.spawnGate(
        definition -> {
          var event = new NpcSpawnEvent(new Handle(definition.id()));
          Bukkit.getPluginManager().callEvent(event);
          return !event.isCancelled();
        });
  }

  @Override
  public void close() {
    HandlerList.unregisterAll(this);
    deleting.clear();
    if (npcs != null) npcs.spawnGate(definition -> true);
  }

  @Override
  public String providerId() {
    return ID;
  }

  private Optional<NpcDefinition> view(String id) {
    if (deleting.contains(id)) return Optional.empty();
    var pending = optimistic.get(id);
    if (pending != null) return Optional.of(pending);
    var service = npcs;
    return service == null ? Optional.empty() : service.get(id);
  }

  @Override
  public Optional<NpcHandle> byKey(String key) {
    return key == null ? Optional.empty() : view(key.trim()).map(n -> new Handle(n.id()));
  }

  @Override
  public Optional<NpcHandle> byEntity(Entity entity) {
    var service = npcs;
    return entity == null || service == null
        ? Optional.empty()
        : service.identify(entity).filter(id -> view(id).isPresent()).map(Handle::new);
  }

  @Override
  public Optional<NpcHandle> targeted(Player player) {
    var service = npcs;
    return service == null
        ? Optional.empty()
        : service.lookedAt(player, 10).filter(id -> view(id).isPresent()).map(Handle::new);
  }

  @Override
  public NpcHandle create(NpcSpec spec) {
    MainThread.require();
    if (npcs == null) throw new IllegalStateException("LoveEssential is still starting");
    var type = spec.type() == EntityType.PLAYER ? EntityType.MANNEQUIN : spec.type();
    String id = "ext-" + UUID.randomUUID().toString().substring(0, 8);
    var definition =
        NpcDefinition.create(id, OWNER, inline(spec.name()), type.name(), Position.of(spec.location()));
    var settings = new HashMap<String, String>();
    settings.put("look-at", Boolean.toString(spec.lookClose()));
    if (spec.skin() != null) {
      settings.put("skin-value", spec.skin().value());
      settings.put("skin-signature", spec.skin().signature());
    } else if (spec.skinName() != null) settings.put("skin", spec.skinName());
    spec.tags().forEach((key, value) -> settings.put("tag." + key, value));
    definition = definition.withSettings(settings);
    optimistic.put(id, definition);
    var saved = definition;
    chain(id, () -> npcs.save(saved, 0, "provider"))
        .whenComplete(
            (result, error) -> {
              optimistic.remove(id, saved);
              if (error != null && !shutdownCancellation(error))
                plugin
                    .getLogger()
                    .log(java.util.logging.Level.WARNING, "NPC " + id + " was not created", error);
              else if (result != null && !deleting.contains(id) && !spec.hologram().isEmpty())
                new Handle(id).hologram(spec.hologram());
            });
    return new Handle(id);
  }

  @Override
  public boolean remove(String key) {
    if (key == null) return false;
    var found = view(key.trim());
    if (found.isEmpty()) return false;
    MainThread.require();
    String id = found.get().id();
    optimistic.remove(id);
    deleting.add(id);
    // Do not queue behind a future that still needs the main thread to apply a prior save.
    var removed = npcs.deleteLatest(id, "provider");
    var tail = tails.getOrDefault(id, CompletableFuture.completedFuture(null));
    removed.whenComplete(
        (v, error) -> {
          if (error != null)
            plugin
                .getLogger()
                .log(java.util.logging.Level.WARNING, "NPC " + key + " was not removed", error);
        });
    CompletableFuture.allOf(removed.handle((v, e) -> null), tail.handle((v, e) -> null))
        .whenComplete(
            (v, e) ->
                main.execute(
                    () -> {
                      npcs.finishRemoval(id, !removed.isCompletedExceptionally());
                      deleting.remove(id);
                    }));
    removeHologram(id);
    return true;
  }

  @Override
  public Collection<NpcHandle> all() {
    var ids = new TreeSet<>(optimistic.keySet());
    if (npcs != null) npcs.all().forEach(n -> ids.add(n.id()));
    var result = new ArrayList<NpcHandle>();
    for (var id : ids) if (!deleting.contains(id)) result.add(new Handle(id));
    return result;
  }

  /** Runs saves for one NPC strictly one after another. */
  private <T> CompletableFuture<T> chain(String id, java.util.function.Supplier<CompletableFuture<T>> step) {
    var result = new CompletableFuture<T>();
    var queued =
        tails.compute(
            id,
            (key, tail) -> {
              CompletableFuture<?> before =
                  tail == null
                      ? CompletableFuture.completedFuture(null)
                      : tail.handle((v, e) -> null);
              var next =
                  before.thenCompose(
                      ignored ->
                          main.call(
                                  () ->
                                      deleting.contains(id)
                                          ? CompletableFuture.<T>completedFuture(null)
                                          : step.get())
                              .thenCompose(java.util.function.Function.identity()));
              next.whenComplete(
                  (value, error) -> {
                    if (error != null) result.completeExceptionally(error);
                    else {
                      @SuppressWarnings("unchecked")
                      T typed = (T) value;
                      result.complete(typed);
                    }
                  });
              return next;
            });
    // Attach outside compute: a synchronously completed step must not mutate the same map recursively.
    queued.whenComplete((value, error) -> tails.remove(id, queued));
    return result;
  }

  private static boolean shutdownCancellation(Throwable error) {
    while ((error instanceof java.util.concurrent.CompletionException
            || error instanceof java.util.concurrent.ExecutionException)
        && error.getCause() != null) error = error.getCause();
    return error instanceof java.util.concurrent.CancellationException
        && "plugin-disabled".equals(error.getMessage());
  }

  private void change(String id, UnaryOperator<NpcDefinition> edit) {
    var current = view(id);
    if (current.isEmpty()) return;
    var predicted = edit.apply(current.get());
    optimistic.put(id, predicted);
    chain(
            id,
            () -> {
              var stored = npcs.get(id).or(() -> Optional.ofNullable(optimistic.get(id)));
              if (stored.isEmpty()) return CompletableFuture.<NpcDefinition>completedFuture(null);
              var updated = edit.apply(stored.get());
              return npcs.save(updated, stored.get().revision(), "provider");
            })
        .whenComplete(
            (saved, error) -> {
              optimistic.remove(id, predicted);
              if (error != null && !shutdownCancellation(error))
                plugin
                    .getLogger()
                    .log(java.util.logging.Level.WARNING, "NPC " + id + " was not updated", error);
            });
  }

  private static String inline(Component name) {
    return "@inline:" + MINI.serialize(name);
  }

  private void removeHologram(String id) {
    holograms
        .get("npc-" + id)
        .ifPresent(snapshot -> holograms.delete(snapshot.value().id(), snapshot.revision()));
  }

  private void placeHologram(String id, List<Component> lines) {
    var definition = view(id);
    if (definition.isEmpty()) return;
    if (lines.isEmpty()) {
      removeHologram(id);
      return;
    }
    var at = definition.get().spawn();
    var anchor = new Position(at.world(), at.x(), at.y() + HOLOGRAM_HEIGHT, at.z(), 0, 0);
    var page =
        new HologramService.Page(
            lines.stream().map(line -> new HologramService.Line(inline(line), "")).toList(), null);
    var existing = holograms.get("npc-" + id);
    holograms.save(
        new HologramService.Hologram("npc-" + id, anchor, List.of(page), 0, false, .27, 32),
        existing.map(HologramService.Snapshot::revision).orElse(0L));
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void click(NpcInteractEvent event) {
    var click = event.getClick() == NpcInteractEvent.Click.LEFT ? NpcClick.LEFT : NpcClick.RIGHT;
    var relayed = new NpcClickEvent(event.getPlayer(), new Handle(event.getNpc().id()), click);
    Bukkit.getPluginManager().callEvent(relayed);
    if (relayed.isCancelled()) event.setCancelled(true);
  }

  private final class Handle implements NpcHandle {
    private final String id;

    private Handle(String id) {
      this.id = id;
    }

    @Override
    public String providerId() {
      return ID;
    }

    @Override
    public String key() {
      return id;
    }

    @Override
    public String name() {
      return view(id)
          .map(n -> PlainTextComponentSerializer.plainText().serialize(npcs.displayName(n)))
          .orElse(id);
    }

    @Override
    public Optional<Entity> entity() {
      return npcs.entity(id);
    }

    @Override
    public boolean spawned() {
      return entity().isPresent();
    }

    /**
     * Shows the NPC. The entity appears on a following NPC tick once a player is in range, so this
     * can still return {@code false} right after the call.
     */
    @Override
    public boolean spawn() {
      npcs.show(id);
      return spawned();
    }

    @Override
    public boolean despawn() {
      boolean wasSpawned = spawned();
      npcs.hide(id);
      return wasSpawned;
    }

    @Override
    public Optional<String> skinName() {
      return view(id).map(n -> n.settings().get("skin")).filter(name -> !name.isBlank());
    }

    @Override
    public Location location() {
      return entity().map(Entity::getLocation).orElseGet(() -> view(id).orElseThrow().spawn().location());
    }

    @Override
    public void setName(Component name) {
      change(id, n -> n.withNameKey(inline(name)));
    }

    @Override
    public void setSkin(String playerName) {
      change(
          id,
          n ->
              n.withSetting("skin", playerName)
                  .withSetting("skin-value", null)
                  .withSetting("skin-signature", null));
    }

    @Override
    public void setSkin(SkinTexture texture) {
      change(
          id,
          n ->
              n.withSetting("skin", null)
                  .withSetting("skin-value", texture.value())
                  .withSetting("skin-signature", texture.signature()));
    }

    @Override
    public void teleport(Location to) {
      var position = Position.of(to);
      change(id, n -> n.withSpawn(position));
      var existing = holograms.get("npc-" + id);
      if (existing.isPresent()) {
        var v = existing.get().value();
        holograms.save(
            new HologramService.Hologram(
                v.id(),
                new Position(position.world(), position.x(), position.y() + HOLOGRAM_HEIGHT, position.z(), 0, 0),
                v.pages(),
                v.background(),
                v.shadow(),
                v.spacing(),
                v.range()),
            existing.get().revision());
      }
    }

    @Override
    public void lookClose(boolean enabled) {
      change(id, n -> n.withSetting("look-at", Boolean.toString(enabled)));
    }

    @Override
    public void hologram(List<Component> lines) {
      placeHologram(id, lines);
    }

    @Override
    public Optional<String> tag(String key) {
      return view(id).map(n -> n.settings().get("tag." + key));
    }

    @Override
    public void tag(String key, String value) {
      change(id, n -> n.withSetting("tag." + key, value));
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Handle handle && handle.id.equals(id);
    }

    @Override
    public int hashCode() {
      return id.hashCode();
    }
  }
}
