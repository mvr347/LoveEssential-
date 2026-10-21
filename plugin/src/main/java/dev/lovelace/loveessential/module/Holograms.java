package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.api.HologramService.*;
import dev.lovelace.loveessential.condition.ActionEngine;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.Placeholders;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

/** Per-viewer display entities keep page selection and placeholders private. */
public final class Holograms implements HologramService, Listener, AutoCloseable {
  private record Viewer(UUID player, String id) {}

  private record Render(List<Entity> entities, List<TextDisplay> texts, int page) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final Map<String, Snapshot> definitions = new LinkedHashMap<>();
  private final Map<Viewer, Render> renders = new HashMap<>();
  private final Map<UUID, Viewer> hits = new HashMap<>();
  private final Map<Viewer, Integer> pages = new HashMap<>();
  private final Map<String, LinkedHashSet<ClickHandler>> handlers = new HashMap<>();
  private final Map<UUID, Long> clicks = new HashMap<>();
  private final Map<String, Long> failures = new HashMap<>();
  private Placeholders placeholders;
  private ActionEngine actions;
  private org.bukkit.scheduler.BukkitTask task;

  public Holograms(
      LoveEssentialPlugin plugin, MainThread main, SqliteStore store, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
  }

  public void setup(Placeholders placeholders, ActionEngine actions) {
    this.placeholders = placeholders;
    this.actions = actions;
  }

