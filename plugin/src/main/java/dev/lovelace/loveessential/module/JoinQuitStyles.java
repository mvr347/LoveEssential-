package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The join and quit messages a player can choose in the personalization menu. The catalogue is written by the
 * administrator under {@code joinquit.styles} (the first entry is always the built-in default message); a style may
 * need a permission. The choice is one id per player in the profile store. An unknown or no longer permitted id
 * silently falls back to the default, so removing a style from the config never breaks anybody's join message.
 */
public final class JoinQuitStyles implements Listener, AutoCloseable {
  public static final String KEY = "loveessential.joinquit";
  public static final String DEFAULT_ID = "default";

  /** One catalogue entry; {@code join} and {@code quit} are MiniMessage templates with {@code <player>}. */
  public record Style(
      String id, String name, String join, String quit, String permission, Material icon) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final Lang lang;
  private final SqliteStore store;
  private final Map<UUID, String> choice = new ConcurrentHashMap<>();
  private final Map<UUID, CompletableFuture<Void>> loaded = new ConcurrentHashMap<>();

  public JoinQuitStyles(LoveEssentialPlugin plugin, MainThread main, Lang lang, SqliteStore store) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.store = store;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    for (var p : Bukkit.getOnlinePlayers()) load(p.getUniqueId());
  }

  // ------------------------------------------------------------------ catalogue

  /** The default style followed by the configured ones, in config order. */
  public List<Style> catalog() {
    var result = new ArrayList<Style>();
    result.add(
        new Style(
            DEFAULT_ID,
            lang.text("joinquit.default-name"),
            lang.text("joinquit.join"),
            lang.text("joinquit.quit"),
            "",
            Material.OAK_SIGN));
    ConfigurationSection styles = plugin.getConfig().getConfigurationSection("joinquit.styles");
    if (styles == null) return result;
    for (String id : styles.getKeys(false)) {
      ConfigurationSection s = styles.getConfigurationSection(id);
      if (s == null || id.equals(DEFAULT_ID)) continue;
      String join = s.getString("join", "");
      String quit = s.getString("quit", "");
      // A style without both texts would send an empty line: skip it with a warning instead.
      if (join.isBlank() || quit.isBlank()) {
        plugin.getLogger().warning("joinquit.styles." + id + ": нужны оба текста join и quit, стиль пропущен.");
        continue;
      }
      Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
      result.add(
          new Style(
              id,
              s.getString("name", id),
              join,
              quit,
              s.getString("permission", ""),
              icon == null ? Material.PAPER : icon));
    }
    return result;
  }

  public boolean allowed(Player p, Style style) {
    return style.permission().isBlank() || p.hasPermission(style.permission());
  }

  /** The style the player's messages use now: the saved one if it still exists and is permitted. */
  public Style chosen(Player p) {
    var all = catalog();
    String id = choice.getOrDefault(p.getUniqueId(), DEFAULT_ID);
    for (var style : all) if (style.id().equals(id) && allowed(p, style)) return style;
    return all.get(0);
  }

  public Component join(Player p) {
    return lang.renderText(chosen(p).join(), Map.of("player", p.getName()));
  }

  public Component quit(Player p) {
    return lang.renderText(chosen(p).quit(), Map.of("player", p.getName()));
  }

  // ------------------------------------------------------------------ storage

  private CompletableFuture<Void> load(UUID id) {
    return loaded.computeIfAbsent(
        id,
        key ->
            store
                .profile(key, KEY)
                .handle(
                    (value, error) -> {
                      // On a read failure the default is used; nothing here may block the join message.
                      // putIfAbsent: a style chosen while this read was still running is newer than the value it read.
                      if (error == null && value.isPresent() && !value.get().isBlank())
                        choice.putIfAbsent(key, value.get());
                      return (Void) null;
                    }));
  }

  /** Runs the task on the main thread once the player's choice is known; skipped if they left meanwhile. */
  public void afterLoaded(Player p, Runnable task) {
    load(p.getUniqueId())
        .thenRun(
            () ->
                main.execute(
                    () -> {
                      if (p.isOnline()) task.run();
                    }));
  }

  /** Saves the choice; the cache changes only after the write succeeded. */
  public CompletableFuture<Void> choose(Player p, String id) {
    MainThread.require();
    var style = catalog().stream().filter(s -> s.id().equals(id)).findFirst();
    if (style.isEmpty() || !allowed(p, style.get()))
      return CompletableFuture.failedFuture(new IllegalArgumentException("errors.permission"));
    UUID uuid = p.getUniqueId();
    return store
        .profile(uuid, KEY, id)
        .thenAccept(done -> main.execute(() -> choice.put(uuid, id)));
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    load(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    // Kept until the quit message is built by Gameplay (LOWEST/NORMAL handlers run before this MONITOR-less drop):
    // the cache is cleared one tick later.
    if (!plugin.isEnabled()) return;
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              choice.remove(id);
              loaded.remove(id);
            });
  }

  @Override
  public void close() {
    org.bukkit.event.HandlerList.unregisterAll(this);
    choice.clear();
    loaded.clear();
  }
}
