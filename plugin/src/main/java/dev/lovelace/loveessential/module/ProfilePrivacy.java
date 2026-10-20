package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/** Online session cache; unknown preferences never grant another player access. */
public final class ProfilePrivacy implements Listener, AutoCloseable {
  public record Preferences(boolean profileVisible, boolean activityVisible) {}

  private record State(long revision, Preferences value) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final LoveBridge love;
  private final dev.lovelace.loveessential.lang.Lang lang;
  private final Map<UUID, CompletableFuture<Preferences>> writes = new HashMap<>();
  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private final Map<UUID, String> names = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> sessions = new HashMap<>();
  private final Set<UUID> saving = new HashSet<>();
  private Consumer<UUID> changed = id -> {};

  public ProfilePrivacy(
      LoveEssentialPlugin plugin,
      SqliteStore store,
      LoveBridge love,
      dev.lovelace.loveessential.lang.Lang lang) {
    this.plugin = plugin;
    this.main = new MainThread(plugin);
    this.store = store;
    this.love = love;
    this.lang = lang;
  }

  public void changed(Consumer<UUID> listener) {
    changed = listener;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getOnlinePlayers().forEach(this::load);
  }

  private void load(Player p) {
    UUID id = p.getUniqueId(), session = UUID.randomUUID();
    sessions.put(id, session);
    states.remove(id);
    names.put(id, p.getName());
    var pending = writes.get(id);
    (pending == null
            ? CompletableFuture.completedFuture(null)
            : pending.handle((value, error) -> null))
        .thenCompose(ignored -> store.get("profile-preferences", id.toString(), Preferences.class))
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      if (!session.equals(sessions.get(id))
                          || !p.isOnline()
                          || Bukkit.getPlayer(id) != p) return;
                      if (error != null) {
                        plugin
                            .getLogger()
                            .warning(
                                lang.text("profile-menu.load-failed")
                                    + " "
                                    + error.getClass().getSimpleName());
                        Bukkit.getScheduler()
                            .runTaskLater(
                                plugin,
                                () -> {
                                  if (p.isOnline() && session.equals(sessions.get(id))) load(p);
                                },
                                200L);
                        return;
                      }
                      states.put(
                          id,
                          saved
                              .map(d -> new State(d.revision(), d.value()))
                              .orElseGet(
                                  () ->
                                      new State(
                                          0,
                                          new Preferences(
                                              plugin
                                                  .getConfig()
                                                  .getBoolean(
                                                      "profile.privacy.default-visible", true),
                                              plugin
                                                  .getConfig()
                                                  .getBoolean(
                                                      "profile.privacy.default-activity-visible",
                                                      true)))));
                    }));
  }

  public CompletableFuture<Preferences> offline(UUID id) {
    MainThread.require();
    var pending = writes.get(id);
    var defaults =
        new Preferences(
            plugin.getConfig().getBoolean("profile.privacy.default-visible", true),
            plugin.getConfig().getBoolean("profile.privacy.default-activity-visible", true));
    return (pending == null
            ? CompletableFuture.completedFuture(null)
            : pending.handle((v, e) -> null))
        .thenCompose(ignored -> store.get("profile-preferences", id.toString(), Preferences.class))
        .thenApply(saved -> saved.map(SqliteStore.Document::value).orElse(defaults));
  }

  public boolean loaded(UUID id) {
    return states.containsKey(id);
  }

  public Preferences preferences(Player p) {
    var s = states.get(p.getUniqueId());
    if (s == null) throw new IllegalArgumentException("profile-menu.loading");
    return s.value();
  }

  public boolean canView(Player viewer, Player target) {
    return viewer.equals(target)
        || Optional.ofNullable(states.get(target.getUniqueId()))
            .map(s -> s.value().profileVisible())
            .orElse(false);
  }

  public boolean canViewActivity(Player viewer, Player target) {
    return viewer.equals(target)
        || canView(viewer, target)
            && Optional.ofNullable(states.get(target.getUniqueId()))
                .map(s -> s.value().activityVisible())
                .orElse(false);
  }

  public Map<String, String> values(UUID id) {
    var state = states.get(id);
    boolean ready = state != null;
    return Map.of(
        "profile_loaded",
        Boolean.toString(ready),
        "profile_visible",
        Boolean.toString(ready && state.value().profileVisible()),
        "profile_hidden",
        Boolean.toString(!ready || !state.value().profileVisible()),
        "activity_visible",
        Boolean.toString(
            ready && state.value().profileVisible() && state.value().activityVisible()),
        "activity_enabled",
        Boolean.toString(ready && state.value().activityVisible()),
        "profile_command",
        names.containsKey(id) ? "loveessential:profile view " + names.get(id) : "",
        "profile_toggle_command",
        "loveessential:profile toggle",
        "activity_toggle_command",
        "loveessential:profile activity toggle");
  }

  public CompletableFuture<Preferences> change(Player p, UnaryOperator<Preferences> update) {
    MainThread.require();
    if (!plugin.ready()
        || !plugin.module("profile")
        || !love.authenticated(p)
        || !p.hasPermission("loveessential.profile"))
      throw new IllegalArgumentException("errors.permission");
    var old = states.get(p.getUniqueId());
    if (old == null) throw new IllegalArgumentException("profile-menu.loading");
    if (!saving.add(p.getUniqueId())) throw new IllegalArgumentException("wizard.busy");
    final Preferences next;
    try {
      next = Objects.requireNonNull(update.apply(old.value()));
    } catch (RuntimeException e) {
      saving.remove(p.getUniqueId());
      throw e;
    }
    UUID session = sessions.get(p.getUniqueId());
    var result =
        store
            .save(
                "profile-preferences",
                p.getUniqueId().toString(),
                old.revision(),
                next,
                p.getUniqueId().toString())
            .handle(
                (revision, error) ->
                    main.call(
                        () -> {
                          if (session.equals(sessions.get(p.getUniqueId()))) {
                            saving.remove(p.getUniqueId());
                            if (error == null) {
                              states.put(p.getUniqueId(), new State(revision, next));
                              changed.accept(p.getUniqueId());
                            }
                          }
                          if (error != null) throw new CompletionException(error);
                          return next;
                        }))
            .thenCompose(f -> f);
    writes.put(p.getUniqueId(), result);
    result.whenComplete(
        (value, error) -> main.execute(() -> writes.remove(p.getUniqueId(), result)));
    return result;
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    load(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    sessions.remove(id);
    states.remove(id);
    names.remove(id);
    saving.remove(id);
  }

  public void close() {
    HandlerList.unregisterAll(this);
    writes.clear();
    states.clear();
    sessions.clear();
    names.clear();
    saving.clear();
  }
}
