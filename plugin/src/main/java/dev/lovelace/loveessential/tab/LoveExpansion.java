package dev.lovelace.loveessential.tab;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class LoveExpansion extends PlaceholderExpansion implements AutoCloseable {
  private final Placeholders placeholders;
  private final Lang lang;
  private final LoveBridge love;
  private final Predicate<Player> afk, vanish;
  private final MainThread main;

  private record Key(UUID player, String parameter) {}

  private record Text(String value, long refreshAfter) {}

  private final Cache<Key, Text> texts =
      Caffeine.newBuilder()
          .maximumWeight(4 * 1024 * 1024)
          .weigher(
              (Key key, Text value) ->
                  (int)
                      Math.min(
                          Integer.MAX_VALUE,
                          160L + key.parameter().length() * 2L + value.value().length() * 2L))
          .expireAfterAccess(java.time.Duration.ofMinutes(5))
          .build();
  private final Set<Key> refreshing = ConcurrentHashMap.newKeySet();
  private final Semaphore refreshLimit = new Semaphore(64);
  private volatile boolean closed;

  public LoveExpansion(
      Placeholders p, Lang l, LoveBridge love, Predicate<Player> afk, Predicate<Player> vanish) {
    this(
        (JavaPlugin) Bukkit.getPluginManager().getPlugin("LoveEssential"), p, l, love, afk, vanish);
  }

  public LoveExpansion(
      JavaPlugin plugin,
      Placeholders p,
      Lang l,
      LoveBridge love,
      Predicate<Player> afk,
      Predicate<Player> vanish) {
    main = plugin == null ? null : new MainThread(plugin);
    placeholders = p;
    lang = l;
    this.love = love;
    this.afk = afk;
    this.vanish = vanish;
  }

  public @NotNull String getIdentifier() {
    return "loveessential";
  }

  public @NotNull String getAuthor() {
    return "Lovelace";
  }

  public @NotNull String getVersion() {
    return "2.0.0";
  }

  public boolean persist() {
    return true;
  }

  @Override
  public String onRequest(OfflinePlayer offline, @NotNull String params) {
    if (closed || offline == null) return "";
    var values = placeholders.playerValues(offline.getUniqueId());
    if (values.containsKey(params)) return values.get(params);
    if (!(offline instanceof Player p)) return "";
    if (params.startsWith("text_") && lang.contains(params.substring(5))) {
      var key = new Key(offline.getUniqueId(), params);
      if (Bukkit.isPrimaryThread()) {
        String value = renderText(p, params);
        publish(key, value);
        return value;
      }
      var cached = texts.getIfPresent(key);
      if (cached == null || System.nanoTime() >= cached.refreshAfter()) refresh(key);
      return cached == null ? "" : cached.value();
    }
    // Async callers consume published scalar snapshots; never call neighbour APIs off-thread.
    if (!Bukkit.isPrimaryThread())
      return switch (params) {
        case "afk", "vanished", "combat", "authenticated", "clan", "reputation" -> "";
        default -> null;
      };
    if (!p.isOnline()) return "";
    return switch (params) {
      case "afk" -> Boolean.toString(afk.test(p));
      case "vanished" -> Boolean.toString(vanish.test(p));
      case "combat" -> Boolean.toString(love.combat(p));
      case "authenticated" -> Boolean.toString(love.authenticated(p));
      case "clan" -> love.clan(p).orElse("");
      case "reputation" ->
          love.reputation(p).isPresent() ? Integer.toString(love.reputation(p).getAsInt()) : "";
      default -> null;
    };
  }

  private String renderText(Player player, String parameter) {
    MainThread.require();
    if (closed || !player.isOnline()) return "";
    return dev.lovelace.loveessential.module.Nicknames.LEGACY.serialize(
        parameter.equals("text_tab.name")
                || parameter.startsWith("text_tab.groups.") && parameter.endsWith(".name")
            ? placeholders.renderName(parameter.substring(5), player, player)
            : placeholders.render(parameter.substring(5), player, player));
  }

  private void refresh(Key key) {
    if (closed || main == null || !refreshLimit.tryAcquire()) return;
    if (!refreshing.add(key)) {
      refreshLimit.release();
      return;
    }
    main.call(
            () -> {
              MainThread.require();
              Player player = closed ? null : Bukkit.getPlayer(key.player());
              return player == null ? "" : renderText(player, key.parameter());
            })
        .whenComplete(
            (value, error) -> {
              try {
                if (!closed && error == null) publish(key, value);
              } finally {
                refreshing.remove(key);
                refreshLimit.release();
              }
            });
  }

  private synchronized void publish(Key key, String value) {
    if (!closed) texts.put(key, new Text(value, System.nanoTime() + 200_000_000L));
  }

  @Override
  public synchronized void close() {
    closed = true;
    texts.invalidateAll();
    refreshing.clear();
  }
}
