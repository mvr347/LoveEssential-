package dev.lovelace.loveessential.content;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * First-session eligibility is durable before authentication; a saved player receipt reconciles SQL
 * retries.
 */
public final class StarterItems implements Listener, AutoCloseable {
  public record Entry(ContentItems item, boolean teleportScroll, int amount) {}

  public record Kit(boolean enabled, String source, List<Entry> items) {
    public Kit {
      items = List.copyOf(items);
    }
  }

  public record Delivery(String state, List<ContentItems> items) {
    public Delivery {
      items = List.copyOf(items);
    }
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final ContentLibrary library;
  private final Set<UUID> busy = new HashSet<>(),
      joinedDuringLoad = new HashSet<>(),
      candidates = new HashSet<>();
  private final NamespacedKey receipt,
      legacy = Objects.requireNonNull(NamespacedKey.fromString("lovetweaks:first_join_received"));
  private SqliteStore.Document<Kit> kit;
  private org.bukkit.scheduler.BukkitTask retry;
  private TweaksStarterBridge tweaksBridge;
  private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> notices =
      com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
          .maximumSize(4096)
          .expireAfterWrite(java.time.Duration.ofMinutes(1))
          .build();

  private void notice(Player p, String key) {
    if (notices.asMap().putIfAbsent(p.getUniqueId() + ":" + key, true) == null) lang.send(p, key);
  }

  public StarterItems(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      ContentLibrary library) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.library = library;
    receipt = new NamespacedKey(plugin, "starter_received");
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public CompletableFuture<Void> load() {
    var tweaks = Bukkit.getPluginManager().getPlugin("LoveTweaks");
    Path config = tweaks == null ? null : tweaks.getDataFolder().toPath().resolve("config.yml");
    return store
        .get("starter-kit", "default", Kit.class)
        .thenCompose(
            saved -> {
              if (saved.isPresent())
                return main.<Void>call(
                    () -> {
                      kit = saved.get();
                      return null;
                    });
              return plugin.async(
                      () -> {
                        var y = new YamlConfiguration();
                        if (config != null && Files.exists(config))
                          try {
                            y.load(config.toFile());
                          } catch (Exception e) {
                            throw new CompletionException(e);
                          }
                        return y;
                      })
                  .thenCompose(
                      y ->
                          main.call(
                              () -> {
                                var texts = new HashMap<String, String>();
                                var entries = new ArrayList<Entry>();
                                var list = y.getMapList("first-join.items");
                                int n = 0;
                                for (var raw : list) {
                                  boolean scroll = Boolean.TRUE.equals(raw.get("teleport-scroll"));
                                  Object amount = raw.get("amount");
                                  int count =
                                      amount instanceof Number number ? number.intValue() : 1;
                                  if (count < 1 || count > 2304)
                                    throw new IllegalArgumentException("content.item-amount");
                                  if (scroll) {
                                    entries.add(new Entry(null, true, count));
                                    continue;
                                  }
                                  var material =
                                      Material.matchMaterial(
                                          Objects.toString(raw.get("material"), "PAPER"));
                                  if (material == null || material.isAir())
                                    throw new IllegalArgumentException("errors.config");
                                  var item = new ItemStack(material);
                                  var meta = item.getItemMeta();
                                  var serializer = LegacyComponentSerializer.legacyAmpersand();
                                  if (raw.get("display-name") instanceof String name
                                      && !name.isBlank())
                                    meta.displayName(serializer.deserialize(name));
                                  if (raw.get("lore") instanceof List<?> lore)
                                    meta.lore(
                                        lore.stream()
                                            .map(Object::toString)
                                            .map(serializer::deserialize)
                                            .toList());
                                  item.setItemMeta(meta);
                                  entries.add(
                                      new Entry(
                                          ContentItems.capture(
                                              item, "content-text.starter.imported." + n++, texts),
                                          false,
                                          count));
                                }
                                if (!y.contains("first-join")) {
                                  entries.add(
                                      new Entry(
                                          ContentItems.capture(
                                              new ItemStack(Material.IRON_SWORD),
                                              "content-text.starter.sword",
                                              texts),
                                          false,
                                          1));
                                  entries.add(
                                      new Entry(
                                          ContentItems.capture(
                                              new ItemStack(Material.COOKED_BEEF),
                                              "content-text.starter.food",
                                              texts),
                                          false,
                                          16));
                                }
                                return new Migration(
                                    new Kit(
                                        y.getBoolean("first-join.enabled", true),
                                        config == null ? "LoveEssential" : config.toString(),
                                        entries),
                                    texts);
                              }))
                  .thenCompose(
                      m ->
                          library
                              .texts(m.texts())
                              .thenCompose(
                                  v ->
                                      store.save("starter-kit", "default", 0, m.kit(), "migration"))
                              .thenCompose(
                                  rev ->
                                      main.<Void>call(
                                          () -> {
                                            kit =
                                                new SqliteStore.Document<>("default", rev, m.kit());
                                            return null;
                                          })));
            });
  }