  public CompletableFuture<Void> load() {
    return store
        .list("hologram", Hologram.class)
        .thenCombine(
            store.list("place", Gameplay.Place.class),
            (current, legacy) -> {
              var work = new ArrayList<CompletableFuture<?>>();
              var ids = new HashSet<String>();
              current.forEach(d -> ids.add(d.id()));
              for (var d : legacy)
                if (d.id().startsWith("hologram:")) {
                  String id = d.id().substring(9);
                  var old = d.value();
                  var lines = old.textKeys().stream().map(k -> new Line(k, "")).toList();
                  var migratedPages = new ArrayList<Page>();
                  for (int i = 0; i < lines.size(); i += 32)
                    migratedPages.add(
                        new Page(lines.subList(i, Math.min(i + 32, lines.size())), null));
                  if (migratedPages.isEmpty()) migratedPages.add(new Page(List.of(), null));
                  var pos = old.position();
                  pos =
                      new Position(
                          pos.world(), pos.x(), pos.y() + 2, pos.z(), pos.yaw(), pos.pitch());
                  if (ids.contains(id))
                    work.add(store.delete("place", d.id(), d.revision(), "migration"));
                  else
                    work.add(
                        store
                            .save(
                                "hologram",
                                id,
                                0,
                                new Hologram(id, pos, migratedPages, 0, true, .3, 48),
                                "migration")
                            .thenCompose(
                                rev -> store.delete("place", d.id(), d.revision(), "migration")));
                }
              return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
            })
        .thenCompose(f -> f)
        .thenCompose(v -> store.list("hologram", Hologram.class))
        .thenCompose(
            list ->
                main.call(
                    () -> {
                      definitions.clear();
                      list.forEach(
                          d -> definitions.put(d.id(), new Snapshot(d.value(), d.revision())));
                      return null;
                    }));
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1, 20);
  }

  public String nextId() {
    return "holo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  public Collection<Snapshot> all() {
    MainThread.require();
    return List.copyOf(definitions.values());
  }

  public Optional<Snapshot> get(String id) {
    MainThread.require();
    return Optional.ofNullable(definitions.get(id));
  }

  public CompletableFuture<Snapshot> save(Hologram value, long expected) {
    return main.call(
            () -> {
              // Decode before committing: malformed third-party item data must not poison the
              // renderer.
              value
                  .pages()
                  .forEach(
                      p ->
                          p.lines()
                              .forEach(
                                  l -> {
                                    if (!l.item().isBlank()) decode(l);
                                  }));
              if (!definitions.containsKey(value.id())
                  && definitions.size()
                      >= Math.clamp(
                          plugin.getConfig().getInt("hologram.max-holograms", 256), 1, 4096))
                throw new IllegalArgumentException("holo.limit");
              return store.save("hologram", value.id(), expected, value, "hologram-api");
            })
        .thenCompose(f -> f)
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      var snapshot = new Snapshot(value, rev);
                      definitions.put(value.id(), snapshot);
                      invalidate(value.id());
                      return snapshot;
                    }));
  }

  public CompletableFuture<Void> delete(String id, long expected) {
    return store
        .delete("hologram", id, expected, "hologram-api")
        .thenCompose(
            v ->
                main.call(
                    () -> {
                      definitions.remove(id);
                      invalidate(id);
                      pages.keySet().removeIf(k -> k.id().equals(id));
                      handlers.remove(id);
                      return null;
                    }));
  }

  public LoveEssentialService.Registration onClick(String id, ClickHandler handler) {
    MainThread.require();
    Objects.requireNonNull(handler);
    handlers.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(handler);
    return () -> {
      MainThread.require();
      var set = handlers.get(id);
      if (set != null) {
        set.remove(handler);
        if (set.isEmpty()) handlers.remove(id);
      }
    };
  }

  public static ItemStack decode(Line line) {
    return ItemStack.deserializeBytes(Base64.getDecoder().decode(line.item()));
  }

  public static Line item(ItemStack item) {
    if (item == null || item.getType().isAir())
      throw new IllegalArgumentException("holo.item-required");
    var copy = item.clone();
    copy.setAmount(1);
    return new Line("", Base64.getEncoder().encodeToString(copy.serializeAsBytes()));
  }

  public void create(Player p, String text, Consumer<String> done) {
    String id = nextId();
    String key = "holograms." + id + ".text_" + UUID.randomUUID().toString().replace("-", "");
    var at =
        p.getLocation()
            .clone()
            .add(0, plugin.getConfig().getDouble("hologram.creation-height", 2), 0);
    publishText(
        p,
        key,
        text,
        () ->
            mutate(
                p,
                new Hologram(
                    id,
                    Position.of(at),
                    List.of(new Page(List.of(new Line(key, "")), null)),
                    0,
                    true,
                    .3,
                    48),
                0,
                () -> done.accept(id)));
  }

  public void publishText(Player p, String key, String text, Runnable done) {
    CompletableFuture.runAsync(
            () -> {
              try {
                lang.putAll(Map.of(key, text));
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            },
            plugin.io())
        .whenComplete(
            (v, e) ->
                main.execute(
                    () -> {
                      if (e != null) lang.send(p, "errors.storage");
                      else if (p.isOnline()
                          && p.hasPermission("loveessential.admin")
                          && love.authenticated(p)
                          && plugin.module("hologram")) done.run();
                    }));
  }

  public void mutate(Player p, Hologram value, long revision, Runnable done) {
    if (!plugin.module("hologram")
        || !p.hasPermission("loveessential.admin")
        || !love.authenticated(p)) throw new IllegalArgumentException("errors.permission");
    if (!love.territorialAccess(p, value.position().location()))
      throw new IllegalArgumentException("errors.territory");
    save(value, revision)
        .whenComplete(
            (v, e) ->
                main.execute(
                    () -> {
                      if (e != null) lang.send(p, "errors.conflict");
                      else if (p.isOnline()) {
                        lang.send(p, "holo.saved", Map.of("id", value.id()));
                        done.run();
                      }
                    }));
  }

  public void remove(Player p, Snapshot old, Runnable done) {
    if (!p.hasPermission("loveessential.admin")
        || !love.authenticated(p)
        || !plugin.module("hologram")) throw new IllegalArgumentException("errors.permission");
    if (!love.territorialAccess(p, old.value().position().location()))
      throw new IllegalArgumentException("errors.territory");
    delete(old.value().id(), old.revision())
        .whenComplete(
            (v, e) ->
                main.execute(
                    () -> {
                      if (e != null) lang.send(p, "errors.conflict");
                      else done.run();
                    }));
  }

  private void invalidate(String id) {
    for (var key : new ArrayList<>(renders.keySet())) if (key.id().equals(id)) removeRender(key);
    failures.remove(id);
  }

  private void removeRender(Viewer key) {
    var old = renders.remove(key);
    if (old != null)
      old.entities()
          .forEach(
              e -> {
                hits.remove(e.getUniqueId());
                e.remove();
              });
  }

  private void refresh() {
    var wanted = new LinkedHashMap<Viewer, Snapshot>();
    int perPlayer = plugin.getConfig().getInt("hologram.max-visible-per-player", 24);
    if (plugin.module("hologram"))
      for (var p : Bukkit.getOnlinePlayers())
        if (love.authenticated(p) && plugin.allows("hologram", p)) {
          var at = p.getLocation();
          definitions.values().stream()
              .filter(
                  snapshot -> {
                    var d = snapshot.value();
                    if (!d.position().world().equals(p.getWorld().getUID())) return false;
                    var pos = d.position().location();
                    return p.getWorld().isChunkLoaded(pos.getBlockX() >> 4, pos.getBlockZ() >> 4)
                        && at.distanceSquared(pos) < d.range() * d.range();
                  })
              .sorted(
                  Comparator.comparingDouble(
                      snapshot -> at.distanceSquared(snapshot.value().position().location())))
              .limit(perPlayer)
              .forEach(
                  snapshot ->
                      wanted.put(new Viewer(p.getUniqueId(), snapshot.value().id()), snapshot));
        }
    for (var key : new ArrayList<>(renders.keySet()))
      if (!wanted.containsKey(key)) removeRender(key);
    int count = renders.values().stream().mapToInt(r -> r.entities().size()).sum();
    int budget = plugin.getConfig().getInt("hologram.max-display-entities", 4096);
    for (var entry : wanted.entrySet()) {
      var key = entry.getKey();
      var def = entry.getValue().value();
      var p = Bukkit.getPlayer(key.player());
      if (p == null) continue;
      var old = renders.get(key);
      if (old != null && old.entities().stream().anyMatch(e -> !e.isValid())) {
        count -= old.entities().size();
        removeRender(key);
        old = null;
      }
      int selected = Math.floorMod(pages.getOrDefault(key, 0), def.pages().size());
      if (old == null
          && count + def.pages().get(selected).lines().size() + 1 <= budget
          && System.currentTimeMillis() - failures.getOrDefault(def.id(), 0L) > 10000) {
        try {
          render(p, key, def, def.position().location());
          count += renders.get(key).entities().size();
        } catch (RuntimeException ex) {
          removeRender(key);
          failures.put(def.id(), System.currentTimeMillis());
          plugin
              .getLogger()
              .warning(
                  lang.text("holo.render-failed")
                      + " ["
                      + def.id()
                      + "] "
                      + ex.getClass().getSimpleName()
                      + ": "
                      + ex.getMessage());
        }
      } else if (old != null) {
        int index = 0;
        for (var line : def.pages().get(old.page()).lines())
          if (!line.textKey().isBlank()) {
            var display = old.texts().get(index++);
            var rendered = text(line, p);
            if (!display.text().equals(rendered)) display.text(rendered);
          }
      }
    }
  }

  public net.kyori.adventure.text.Component text(Line line, Player p) {
    return placeholders == null
        ? lang.render(line.textKey())
        : placeholders.renderRaw(lang.text(line.textKey()), p, p);
  }

  private void render(Player p, Viewer key, Hologram def, Location at) {
    int page = Math.floorMod(pages.getOrDefault(key, 0), def.pages().size());
    var entities = new ArrayList<Entity>();
    var texts = new ArrayList<TextDisplay>();
    renders.put(key, new Render(entities, texts, page));
    var lines = def.pages().get(page).lines();
    double total =
        lines.stream()
            .mapToDouble(l -> l.item().isBlank() ? def.spacing() : Math.max(.65, def.spacing()))
            .sum();
    double last =
        lines.isEmpty()
            ? 0
            : lines.getLast().item().isBlank() ? def.spacing() : Math.max(.65, def.spacing());
    double top = Math.max(0, total - last);
    double offset = 0;
    for (var line : def.pages().get(page).lines()) {
      var location = at.clone().add(0, top - offset, 0);
      Entity entity;
      if (line.item().isBlank()) {
        entity =
            at.getWorld()
                .spawn(
                    location,
                    TextDisplay.class,
                    d -> {
                      base(d);
                      d.setLineWidth(300);
                      d.setDefaultBackground(false);
                      d.setBackgroundColor(Color.fromARGB(def.background()));
                      d.setShadowed(def.shadow());
                      d.text(text(line, p));
                    });
        texts.add((TextDisplay) entity);
        offset += def.spacing();
      } else {
        entity =
            at.getWorld()
                .spawn(
                    location,
                    ItemDisplay.class,
                    d -> {
                      base(d);
                      d.setItemStack(decode(line));
                      d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
                      d.setTransformation(
                          new org.bukkit.util.Transformation(
                              new org.joml.Vector3f(),
                              new org.joml.Quaternionf(),
                              new org.joml.Vector3f(.55f),
                              new org.joml.Quaternionf()));
                    });
        offset += Math.max(.65, def.spacing());
      }
      entities.add(entity);
      p.showEntity(plugin, entity);
    }
    float hitHeight = (float) Math.max(1, top + .75);
    var hit =
        at.getWorld()
            .spawn(
                at.clone().subtract(0, .25, 0),
                Interaction.class,
                d -> {
                  d.setPersistent(false);
                  d.setVisibleByDefault(false);
                  d.setInteractionWidth(2);
                  d.setInteractionHeight(hitHeight);
                  d.setResponsive(true);
                });
    entities.add(hit);
    hits.put(hit.getUniqueId(), key);
    p.showEntity(plugin, hit);
  }

  private void base(Display d) {
    d.setPersistent(false);
    d.setVisibleByDefault(false);
    d.setBrightness(new Display.Brightness(15, 15));
    d.setBillboard(Display.Billboard.CENTER);
    d.setViewRange(1.5f);
    d.setTeleportDuration(0);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void click(PlayerInteractEntityEvent e) {
    var key = hits.get(e.getRightClicked().getUniqueId());
    if (key == null
        || !key.player().equals(e.getPlayer().getUniqueId())
        || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
    e.setCancelled(true);
    var p = e.getPlayer();
    if (!plugin.allows("hologram", p)
        || !love.authenticated(p)
        || !love.territorialAccess(p, e.getRightClicked().getLocation())
        || p.getLocation().distanceSquared(e.getRightClicked().getLocation()) > 36) return;
    long now = System.currentTimeMillis();
    if (now - clicks.getOrDefault(p.getUniqueId(), 0L) < 250) return;
    clicks.put(p.getUniqueId(), now);
    var def = definitions.get(key.id());
    if (def == null) return;
    var rendered = renders.get(key);
    if (rendered == null) return;
    for (var handler : List.copyOf(handlers.getOrDefault(key.id(), new LinkedHashSet<>())))
      try {
        if (handler.click(p, key.id(), rendered.page())) return;
      } catch (RuntimeException ex) {
        plugin
            .getLogger()
            .warning(lang.text("holo.handler-failed") + " " + ex.getClass().getSimpleName());
        return;
      }
    var action = def.value().pages().get(rendered.page()).action();
    if (action != null && actions != null) {
      actions.execute(p, List.of(action));
      return;
    }
    if (def.value().pages().size() > 1) {
      pages.put(key, (rendered.page() + 1) % def.value().pages().size());
      removeRender(key);
      refresh();
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    for (var key : new ArrayList<>(renders.keySet()))
      if (key.player().equals(id)) removeRender(key);
    pages.keySet().removeIf(k -> k.player().equals(id));
    clicks.remove(id);
  }

  public void close() {
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    for (var key : new ArrayList<>(renders.keySet())) removeRender(key);
    pages.clear();
    handlers.clear();
    clicks.clear();
    failures.clear();
  }
}