  private record Migration(Kit kit, Map<String, String> texts) {}

  public SqliteStore.Document<Kit> kit() {
    return kit;
  }

  public CompletableFuture<Void> save(
      Kit value, long revision, String actor, Map<String, String> texts) {
    if (value.items().size() > 36) throw new IllegalArgumentException("content.limit");
    for (var entry : value.items())
      if (entry.amount() < 1
          || entry.amount() > 2304
          || !entry.teleportScroll() && entry.item() == null)
        throw new IllegalArgumentException("content.item-amount");
    int slots = 0;
    for (var entry : value.items()) {
      int max = entry.teleportScroll() ? 1 : entry.item().item(lang).getMaxStackSize();
      slots += (entry.amount() + max - 1) / max;
    }
    if (slots > 36) throw new IllegalArgumentException("content.starter-too-large");
    return library
        .texts(texts)
        .thenCompose(v -> store.save("starter-kit", "default", revision, value, actor))
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      kit = new SqliteStore.Document<>("default", rev, value);
                      return null;
                    }));
  }

  public void start() {
    tweaksBridge = new TweaksStarterBridge(plugin);
    tweaksBridge.takeOwnership();
    if (plugin.module("starter")) for (var id : joinedDuringLoad) enroll(id);
    joinedDuringLoad.clear();
    retry =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  if (plugin.ready() && plugin.module("starter"))
                    for (var id : List.copyOf(candidates)) {
                      var p = Bukkit.getPlayer(id);
                      if (p != null && love.authenticated(p)) claim(p, false);
                    }
                },
                100,
                200);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void join(PlayerJoinEvent e) {
    var p = e.getPlayer();
    if (!plugin.ready()) {
      if (!p.hasPlayedBefore() && !received(p)) joinedDuringLoad.add(p.getUniqueId());
      return;
    }
    if (!plugin.module("starter")) return;
    candidates.add(p.getUniqueId());
    if (p.hasPlayedBefore() || received(p)) {
      claim(p, false);
      return;
    }
    // Insert once, before any delayed authentication or player save changes hasPlayedBefore().
    enroll(p.getUniqueId());
  }

  private void enroll(UUID id) {
    store
        .get("starter-delivery", id.toString(), Delivery.class)
        .thenCompose(
            d ->
                d.isPresent()
                    ? CompletableFuture.completedFuture(0L)
                    : store.save(
                        "starter-delivery",
                        id.toString(),
                        0,
                        new Delivery("eligible", List.of()),
                        "join"))
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      var p = Bukkit.getPlayer(id);
                      if (p != null) {
                        if (error == null) {
                          candidates.add(id);
                          claim(p, false);
                        } else lang.send(p, "errors.storage");
                      }
                    }));
  }

  @EventHandler
  public void auth(dev.lovelace.lovecore.api.auth.PlayerAuthenticatedEvent e) {
    main.execute(() -> claim(e.player(), false));
  }

  public boolean received(Player p) {
    return p.getPersistentDataContainer().has(receipt, PersistentDataType.BYTE)
        || p.getPersistentDataContainer().has(legacy, PersistentDataType.BYTE);
  }

  public ItemStack preview(Entry entry) {
    ItemStack base;
    if (entry.teleportScroll()) {
      var tweaks = Bukkit.getPluginManager().getPlugin("LoveTweaks");
      if (tweaks == null || !tweaks.isEnabled())
        throw new IllegalArgumentException("content.scroll-unavailable");
      try {
        var factory =
            Class.forName(
                    "me.lovelace.loveTweaks.items.TeleportScroll",
                    true,
                    tweaks.getClass().getClassLoader())
                .getMethod("create");
        Object value = factory.invoke(null);
        if (!(value instanceof ItemStack item) || item.getType().isAir())
          throw new IllegalStateException();
        base = item.clone();
      } catch (ReflectiveOperationException | RuntimeException e) {
        throw new IllegalArgumentException("content.scroll-unavailable", e);
      }
    } else base = entry.item().item(lang);
    base.setAmount(Math.min(entry.amount(), base.getMaxStackSize()));
    return base;
  }

  public void claim(Player p, boolean notify) {
    MainThread.require();
    if (!plugin.ready() || !plugin.allows("starter", p) || !p.isOnline() || !love.authenticated(p))
      return;
    UUID id = p.getUniqueId();
    if (!busy.add(id)) return;
    store
        .get("starter-delivery", id.toString(), Delivery.class)
        .thenCompose(
            saved ->
                main.call(
                    () -> {
                      if (saved.isEmpty()) {
                        candidates.remove(id);
                        if (notify)
                          lang.send(
                              p, received(p) ? "content.starter-received" : "content.no-starter");
                        return CompletableFuture.<Void>completedFuture(null);
                      }
                      var d = saved.get();
                      if (d.value().state().equals("delivered")) {
                        candidates.remove(id);
                        if (notify) lang.send(p, "content.starter-received");
                        return CompletableFuture.<Void>completedFuture(null);
                      }
                      if (received(p)) return finish(id, d.revision());
                      if (!kit.value().enabled() || kit.value().items().isEmpty())
                        return CompletableFuture.<Void>completedFuture(null);
                      if (d.value().state().equals("pending")) return deliver(p, d);
                      if (!p.isOnline() || !love.authenticated(p) || !plugin.allows("starter", p))
                        return CompletableFuture.<Void>completedFuture(null);
                      var items = new ArrayList<ContentItems>();
                      var texts = new HashMap<String, String>();
                      for (var entry : kit.value().items()) {
                        var base = preview(entry);
                        int count = entry.amount();
                        while (count > 0) {
                          var stack = base.clone();
                          stack.setAmount(Math.min(count, stack.getMaxStackSize()));
                          count -= stack.getAmount();
                          if (items.size() >= 36)
                            throw new IllegalArgumentException("content.starter-too-large");
                          if (entry.teleportScroll()) {
                            String hash =
                                UUID.nameUUIDFromBytes(base.serializeAsBytes()).toString();
                            items.add(
                                ContentItems.capture(
                                    stack, "content-text.starter.scroll." + hash, texts));
                          } else items.add(entry.item().amount(stack.getAmount()));
                        }
                      }
                      var pending = new Delivery("pending", items);
                      return library
                          .texts(texts)
                          .thenCompose(
                              v ->
                                  store.save(
                                      "starter-delivery",
                                      id.toString(),
                                      d.revision(),
                                      pending,
                                      "starter"))
                          .thenCompose(
                              rev ->
                                  main.call(
                                      () ->
                                          deliver(
                                              p,
                                              new SqliteStore.Document<>(
                                                  id.toString(), rev, pending))))
                          .thenCompose(f -> f);
                    }))
        .thenCompose(f -> f)
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      busy.remove(id);
                      if (error != null && p.isOnline()) {
                        var cause = error;
                        while (cause.getCause() != null) cause = cause.getCause();
                        String key = cause.getMessage();
                        if (notify) lang.send(p, lang.contains(key) ? key : "errors.storage");
                        else notice(p, lang.contains(key) ? key : "errors.storage");
                      }
                    }));
  }

  private CompletableFuture<Void> finish(UUID id, long rev) {
    return store
        .save(
            "starter-delivery", id.toString(), rev, new Delivery("delivered", List.of()), "starter")
        .thenApply(v -> null);
  }

  private CompletableFuture<Void> deliver(Player p, SqliteStore.Document<Delivery> d) {
    if (!p.isOnline() || !plugin.ready() || !plugin.allows("starter", p) || !love.authenticated(p))
      return CompletableFuture.completedFuture(null);
    if (received(p)) return finish(p.getUniqueId(), d.revision());
    var stacks = d.value().items().stream().map(i -> i.item(lang)).toList();
    ItemStack[] before = p.getInventory().getStorageContents();
    ItemStack[] after = fit(before, stacks);
    if (after == null) {
      notice(p, "content.starter-full");
      return CompletableFuture.completedFuture(null);
    }
    p.getInventory().setStorageContents(after);
    p.getPersistentDataContainer().set(receipt, PersistentDataType.BYTE, (byte) 1);
    p.getPersistentDataContainer().set(legacy, PersistentDataType.BYTE, (byte) 1);
    try {
      p.saveData();
    } catch (RuntimeException e) {
      p.getInventory().setStorageContents(before);
      p.getPersistentDataContainer().remove(receipt);
      p.getPersistentDataContainer().remove(legacy);
      throw e;
    }
    lang.send(p, "content.starter-given");
    love.stat(p, "essential.starter.received", 1);
    return finish(p.getUniqueId(), d.revision());
  }

  /** Pure packing simulation: never partially grant or drop durable kit items into the world. */
  public static ItemStack[] fit(ItemStack[] original, List<ItemStack> items) {
    ItemStack[] out =
        Arrays.stream(original).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
    for (var item : items) {
      int left = item.getAmount();
      for (var slot : out)
        if (slot != null && slot.isSimilar(item)) {
          int move = Math.min(left, Math.max(0, slot.getMaxStackSize() - slot.getAmount()));
          slot.setAmount(slot.getAmount() + move);
          left -= move;
        }
      for (int i = 0; i < out.length && left > 0; i++)
        if (out[i] == null || out[i].getType().isAir()) {
          out[i] = item.clone();
          out[i].setAmount(Math.min(left, item.getMaxStackSize()));
          left -= out[i].getAmount();
        }
      if (left > 0) return null;
    }
    return out;
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    candidates.remove(e.getPlayer().getUniqueId());
  }

  @Override
  public void close() {
    if (retry != null) retry.cancel();
    if (tweaksBridge != null) tweaksBridge.close();
    HandlerList.unregisterAll(this);
    busy.clear();
    joinedDuringLoad.clear();
    candidates.clear();
    notices.invalidateAll();
  }
}
