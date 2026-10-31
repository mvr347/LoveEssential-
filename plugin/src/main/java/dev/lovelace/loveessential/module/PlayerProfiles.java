package dev.lovelace.loveessential.module;

import com.google.gson.Gson;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.gui.MenuStyle;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.Placeholders;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

public final class PlayerProfiles implements Listener, AutoCloseable {
  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final Lang lang;
  private final LoveBridge love;
  private final Gameplay gameplay;
  private final SqliteStore store;
  private final Placeholders placeholders;
  private final PrivateMessages messages;
  private final InventoryInspector inventories;
  private final Map<UUID, Map<String, String>> snapshots = new ConcurrentHashMap<>();
  private final Map<UUID, java.util.concurrent.CompletableFuture<Void>> snapshotWrites =
      new HashMap<>();
  private final Map<UUID, long[]> blockStatistics = new HashMap<>();
  private final Map<UUID, Menu> menus = new HashMap<>();
  private final Gson gson = new Gson();
  private final ProfilePrivacy privacy;
  private dev.lovelace.loveessential.dialog.NativeDialogs nativeUi;
  private final PlayerReports reports;

  public void nativeDialogs(dev.lovelace.loveessential.dialog.NativeDialogs ui) {
    nativeUi = ui;
    reports.ui(ui);
  }

  public void reportsCommand(Player p, String[] args) {
    reports.command(p, args);
  }

  public void reportCommand(Player p, String name) {
    Player target = messages.target(p, name);
    if (!canView(p, target)) throw new IllegalArgumentException("profile-menu.hidden");
    reports.create(
        p, target.getUniqueId(), target.getName(), () -> access(p) && canView(p, target));
  }

  private BukkitTask task;
  private long ticks;

  private static final class Menu implements dev.lovelace.loveessential.gui.GuiLayout {
    Inventory inventory;
    int sourceSize;
    String menuId = "gui.menu";

    public String guiId() {
      return menuId;
    }

    public Map<Integer, ?> guiActions() {
      return actions;
    }

    public void guiPut(int slot, Runnable action) {
      actions.put(slot, action);
    }

    final Map<Integer, Runnable> actions = new HashMap<>();
    final Map<Integer, java.util.function.Consumer<ClickType>> choices = new HashMap<>();
    final Player viewer;
    final BooleanSupplier allowed;
    UUID subject;
    String tab = "";
    /** Which section each statistics button currently shows (category id to index). */
    final Map<String, Integer> sections = new HashMap<>();

    public Object guiRoute() {
      return List.of(menuId, subject == null ? viewer.getUniqueId() : subject);
    }

    Map<String, String> variables = Map.of();

    public Map<String, String> guiVariables() {
      return variables;
    }

    Runnable repaint;
    Runnable resume = () -> {};

    public void guiResume() {
      resume.run();
    }

    public boolean guiAllowed(Player player) {
      return allowed.getAsBoolean();
    }

    Menu(Player viewer, BooleanSupplier allowed) {
      this.viewer = viewer;
      this.allowed = allowed;
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }
  }

  public PlayerProfiles(
      LoveEssentialPlugin plugin,
      Lang lang,
      LoveBridge love,
      Gameplay gameplay,
      SqliteStore store,
      Placeholders placeholders,
      PrivateMessages messages,
      InventoryInspector inventories) {
    this.plugin = plugin;
    this.main = new MainThread(plugin);
    this.lang = lang;
    this.love = love;
    this.gameplay = gameplay;
    this.store = store;
    this.placeholders = placeholders;
    this.messages = messages;
    this.inventories = inventories;
    reports = new PlayerReports(plugin, store, lang, love);
    privacy = new ProfilePrivacy(plugin, store, love, lang);
    privacy.changed(this::protectOpenMenus);
  }

  public void start() {
    privacy.start();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    task =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  ticks++;
                  if (ticks
                          % Math.max(1, plugin.getConfig().getInt("profile.gui.refresh-ticks", 20))
                      == 0) {
                    for (var m : List.copyOf(menus.values()))
                      if (!permitted(m)) m.viewer.closeInventory();
                      else if (m.repaint != null) repaint(m);
                    for (Player p : Bukkit.getOnlinePlayers())
                      if (!love.authenticated(p)) snapshots.remove(p.getUniqueId());
                  }
                  if (ticks % Math.max(20, plugin.getConfig().getInt("profile.refresh-ticks", 40))
                      == 0) {
                    for (Player p : Bukkit.getOnlinePlayers())
                      if (love.authenticated(p)) capture(p);
                  }
                  if (ticks
                          % Math.max(
                              1200, plugin.getConfig().getInt("profile.persist-seconds", 300) * 20L)
                      == 0) snapshots.forEach(this::persist);
                },
                1,
                1);
  }

  /** Immutable snapshots make numeric/identity PAPI placeholders safe for async consumers. */
  public Map<String, String> values(UUID id) {
    var result = new HashMap<>(snapshots.getOrDefault(id, Map.of()));
    result.putAll(privacy.values(id));
    result.put(
        "profile_visibility_label",
        lang.text(
            Boolean.parseBoolean(result.get("profile_visible"))
                ? "profile-menu.visibility-public"
                : "profile-menu.visibility-private"));
    result.put(
        "activity_visibility_label",
        lang.text(
            Boolean.parseBoolean(result.get("activity_enabled"))
                ? "profile-menu.visibility-public"
                : "profile-menu.visibility-private"));
    return Map.copyOf(result);
  }

  private void capture(Player p) {
    var m = new HashMap<String, String>();
    m.put("player", p.getName());
    m.put("uuid", p.getUniqueId().toString());
    m.put("display_name", PlainTextComponentSerializer.plainText().serialize(p.displayName()));
    m.put("world", p.getWorld().getName());
    var loc = p.getLocation();
    m.put("x", "" + loc.getBlockX());
    m.put("y", "" + loc.getBlockY());
    m.put("z", "" + loc.getBlockZ());
    m.put("health", number(p.getHealth()));
    var health = p.getAttribute(Attribute.MAX_HEALTH);
    m.put("max_health", number(health == null ? 20 : health.getValue()));
    m.put("food", "" + p.getFoodLevel());
    m.put("saturation", number(p.getSaturation()));
    m.put("level", "" + p.getLevel());
    m.put("exp", "" + Math.round(p.getExp() * 100));
    m.put("total_exp", "" + p.getTotalExperience());
    m.put("ping", "" + p.getPing());
    m.put("gamemode", p.getGameMode().name().toLowerCase(Locale.ROOT));
    m.put("gamemode_name", lang.text("players.gamemodes." + m.get("gamemode")));
    m.put("flying", "" + p.isFlying());
    m.put("flight_allowed", "" + p.getAllowFlight());
    m.put("afk", "" + gameplay.afk(p));
    m.put("vanished", "" + gameplay.vanished(p));
    m.put("authenticated", "" + love.authenticated(p));
    m.put("combat", "" + love.combat(p));
    m.put("clan", love.clan(p).orElse(""));
    m.put("territory", love.territory(p));
    var reputation = love.reputation(p);
    m.put("reputation", reputation.isPresent() ? "" + reputation.getAsInt() : "");
    m.put("balance", love.economy().map(e -> "" + e.balance(p)).orElse(""));
    m.put("currency", love.economy().map(e -> e.currencyName()).orElse(""));
    m.put("deaths", "" + p.getStatistic(Statistic.DEATHS));
    m.put("kills", "" + p.getStatistic(Statistic.PLAYER_KILLS));
    m.put("mob_kills", "" + p.getStatistic(Statistic.MOB_KILLS));
    m.put("jumps", "" + p.getStatistic(Statistic.JUMP));
    m.put("damage_dealt", number(p.getStatistic(Statistic.DAMAGE_DEALT) / 10.0));
    m.put("damage_taken", number(p.getStatistic(Statistic.DAMAGE_TAKEN) / 10.0));
    m.put("walk_km", number(p.getStatistic(Statistic.WALK_ONE_CM) / 100000.0));
    m.put(
        "kd_ratio",
        number(
            p.getStatistic(Statistic.PLAYER_KILLS)
                / (double) Math.max(1, p.getStatistic(Statistic.DEATHS))));
    m.put("sprint_km", number(p.getStatistic(Statistic.SPRINT_ONE_CM) / 100000.0));
    m.put("swim_km", number(p.getStatistic(Statistic.SWIM_ONE_CM) / 100000.0));
    m.put("fly_km", number(p.getStatistic(Statistic.AVIATE_ONE_CM) / 100000.0));
    m.put("time_since_death_hours", number(p.getStatistic(Statistic.TIME_SINCE_DEATH) / 72000.0));
    m.put("fish_caught", "" + p.getStatistic(Statistic.FISH_CAUGHT));
    m.put("animals_bred", "" + p.getStatistic(Statistic.ANIMALS_BRED));
    m.put("villager_trades", "" + p.getStatistic(Statistic.TRADED_WITH_VILLAGER));
    var aggregate = blockStatistics.get(p.getUniqueId());
    if (aggregate == null
        || System.nanoTime() - aggregate[0] > java.time.Duration.ofSeconds(30).toNanos()) {
      long mined = 0, used = 0, crafted = 0, broken = 0;
      for (var material : Material.values()) {
        if (material.isLegacy()) continue;
        if (material.isBlock()) {
          mined += p.getStatistic(Statistic.MINE_BLOCK, material);
          if (material.isItem()) used += p.getStatistic(Statistic.USE_ITEM, material);
        }
        if (material.isItem()) {
          crafted += p.getStatistic(Statistic.CRAFT_ITEM, material);
          if (material.getMaxDurability() > 0) broken += p.getStatistic(Statistic.BREAK_ITEM, material);
        }
      }
      aggregate = new long[] {System.nanoTime(), mined, used, crafted, broken};
      blockStatistics.put(p.getUniqueId(), aggregate);
    }
    m.put("blocks_mined", "" + aggregate[1]);
    m.put("blocks_used", "" + aggregate[2]);
    m.put("items_crafted", "" + aggregate[3]);
    m.put("tools_broken", "" + aggregate[4]);
    // More of Minecraft's own counters, for the statistics sections (distances in km, falls in metres).
    m.put("sessions", "" + p.getStatistic(Statistic.LEAVE_GAME));
    m.put("sleeps", "" + p.getStatistic(Statistic.SLEEP_IN_BED));
    m.put("boat_km", number(p.getStatistic(Statistic.BOAT_ONE_CM) / 100000.0));
    m.put("horse_km", number(p.getStatistic(Statistic.HORSE_ONE_CM) / 100000.0));
    m.put("minecart_km", number(p.getStatistic(Statistic.MINECART_ONE_CM) / 100000.0));
    m.put("crouch_km", number(p.getStatistic(Statistic.CROUCH_ONE_CM) / 100000.0));
    m.put("fall_m", number(p.getStatistic(Statistic.FALL_ONE_CM) / 100.0));
    m.put("items_dropped", "" + p.getStatistic(Statistic.DROP_COUNT));
    m.put("items_enchanted", "" + p.getStatistic(Statistic.ITEM_ENCHANTED));
    m.put("villager_talks", "" + p.getStatistic(Statistic.TALKED_TO_VILLAGER));
    m.put("bell_rings", "" + p.getStatistic(Statistic.BELL_RING));
    m.put("shield_blocked", number(p.getStatistic(Statistic.DAMAGE_BLOCKED_BY_SHIELD) / 10.0));
    m.put("target_hits", "" + p.getStatistic(Statistic.TARGET_HIT));
    // The server tax rates, in percent: for trading with the server and between players.
    love.service(dev.lovelace.lovecore.api.economy.TaxOracle.class)
        .ifPresent(
            tax -> {
              m.put("tax_rate", number(tax.rate(p.getUniqueId()) * 100.0));
              m.put("trade_tax_rate", number(tax.tradeRate(p.getUniqueId()) * 100.0));
            });
    long seconds = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
    m.put("playtime_seconds", "" + seconds);
    m.put("playtime_hours", "" + seconds / 3600);
    m.put("playtime_minutes", "" + seconds / 60 % 60);
    m.put("online_state", lang.text("profile-menu.online"));
    m.put("first_join", Instant.ofEpochMilli(p.getFirstPlayed()).toString());
    m.put("last_seen", Instant.now().toString());
    m.put("first_join_local", localDate(m.get("first_join")));
    m.put("last_seen_local", localDate(m.get("last_seen")));
    m.put("homes", "" + gameplay.names(p, "home").size());
    var textures = p.getPlayerProfile().getTextures();
    m.put("skin_url", textures.getSkin() == null ? "" : textures.getSkin().toExternalForm());
    m.put("skin_model", textures.getSkinModel().name().toLowerCase(Locale.ROOT));
    for (String flag :
        List.of("afk", "vanished", "authenticated", "combat", "flying", "flight_allowed"))
      m.put(
          flag + "_state",
          lang.text("players." + (Boolean.parseBoolean(m.get(flag)) ? "yes" : "no")));
    for (String id :
        List.of(
            "loveadaptation_current_name",
            "loveadaptation_current_progress",
            "loveadaptation_progress_next_adaptation",
            "lovebehavior_politeness_name",
            "lovebehavior_playstyle_name",
            "lovesubnames_current")) {
      m.put("external_" + id, savedPlaceholder(p, id));
    }
    for (String type : List.of("water", "nether", "cave", "height", "abyss", "combat", "travel")) {
      for (String field : List.of("status", "progress")) {
        String id = "loveadaptation_" + type + "_" + field;
        m.put("external_" + id, savedPlaceholder(p, id));
      }
    }
    var statistics = plugin.getConfig().getConfigurationSection("profile.statistics.placeholders");
    if (statistics != null)
      for (String alias : statistics.getKeys(false)) {
        String id = statistics.getString(alias, "");
        if (alias.matches("[a-z0-9_]{1,100}") && id.matches("[A-Za-z0-9_]{1,150}")) {
          String raw = savedPlaceholder(p, id);
          m.put(
              alias,
              raw.isBlank() || Set.of("ERROR", "N/A", "None").contains(raw)
                  ? lang.text("profile-menu.no-data")
                  : PlainTextComponentSerializer.plainText()
                      .serialize(placeholders.legacyOrMini(raw)));
        }
      }
    snapshots.put(p.getUniqueId(), Map.copyOf(m));
  }

  private String savedPlaceholder(Player p, String id) {
    String token = "%" + id + "%", value = placeholders.papi(p, p, token);
    return value == null || value.isBlank() || value.equals(token) ? "" : value;
  }

  private static String number(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private final Map<UUID, Map<String, String>> pendingSnapshots = new HashMap<>();
  private boolean closing;

  private void persist(UUID uuid, Map<String, String> data) {
    if (snapshotWrites.containsKey(uuid)) {
      pendingSnapshots.put(uuid, data);
      return;
    }
    var completed = new java.util.concurrent.CompletableFuture<Void>();
    snapshotWrites.put(uuid, completed);
    writeSnapshot(uuid, data, completed);
  }

  private void writeSnapshot(
      UUID uuid, Map<String, String> data, java.util.concurrent.CompletableFuture<Void> completed) {
    store
        .profile(uuid, "loveessential.player-info", gson.toJson(data))
        .whenComplete(
            (value, error) ->
                main
                    .execute(
                        () -> {
                          if (error != null)
                            plugin
                                .getLogger()
                                .log(
                                    java.util.logging.Level.WARNING,
                                    lang.text("players.save-failed"),
                                    error);
                          var latest = pendingSnapshots.remove(uuid);
                          if (latest != null && !closing) {
                            writeSnapshot(uuid, latest, completed);
                            return;
                          }
                          snapshotWrites.remove(uuid, completed);
                          if (error == null) completed.complete(null);
                          else completed.completeExceptionally(error);
                        }));
  }

  private static String localDate(String value) {
    try {
      return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
          .withZone(java.time.ZoneId.of("Europe/Kiev"))
          .format(Instant.parse(value));
    } catch (RuntimeException e) {
      return value;
    }
  }

  private record Saved(UUID id, Map<String, String> data, ProfilePrivacy.Preferences preferences) {}

  private final Map<UUID, UUID> requests = new HashMap<>();

  public void cancelPending(Player viewer) {
    requests.remove(viewer.getUniqueId());
  }

  public void openProfile(Player viewer, String name) {
    MainThread.require();
    UUID request = UUID.randomUUID();
    requests.put(viewer.getUniqueId(), request);
    Player online = Bukkit.getPlayerExact(name);
    if (online != null) {
      profile(viewer, online);
      return;
    }
    var previousInventory = viewer.getOpenInventory().getTopInventory();
    lang.send(viewer, "profile-menu.offline-loading");
    java.util.concurrent.CompletableFuture.allOf(
            snapshotWrites.values().toArray(java.util.concurrent.CompletableFuture[]::new))
        .thenCompose(ignored -> store.findPlayerProfile(name))
        .thenCompose(
            saved -> {
              if (saved.isEmpty())
                return java.util.concurrent.CompletableFuture.completedFuture((Saved) null);
              var row = saved.get();
              @SuppressWarnings("unchecked")
              Map<String, String> data =
                  gson.fromJson(
                      row.json(),
                      new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
              return main
                  .call(() -> privacy.offline(row.id()))
                  .thenCompose(f -> f)
                  .thenApply(prefs -> new Saved(row.id(), Map.copyOf(data), prefs));
            })
        .whenComplete(
            (saved, error) ->
                main
                    .execute(
                        () -> {
                          if (!request.equals(requests.get(viewer.getUniqueId()))
                              || !viewer.isOnline()
                              || !access(viewer)
                              || viewer.getOpenInventory().getTopInventory() != previousInventory)
                            return;
                          requests.remove(viewer.getUniqueId());
                          if (error != null) {
                            lang.send(viewer, "errors.storage");
                            return;
                          }
                          if (saved == null) {
                            lang.send(viewer, "profile-menu.no-snapshot");
                            return;
                          }
                          Player current = Bukkit.getPlayer(saved.id());
                          if (current != null) {
                            if (canView(viewer, current)) profile(viewer, current);
                            else lang.send(viewer, "profile-menu.hidden");
                            return;
                          }
                          if (!saved.preferences().profileVisible()
                              && !viewer.getUniqueId().equals(saved.id())) {
                            lang.send(viewer, "profile-menu.hidden");
                            return;
                          }
                          offlineProfile(viewer, saved);
                        }));
  }

  private boolean savedAllowed(Player viewer, Saved saved, boolean activity) {
    return access(viewer)
        && Bukkit.getPlayer(saved.id()) == null
        && (viewer.getUniqueId().equals(saved.id())
            || saved.preferences().profileVisible()
                && (!activity || saved.preferences().activityVisible()));
  }

  private Map<String, String> savedValues(Saved saved) {
    var values = new HashMap<>(displayValues(saved.data()));
    for (String id :
        List.of(
            "kd_ratio",
            "sprint_km",
            "swim_km",
            "fly_km",
            "time_since_death_hours",
            "fish_caught",
            "animals_bred",
            "villager_trades",
            "blocks_mined",
            "blocks_used",
            "balance",
            "currency",
            "homes")) {
      if (values.getOrDefault(id, "").isBlank()) values.put(id, lang.text("profile-menu.no-data"));
    }
    values.put("online_state", lang.text("profile-menu.offline"));
    values.put("last_seen_local", localDate(values.getOrDefault("last_seen", "")));
    values.put("first_join_local", localDate(values.getOrDefault("first_join", "")));
    for (String id :
        List.of(
            "lovesubnames_current",
            "loveadaptation_current_name",
            "loveadaptation_current_progress",
            "lovebehavior_politeness_name",
            "lovebehavior_playstyle_name")) {
      values.putIfAbsent("external_" + id, "");
    }
    for (var entry : List.copyOf(values.entrySet()))
      if (entry.getKey().startsWith("external_")) {
        values.put(
            entry.getKey(),
            entry.getValue().isBlank()
                ? lang.text("profile-menu.no-data")
                : PlainTextComponentSerializer.plainText()
                    .serialize(placeholders.legacyOrMini(entry.getValue())));
      }
    return values;
  }

  private ItemStack savedHead(Saved saved) {
    var vars = savedValues(saved);
    var item =
        MenuStyle.item(
            Material.PLAYER_HEAD,
            lang.render("profile-menu.saved-heading", vars),
            lang.lines("profile-menu.saved-heading-lore").stream()
                .map(t -> lang.renderText(t, vars))
                .toList());
    String url = saved.data().getOrDefault("skin_url", "");
    if (!url.isBlank()) {
      try {
        var uri = java.net.URI.create(url);
        if ("textures.minecraft.net".equalsIgnoreCase(uri.getHost())
            && uri.getPath().matches("/texture/[a-fA-F0-9]{32,64}")) {
          var profile =
              Bukkit.createProfile(saved.id(), saved.data().getOrDefault("player", "Player"));
          var textures = profile.getTextures();
          textures.setSkin(
              uri.toURL(),
              org.bukkit.profile.PlayerTextures.SkinModel.valueOf(
                  saved.data().getOrDefault("skin_model", "classic").toUpperCase(Locale.ROOT)));
          profile.setTextures(textures);
          var meta = (SkullMeta) item.getItemMeta();
          meta.setPlayerProfile(profile);
          item.setItemMeta(meta);
        }
      } catch (java.net.MalformedURLException | IllegalArgumentException ignored) {
      }
    }
    return item;
  }

  private void offlineProfile(Player viewer, Saved saved) {
    var m =
        menu(
            viewer,
            "public-profile.title",
            Map.of("player", saved.data().getOrDefault("player", "")),
            () -> savedAllowed(viewer, saved, false));
    m.subject = saved.id();
    m.inventory.setItem(0, savedHead(saved));
    m.variables = savedValues(saved);
    drawSummary(m, m.variables, false, saved.preferences().activityVisible());
    button(
        m,
        3,
        Material.SUNFLOWER,
        "profile-menu.reputation",
        "public-profile.offline-feedback",
        () -> {
          throw new IllegalArgumentException("public-profile.offline-feedback");
        });
    button(
        m,
        5,
        Material.WRITABLE_BOOK,
        "public-profile.report",
        "public-profile.report-help",
        () ->
            reports.create(
                viewer,
                saved.id(),
                saved.data().getOrDefault("player", ""),
                () -> savedAllowed(viewer, saved, false)));
    button(
        m,
        m.inventory.getSize() - 3,
        Material.ARROW,
        "common.back",
        "gui.click.open",
        () -> profile(viewer, viewer));
    show(m);
  }

  private List<String> statisticCategories() {
    var config = plugin.getConfig().getConfigurationSection("profile.statistics.categories");
    if (config == null) return List.of();
    return config.getKeys(false).stream()
        .filter(id -> config.getBoolean(id + ".enabled", true))
        .filter(
            id -> {
              String owner = config.getString(id + ".plugin", "");
              return owner.isBlank()
                  || love.installed(owner)
                  || !plugin.getConfig().getBoolean("profile.statistics.hide-unavailable", true);
            })
        .toList();
  }

  private int contentSlot(Menu m, int index, int count) {
    return MenuStyle.slot(index, count) + (m.inventory.getSize() >= 45 ? 0 : -9);
  }

  private int contentCapacity(Menu m) {
    return (m.inventory.getSize() / 9 - (m.inventory.getSize() >= 45 ? 3 : 2)) * 7;
  }

  private String statisticPath(String id) {
    return "profile.statistics.categories." + id;
  }

  private List<Component> snapshotLines(String key, Map<String, String> values) {
    return lang.lines(key).stream().map(t -> lang.renderText(t, values)).toList();
  }

  private ItemStack statisticIcon(
      String path, String title, String lore, Map<String, String> values) {
    String texture =
        plugin
            .getConfig()
            .getString(
                path + ".texture",
                plugin
                    .getConfig()
                    .getString(
                        "profile.gui.textures.profile-menu.statistics",
                        "8672085ff5df0ee852136e2d41a769229291d666b28e9fb04d6c92f158e42bb"));
    return MenuStyle.head(texture, lang.render(title, values), snapshotLines(lore, values));
  }

  private void drawSummary(Menu m, Map<String, String> values, boolean own, boolean activity) {
    m.variables = values;
    var ids = statisticCategories();
    int count = Math.min(contentCapacity(m), ids.size());
    for (int i = 0; i < count; i++) {
      String id = ids.get(i), path = statisticPath(id);
      int slot = contentSlot(m, i, count);
      String title =
          plugin.getConfig().getString(path + ".title-key", "profile-stats." + id + ".title");
      boolean sensitive = plugin.getConfig().getBoolean(path + ".activity-sensitive", false);
      if (!own && sensitive && !activity) {
        m.inventory.setItem(
            slot,
            icon(
                "disabled",
                Material.GRAY_DYE,
                lang.render(title),
                List.of(lang.render("profile-menu.activity-hidden-lore"))));
        continue;
      }
      String owner = plugin.getConfig().getString(path + ".plugin", "");
      if (!owner.isBlank() && !love.installed(owner)) {
        m.inventory.setItem(
            slot,
            icon(
                "disabled",
                Material.GRAY_DYE,
                lang.render(title),
                List.of(lang.render("profile-menu.integration-unavailable"))));
        continue;
      }
      String lore =
          plugin
              .getConfig()
              .getString(
                  path + (own ? ".summary-key" : ".public-key"),
                  "profile-stats." + id + (own ? ".summary" : ".public"));
      m.inventory.setItem(slot, statisticIcon(path, title, lore, values));
    }
  }

  private record Metric(String label, String value, String variable, String group) {}

  private static final java.util.regex.Pattern METRIC =
      java.util.regex.Pattern.compile("^<gray>([^:<]+):\\s*(.+)$");
  private static final java.util.regex.Pattern VARIABLE =
      java.util.regex.Pattern.compile("<([a-z][a-z0-9_]*)>");

  /**
   * Metrics of one category, one entry per "Label: <value>" line of its detail pages; the lines
   * stay in the language file, so administrators edit them there.
   */
  private List<List<Metric>> metricRows(String id, Map<String, String> values) {
    String path = statisticPath(id);
    var details = plugin.getConfig().getConfigurationSection(path + ".details");
    var groups = new ArrayList<List<Metric>>();
    if (details == null) return groups;
    for (String detail : details.getKeys(false)) {
      String prefix = path + ".details." + detail;
      // A section can belong to its own plugin (a category mixes several): without it the section is left out.
      String owner = plugin.getConfig().getString(prefix + ".plugin", "");
      if (!owner.isBlank()
          && !love.installed(owner)
          && plugin.getConfig().getBoolean("profile.statistics.hide-unavailable", true)) continue;
      String loreKey =
          plugin.getConfig().getString(prefix + ".lore-key", "profile-stats." + id + "." + detail + ".lore");
      String group =
          plugin
              .getConfig()
              .getString(prefix + ".title-key", "profile-stats." + id + "." + detail + ".title");
      var row = new ArrayList<Metric>();
      for (String line : lang.lines(loreKey)) {
        var match = METRIC.matcher(line);
        if (!match.matches()) continue;
        var variable = VARIABLE.matcher(match.group(2));
        // A line without a known variable is a note, not a number.
        if (!variable.find() || !values.containsKey(variable.group(1))) continue;
        row.add(new Metric(match.group(1), match.group(2), variable.group(1), group));
      }
      if (!row.isEmpty()) groups.add(row);
    }
    return groups;
  }

  private static ItemStack glow(ItemStack item) {
    var meta = item.getItemMeta();
    meta.setEnchantmentGlintOverride(true);
    item.setItemMeta(meta);
    return item;
  }

  /** Own statistics: one button per category, each leafing through its sections in the lore. */
  public void statistics(Player viewer, Player target) {
    if (!canView(viewer, target)) throw new IllegalArgumentException("profile-menu.hidden");
    if (!viewer.equals(target)) {
      profile(viewer, target);
      return;
    }
    var m = menu(viewer, "profile-menu.statistics", Map.of(), () -> access(viewer));
    m.subject = viewer.getUniqueId();
    m.repaint = () -> drawStatistics(m, viewer);
    drawStatistics(m, viewer);
    show(m);
  }

  /** A category that has something to show, with its sections as lists of metrics. */
  private record Card(String id, List<List<Metric>> sections) {}

  /**
   * Slots of the 3x3 block reserved for the category buttons (rows 2-4, columns 5-7), by how many buttons
   * there are. Five, the shipped count, sit like the dots on a die; an admin who kept the six categories
   * of the old layout still gets all six.
   */
  private static final int[][] STAT_SLOTS = {
    {},
    {23},
    {22, 24},
    {13, 23, 33},
    {13, 15, 31, 33},
    {13, 15, 23, 31, 33},
    {13, 15, 22, 24, 31, 33}
  };

  private static final int STAT_BUTTONS = STAT_SLOTS.length - 1;

  private void drawStatistics(Menu m, Player viewer) {
    capture(viewer);
    var values = displayValues(snapshots.get(viewer.getUniqueId()));
    m.variables = values;
    MenuStyle.frame(m.inventory);
    m.actions.clear();
    m.choices.clear();
    m.inventory.setItem(
        0,
        head(
            viewer,
            placeholders.render("profile-menu.heading", viewer, viewer),
            textLines("profile-menu.heading-lore", viewer, viewer)));
    // Owner's layout for this menu: back and close in the last two slots of the bottom row.
    int last = m.inventory.getSize() - 1;
    button(m, last - 1, Material.ARROW, "common.back", "gui.click.open", () -> profile(viewer, viewer));
    button(m, last, Material.BARRIER, "gui.close", "gui.click.close", viewer::closeInventory);
    var cards = new ArrayList<Card>();
    for (String id : statisticCategories().stream().limit(STAT_BUTTONS).toList()) {
      var sections = metricRows(id, values);
      if (!sections.isEmpty()) cards.add(new Card(id, sections));
    }
    if (cards.isEmpty()) {
      m.inventory.setItem(
          23,
          icon(
              "disabled",
              Material.GRAY_DYE,
              lang.render("profile-menu.stats-empty"),
              List.of(lang.render("profile-menu.integration-unavailable"))));
    } else {
      int[] slots = STAT_SLOTS[cards.size()];
      for (int i = 0; i < cards.size(); i++) statisticButton(m, cards.get(i), slots[i], values);
    }
    if (menus.get(viewer.getUniqueId()) == m) MenuStyle.apply(viewer, m.inventory);
  }

  /** One category button: the current section's metrics, a trail of all sections, and left/right to leaf. */
  private void statisticButton(Menu m, Card card, int slot, Map<String, String> values) {
    String id = card.id(), path = statisticPath(id);
    int count = card.sections().size();
    int index = StatCycle.clamp(m.sections.getOrDefault(id, 0), count);
    var current = card.sections().get(index);
    String title = plugin.getConfig().getString(path + ".title-key", "profile-stats." + id + ".title");
    String texture =
        plugin
            .getConfig()
            .getString(
                path + ".texture",
                plugin
                    .getConfig()
                    .getString(
                        "profile.gui.textures.profile-menu.statistics",
                        "8672085ff5df0ee852136e2d41a769229291d666b28e9fb04d6c92f158e42bb"));

    var lore = new ArrayList<Component>();
    lore.add(
        lang.renderText(
            lang.text("profile-menu.section-line"),
            Map.of(
                "name",
                plain(lang.render(current.getFirst().group(), values)),
                "index",
                String.valueOf(index + 1),
                "count",
                String.valueOf(count))));
    if (count > 1) {
      var trail = Component.text();
      for (int i = 0; i < count; i++) {
        if (i > 0) trail.append(lang.render("profile-menu.section-divider"));
        String name = plain(lang.render(card.sections().get(i).getFirst().group(), values));
        trail.append(
            lang.renderText(
                lang.text(i == index ? "profile-menu.section-current" : "profile-menu.section-other"),
                Map.of("name", name)));
      }
      lore.add(trail.build());
    }
    lore.add(lang.render("gui.separator"));
    for (var metric : current)
      lore.add(
          lang.renderText(
              lang.text("profile-menu.metric-line"),
              Map.of("label", metric.label(), "value", plain(lang.renderText(metric.value(), values)))));
    lore.add(lang.render("gui.separator"));
    lore.add(lang.render(count > 1 ? "profile-menu.cycle-hint" : "profile-menu.cycle-single"));

    m.inventory.setItem(slot, MenuStyle.head(texture, lang.render(title, values), lore));
    if (count > 1)
      m.choices.put(
          slot,
          click -> {
            int now = StatCycle.clamp(m.sections.getOrDefault(id, 0), count);
            m.sections.put(
                id, click == ClickType.RIGHT ? StatCycle.previous(now, count) : StatCycle.next(now, count));
            repaint(m);
          });
  }

  private static String plain(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  private Map<String, String> displayValues(Map<String, String> source) {
    var values = new HashMap<>(Objects.requireNonNullElse(source, Map.<String, String>of()));
    var aliases = plugin.getConfig().getConfigurationSection("profile.statistics.placeholders");
    if (aliases != null)
      for (String alias : aliases.getKeys(false))
        values.putIfAbsent(alias, lang.text("profile-menu.no-data"));
    for (String key :
        List.of(
            "kd_ratio",
            "playtime_hours",
            "playtime_minutes",
            "kills",
            "deaths",
            "mob_kills",
            "blocks_mined",
            "blocks_used",
            "walk_km",
            "sprint_km",
            "swim_km",
            "fly_km",
            "jumps",
            "damage_dealt",
            "damage_taken",
            "time_since_death_hours",
            "fish_caught",
            "animals_bred",
            "villager_trades",
            "balance",
            "currency",
            "homes",
            "first_join_local",
            "last_seen_local")) values.putIfAbsent(key, lang.text("profile-menu.no-data"));
    for (var entry : List.copyOf(values.entrySet()))
      if (entry.getValue().isBlank()) values.put(entry.getKey(), lang.text("profile-menu.no-data"));
    return Map.copyOf(values);
  }

  public static ItemStack head(Player target, Component title, List<Component> lore) {
    var item = MenuStyle.item(Material.PLAYER_HEAD, title, lore);
    var meta = (SkullMeta) item.getItemMeta();
    meta.setPlayerProfile(target.getPlayerProfile());
    item.setItemMeta(meta);
    return item;
  }

  private boolean visible(Player viewer, Player target) {
    return target.isOnline() && viewer.canSee(target) && love.authenticated(target);
  }

  private boolean permitted(Menu m) {
    return plugin.ready()
        && m.viewer.isOnline()
        && love.authenticated(m.viewer)
        && m.allowed.getAsBoolean();
  }

  private Menu menu(
      Player viewer, String title, Map<String, String> vars, BooleanSupplier allowed) {
    requests.remove(viewer.getUniqueId());
    var m = new Menu(viewer, allowed);
    if (!permitted(m)) throw new IllegalArgumentException("errors.permission");
    // Profile pages are 45 slots (two work rows); the statistics menu and every other profile menu are 54.
    int size =
        Set.of("profile-menu.title", "public-profile.title").contains(title)
            ? 45
            : 54;
    if (!Set.of(27, 36, 45, 54).contains(size))
      throw new IllegalArgumentException("gui-layout.invalid");
    m.resume =
        () -> {
          menus.put(viewer.getUniqueId(), m);
          if (m.repaint != null) repaint(m);
        };
    m.menuId = title;
    m.sourceSize = size;
    m.inventory = Bukkit.createInventory(m, size, MenuStyle.title(lang, title, vars));
    MenuStyle.frame(m.inventory);
    m.inventory.setItem(0, head(viewer, lang.render(title, vars), List.of()));
    button(m, size - 2, Material.BARRIER, "gui.close", "gui.click.close", viewer::closeInventory);
    return m;
  }

  private void repaint(Menu m) {
    if (m.repaint == null) return;
    var original = m.inventory;
    var staging =
        Bukkit.createInventory(m, m.sourceSize, MenuStyle.title(lang, m.menuId, m.variables));
    m.inventory = staging;
    try {
      m.repaint.run();
      var prepared = MenuStyle.prepare(m.viewer, staging);
      if (prepared.getSize() != original.getSize()) {
        m.inventory = prepared;
        MenuStyle.open(m.viewer, prepared);
        menus.put(m.viewer.getUniqueId(), m);
        return;
      }
      for (int slot = 0; slot < original.getSize(); slot++)
        if (!Objects.equals(original.getItem(slot), prepared.getItem(slot)))
          original.setItem(slot, prepared.getItem(slot));
    } finally {
      if (m.inventory == staging || m.inventory.getSize() == original.getSize())
        m.inventory = original;
    }
  }

  private void show(Menu m) {
    if (MenuStyle.open(m.viewer, m.inventory) != null) menus.put(m.viewer.getUniqueId(), m);
  }

  private void button(Menu m, int slot, Material icon, String title, String hint, Runnable action) {
    m.inventory.setItem(slot, MenuStyle.button(lang, icon, title, "gui.separator", hint));
    m.actions.put(slot, action);
  }

  public void activity(Player viewer, Player target) {
    if (!canView(viewer, target) || !privacy.canViewActivity(viewer, target))
      throw new IllegalArgumentException("profile-menu.activity-hidden");
    var m =
        menu(
            viewer,
            "profile-menu.activity-title",
            Map.of("player", target.getName()),
            () ->
                plugin.module("profile")
                    && canView(viewer, target)
                    && privacy.canViewActivity(viewer, target));
    m.subject = target.getUniqueId();
    capture(target);
    m.inventory.setItem(
        0,
        head(
            target,
            lang.render("players.title", Map.of("player", target.getName())),
            List.of(placeholders.render("nick.profile-name", viewer, target))));
    var cards = plugin.getConfig().getConfigurationSection("profile.gui.cards");
    if (cards != null) {
      var keys =
          cards.getKeys(false).stream()
              .filter(
                  key -> {
                    String permission = cards.getString(key + ".permission", "");
                    return !key.equals("statistics")
                        && cards.getBoolean(key + ".enabled", true)
                        && (permission.isBlank()
                            || viewer.equals(target)
                            || viewer.hasPermission(permission));
                  })
              .limit(21)
              .toList();
      for (int i = 0; i < keys.size(); i++) {
        String key = keys.get(i);
        Material icon = Material.matchMaterial(cards.getString(key + ".material", "BOOK"));
        if (icon == null || !icon.isItem() || icon.isAir()) icon = Material.BOOK;
        Component title =
            placeholders.render(
                cards.getString(key + ".title-key", "players.title"), viewer, target);
        var lore = new ArrayList<Component>();
        for (String lineKey : cards.getStringList(key + ".lore-keys"))
          lore.addAll(placeholders.renderLines(lineKey, viewer, target));
        m.inventory.setItem(
            MenuStyle.slot(i, keys.size()),
            icon == Material.PLAYER_HEAD
                ? head(target, title, lore)
                : MenuStyle.item(icon, title, lore));
      }
    }
    if (viewer.equals(target)) {
      var prefs = privacy.loaded(target.getUniqueId()) ? privacy.preferences(target) : null;
      button(
          m,
          3,
          prefs != null && prefs.profileVisible() ? Material.LIME_DYE : Material.GRAY_DYE,
          prefs != null && prefs.profileVisible()
              ? "profile-menu.visibility-on"
              : "profile-menu.visibility-off",
          "profile-menu.visibility-help",
          () -> changePrivacy(viewer, false, "toggle", () -> activity(viewer, target)));
      button(
          m,
          5,
          prefs != null && prefs.activityVisible() ? Material.LIME_DYE : Material.GRAY_DYE,
          prefs != null && prefs.activityVisible()
              ? "profile-menu.activity-on"
              : "profile-menu.activity-off",
          "profile-menu.activity-help",
          () -> changePrivacy(viewer, true, "toggle", () -> activity(viewer, target)));
    }
    button(m, 51, Material.ARROW, "common.back", "gui.click.open", () -> profile(viewer, target));
    button(
        m, 50, Material.CLOCK, "players.refresh", "gui.click.open", () -> activity(viewer, target));
    show(m);
  }

  private void suggestMessage(Player viewer, Player target) {
    if (!visible(viewer, target)) throw new IllegalArgumentException("players.unavailable");
    viewer.closeInventory();
    viewer.sendMessage(
        lang.render("messages.compose", Map.of("player", target.getName()))
            .clickEvent(ClickEvent.suggestCommand("/le msg " + target.getName() + " ")));
  }

  public ProfilePrivacy privacy() {
    return privacy;
  }

  public boolean canView(Player viewer, Player target) {
    return visible(viewer, target) && privacy.canView(viewer, target);
  }

  private boolean access(Player p) {
    return plugin.ready() && plugin.allows("profile", p) && love.authenticated(p);
  }

  public void command(Player p, String[] args) {
    plugin.requireAccess("profile", p);
    if (!access(p)) throw new IllegalArgumentException("errors.permission");
    if (args.length == 0) {
      profile(p, p);
      return;
    }
    String op = args[0].toLowerCase(Locale.ROOT);
    if (args.length == 1)
      switch (op) {
        case "list" -> {
          players(p, "profile", 0);
          return;
        }
        case "stats", "statistics" -> {
          statistics(p, p);
          return;
        }
        case "settings" -> {
          settings(p);
          return;
        }
        case "activity" -> {
          activity(p, p);
          return;
        }
        case "show", "hide", "toggle" -> {
          changePrivacy(p, false, op, () -> {});
          return;
        }
        default -> {}
      }
    if (args.length == 2 && op.equals("view")) {
      openProfile(p, args[1]);
      return;
    }
    if (args.length == 2 && (op.equals("privacy") || op.equals("activity"))) {
      String value = args[1].toLowerCase(Locale.ROOT);
      if (value.equals("public")) value = "show";
      if (value.equals("private")) value = "hide";
      if (!Set.of("show", "hide", "toggle").contains(value))
        throw new IllegalArgumentException("profile-menu.usage");
      changePrivacy(p, op.equals("activity"), value, () -> {});
      return;
    }
    if (args.length != 1) throw new IllegalArgumentException("profile-menu.usage");
    openProfile(p, args[0]);
  }

  private void changePrivacy(Player p, boolean activity, String value, Runnable done) {
    privacy
        .change(
            p,
            old -> {
              boolean previous = activity ? old.activityVisible() : old.profileVisible();
              boolean next = value.equals("toggle") ? !previous : value.equals("show");
              return new ProfilePrivacy.Preferences(
                  activity ? old.profileVisible() : next, activity ? next : old.activityVisible());
            })
        .whenComplete(
            (saved, error) ->
                main
                    .execute(
                        () -> {
                          if (!p.isOnline()) return;
                          if (error != null) {
                            lang.send(p, "errors.storage");
                            return;
                          }
                          lang.send(
                              p,
                              activity
                                  ? (saved.activityVisible()
                                      ? "profile-menu.activity-public"
                                      : "profile-menu.activity-private")
                                  : (saved.profileVisible()
                                      ? "profile-menu.profile-public"
                                      : "profile-menu.profile-private"));
                          if (access(p)) done.run();
                        }));
  }

  private void protectOpenMenus(UUID target) {
    for (var m : List.copyOf(menus.values()))
      if (target.equals(m.subject) && !target.equals(m.viewer.getUniqueId()))
        m.viewer.closeInventory();
  }

  private List<Component> textLines(String key, Player viewer, Player target) {
    var result = placeholders.renderLines(key, viewer, target);
    if (Set.of("grammar", "silent", "mentions", "channel", "clan-invites", "player-trades").stream()
        .anyMatch(id -> key.equals("profile-menu." + id + "-lore"))) {
      return localizedStates(result);
    }
    return result;
  }

  private List<Component> localizedStates(List<Component> result) {
    return result.stream()
        .map(
            c ->
                c.replaceText(
                    net.kyori.adventure.text.TextReplacementConfig.builder()
                        .match("(?i)\\b(yes|no|true|false|global|local|admin)\\b")
                        .replacement(
                            (match, builder) ->
                                builder.content(
                                    lang.text(
                                        "profile-menu.states."
                                            + match.group().toLowerCase(Locale.ROOT))))
                        .build()))
        .toList();
  }

  private ItemStack icon(String key, Material fallback, Component title, List<Component> lore) {
    String texture = plugin.getConfig().getString("profile.gui.textures." + key, "");
    return texture.isBlank()
        ? MenuStyle.item(fallback, title, lore)
        : MenuStyle.head(texture, title, lore);
  }

  private void card(
      Menu m,
      int slot,
      Material material,
      String title,
      String lore,
      Player target,
      Runnable action) {
    m.inventory.setItem(
        slot,
        icon(
            title,
            material,
            placeholders.render(title, m.viewer, target),
            textLines(lore, m.viewer, target)));
    if (action != null) m.actions.put(slot, action);
  }

  private void link(
      Menu m,
      int slot,
      String id,
      String title,
      String lore,
      Material material,
      Player target,
      boolean close) {
    var baseLines = textLines(lore, m.viewer, target);
    if (Set.of(
            "grammar",
            "silent",
            "mentions",
            "channel",
            "channel-local",
            "clan-invites",
            "player-trades")
        .contains(id)) baseLines = localizedStates(baseLines);
    var lines = new ArrayList<>(baseLines);
    if (!linkAvailable(id, m.viewer))
      lines.add(lang.render("profile-menu.integration-unavailable"));
    String iconKey = title;
    String stateToken =
        switch (id) {
          case "grammar" -> "lovechatfilter_grammar";
          case "silent" -> "lovechat_silent_mode";
          case "mentions" -> "lovechat_mentions_disabled";
          case "clan-invites" -> "clans_invites_enabled";
          case "player-trades" -> "lovetrades_requests_enabled";
          case "channel", "channel-local" -> "lovechat_channel";
          default -> "";
        };
    if (!stateToken.isEmpty()) {
      String state =
          placeholders.papi(m.viewer, target, "%" + stateToken + "%").toLowerCase(Locale.ROOT);
      boolean on = state.equals("yes") || state.equals("true");
      if (id.equals("mentions")) on = state.equals("no") || state.equals("false");
      if (stateToken.equals("lovechat_channel")) {
        if (Set.of("global", "local", "admin").contains(state))
          iconKey = "profile-menu.channel-" + state;
      } else if (!on) iconKey = "disabled";
    }
    m.inventory.setItem(
        slot, icon(iconKey, material, placeholders.render(title, m.viewer, target), lines));
    m.actions.put(slot, () -> invoke(id, m.viewer, target, close));
  }

  private org.bukkit.command.PluginCommand linkCommand(String id) {
    String path = "profile.links." + id;
    String owner = plugin.getConfig().getString(path + ".plugin", "");
    String value = plugin.getConfig().getString(path + ".command", "").strip();
    if (owner.isBlank()
        || value.isBlank()
        || Arrays.stream(Bukkit.getPluginManager().getPlugins())
            .noneMatch(p -> p.isEnabled() && p.getName().equalsIgnoreCase(owner))
        || value.startsWith("/")
        || value.contains("\n")
        || value.contains("\r")
        || value.indexOf('\0') >= 0) return null;
    String root = value.split(" ", 2)[0];
    if (!root.contains(":")) root = owner.toLowerCase(Locale.ROOT) + ":" + root;
    var command = Bukkit.getPluginCommand(root);
    return command != null && command.getPlugin().getName().equalsIgnoreCase(owner)
        ? command
        : null;
  }

  private boolean linkAvailable(String id, Player viewer) {
    var command = linkCommand(id);
    String permission = plugin.getConfig().getString("profile.links." + id + ".permission", "");
    return command != null
        && command.testPermissionSilent(viewer)
        && (Set.of("scoreboard").contains(id)
            || permission.isBlank()
            || viewer.hasPermission(permission));
  }

  private void invoke(String id, Player viewer, Player target, boolean close) {
    if (!access(viewer) || !canView(viewer, target))
      throw new IllegalArgumentException("profile-menu.hidden");
    if (!linkAvailable(id, viewer))
      throw new IllegalArgumentException("profile-menu.integration-unavailable");
    String value =
        plugin
            .getConfig()
            .getString("profile.links." + id + ".command", "")
            .strip()
            .replace("{player}", target.getName())
            .replace("{viewer}", viewer.getName());
    String[] parts = value.split(" ", 2);
    var command = linkCommand(id);
    if (command == null) throw new IllegalArgumentException("profile-menu.integration-unavailable");
    if (close) viewer.closeInventory();
    command.execute(
        viewer, command.getName(), parts.length == 1 ? new String[0] : parts[1].split(" +"));
    placeholders.invalidate(viewer.getUniqueId());
    placeholders.invalidate(target.getUniqueId());
    // A toggle keeps the menu open: repaint on the next tick so the new state shows at once
    // instead of waiting for the periodic refresh.
    if (!close)
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                var open = menus.get(viewer.getUniqueId());
                if (open != null && viewer.isOnline() && permitted(open)) repaint(open);
              });
  }

  public void profile(Player viewer, Player target) {
    if (!canView(viewer, target)) throw new IllegalArgumentException("profile-menu.hidden");
    var m =
        menu(
            viewer,
            viewer.equals(target) ? "profile-menu.title" : "public-profile.title",
            Map.of("player", target.getName()),
            () -> access(viewer) && canView(viewer, target));
    m.subject = target.getUniqueId();
    m.repaint = () -> drawProfile(m, target);
    drawProfile(m, target);
    show(m);
  }

  private void drawProfile(Menu m, Player target) {
    capture(target);
    m.variables = displayValues(snapshots.get(target.getUniqueId()));
    var viewer = m.viewer;
    boolean own = viewer.equals(target);
    MenuStyle.frame(m.inventory);
    m.actions.clear();
    m.inventory.setItem(
        0,
        head(
            target,
            placeholders.render("profile-menu.heading", viewer, target),
            textLines("profile-menu.heading-lore", viewer, target)));
    if (own) {
      // The only header control: settings (the account page is a work-row card).
      card(
          m,
          1,
          Material.COMPARATOR,
          "profile-menu.settings",
          "profile-menu.settings-lore",
          target,
          () -> settings(viewer));
      // First work row: what the server knows about the player; a missing plugin leaves no gap.
      var topDraw = new ArrayList<java.util.function.IntConsumer>();
      if (linkAvailable("behavior", viewer))
        topDraw.add(
            slot ->
                link(
                    m,
                    slot,
                    "behavior",
                    "profile-menu.behavior",
                    "profile-menu.behavior-lore",
                    Material.SUNFLOWER,
                    target,
                    true));
      topDraw.add(
          slot ->
              card(
                  m,
                  slot,
                  Material.KNOWLEDGE_BOOK,
                  "profile-menu.statistics",
                  "profile-menu.statistics-lore",
                  target,
                  () -> statistics(viewer, viewer)));
      if (linkAvailable("adaptations", viewer))
        topDraw.add(
            slot ->
                link(
                    m,
                    slot,
                    "adaptations",
                    "profile-menu.adaptations",
                    "profile-menu.adaptations-lore",
                    Material.HEART_OF_THE_SEA,
                    target,
                    true));
      for (int i = 0; i < topDraw.size(); i++)
        topDraw.get(i).accept(contentSlot(m, i, topDraw.size()));
      // Second work row: the player's own activity and account.
      var own2 = new ArrayList<java.util.function.IntConsumer>();
      own2.add(
          slot ->
              card(
                  m,
                  slot,
                  Material.CLOCK,
                  "profile-menu.activity",
                  "profile-menu.activity-settings-lore",
                  target,
                  () -> activity(viewer, viewer)));
      if (linkAvailable("account", viewer))
        own2.add(
            slot ->
                link(
                    m,
                    slot,
                    "account",
                    "profile-menu.account",
                    "profile-menu.account-lore",
                    Material.TRIPWIRE_HOOK,
                    target,
                    true));
      for (int i = 0; i < own2.size(); i++)
        own2.get(i).accept(contentSlot(m, 7 + i, 7 + own2.size()));
    } else {
      drawSummary(
          m,
          displayValues(snapshots.get(target.getUniqueId())),
          false,
          privacy.canViewActivity(viewer, target));
      // Second work row: what the viewer can do with this player.
      var actions = new ArrayList<java.util.function.IntConsumer>();
      if (linkAvailable("reputation", viewer))
        actions.add(
            slot ->
                link(
                    m,
                    slot,
                    "reputation",
                    "profile-menu.reputation",
                    "profile-menu.reputation-lore",
                    Material.SUNFLOWER,
                    target,
                    true));
      actions.add(
          slot ->
              button(
                  m,
                  slot,
                  Material.WRITABLE_BOOK,
                  "public-profile.report",
                  "public-profile.report-help",
                  () ->
                      reports.create(
                          viewer,
                          target.getUniqueId(),
                          target.getName(),
                          () -> access(viewer) && canView(viewer, target))));
      actions.add(
          slot ->
              button(
                  m,
                  slot,
                  Material.PAPER,
                  "public-profile.message",
                  "public-profile.message-help",
                  () -> suggestMessage(viewer, target)));
      actions.add(
          slot ->
              button(
                  m,
                  slot,
                  Material.BARRIER,
                  "public-profile.ignore",
                  "public-profile.ignore-help",
                  () -> messages.command(viewer, "ignore", new String[] {target.getName()})));
      for (int i = 0; i < actions.size(); i++)
        actions.get(i).accept(contentSlot(m, 7 + i, 7 + actions.size()));
      if (viewer.hasPermission("loveessential.reports.notify"))
        reports.openCount(
            viewer,
            target.getUniqueId(),
            open -> {
              // Staff see how many different players have an open complaint about this one.
              if (open > 0 && menus.get(viewer.getUniqueId()) == m)
                m.inventory.setItem(
                    4,
                    MenuStyle.item(
                        Material.PAPER,
                        lang.render("public-profile.complaints"),
                        List.of(
                            lang.render(
                                "public-profile.complaints-count",
                                Map.of("count", Integer.toString(open))))));
            });
    }
    if (menus.get(viewer.getUniqueId()) == m) MenuStyle.apply(viewer, m.inventory);
  }

  private static final List<String> SETTINGS_TABS =
      List.of("chat", "dm", "alerts", "display", "privacy", "board");

  public void settings(Player p) {
    var m = menu(p, "profile-menu.settings", Map.of(), () -> access(p));
    m.subject = p.getUniqueId();
    m.repaint = () -> drawSettings(m, p);
    drawSettings(m, p);
    show(m);
  }

  /** One tab per topic in the header; the work area holds that topic's switches. */
  private void drawSettings(Menu m, Player p) {
    m.actions.clear();
    MenuStyle.frame(m.inventory);
    if (!SETTINGS_TABS.contains(m.tab)) m.tab = SETTINGS_TABS.getFirst();
    m.inventory.setItem(
        0,
        head(
            p,
            placeholders.render("profile-menu.heading", p, p),
            textLines("profile-menu.settings-lore", p, p)));
    for (int i = 0; i < SETTINGS_TABS.size(); i++) {
      String id = SETTINGS_TABS.get(i);
      boolean current = id.equals(m.tab);
      var tab =
          icon(
              "profile-menu.tab-" + id,
              Material.PAPER,
              lang.render("profile-menu.tab-" + id),
              List.of(
                  lang.render("profile-menu.tab-" + id + "-lore"),
                  lang.render("gui.separator"),
                  lang.render(current ? "profile-menu.tab-current" : "gui.click.open")));
      m.inventory.setItem(1 + i, current ? glow(tab) : tab);
      m.actions.put(
          1 + i,
          () -> {
            m.tab = id;
            repaint(m);
          });
    }
    switch (m.tab) {
      case "chat" -> drawControls(m, p, "chat");
      case "dm" -> {
        int shown = 0;
        var controls = plugin.getConfig().getConfigurationSection("profile.setting-controls");
        int controlCount = controls == null ? 0 : (int) controls.getKeys(false).stream()
            .filter(id -> tabOf(controls, id).equals("dm")).count();
        int total = controlCount + 1;
        card(
            m,
            contentSlot(m, shown++, total),
            Material.WRITABLE_BOOK,
            "messages.settings",
            "profile-menu.messages-lore",
            p,
            () -> preferences(p, 0));
        drawControls(m, p, "dm", shown, total);
      }
      case "alerts" -> drawAlerts(m, p);
      case "display" -> {
        link(
            m,
            contentSlot(m, 0, 2),
            "display",
            "profile-menu.display",
            "profile-menu.display-lore",
            Material.COMPARATOR,
            p,
            true);
        link(
            m,
            contentSlot(m, 1, 2),
            "suffixes",
            "profile-menu.suffixes",
            "profile-menu.suffixes-lore",
            Material.FLOWER_BANNER_PATTERN,
            p,
            true);
      }
      case "privacy" -> {
        var prefs = privacy.loaded(p.getUniqueId()) ? privacy.preferences(p) : null;
        boolean profileOn = prefs != null && prefs.profileVisible();
        boolean activityOn = prefs != null && prefs.activityVisible();
        toggle(
            m,
            contentSlot(m, 0, 2),
            profileOn,
            profileOn ? "profile-menu.visibility-on" : "profile-menu.visibility-off",
            "profile-menu.visibility-help",
            () -> changePrivacy(p, false, "toggle", () -> repaintNext(p)));
        toggle(
            m,
            contentSlot(m, 1, 2),
            activityOn,
            activityOn ? "profile-menu.activity-on" : "profile-menu.activity-off",
            "profile-menu.activity-help",
            () -> changePrivacy(p, true, "toggle", () -> repaintNext(p)));
      }
      default ->
          link(
              m,
              contentSlot(m, 0, 1),
              "scoreboard",
              "profile-menu.scoreboard",
              "profile-menu.scoreboard-lore",
              Material.MAP,
              p,
              true);
    }
    if (menus.get(p.getUniqueId()) == m) MenuStyle.apply(p, m.inventory);
  }

  private void repaintNext(Player p) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              var open = menus.get(p.getUniqueId());
              if (open != null && p.isOnline() && permitted(open)) repaint(open);
            });
  }

  private void toggle(
      Menu m, int slot, boolean on, String title, String hint, Runnable action) {
    m.inventory.setItem(
        slot,
        icon(
            on ? "enabled" : "disabled",
            on ? Material.LIME_DYE : Material.GRAY_DYE,
            lang.render(title),
            List.of(lang.render("gui.separator"), lang.render(hint))));
    m.actions.put(slot, action);
  }

  /** Controls saved before tabs existed have no {@code tab}: trades and invites go to "dm". */
  private static String tabOf(org.bukkit.configuration.ConfigurationSection controls, String id) {
    return controls.getString(
        id + ".tab", id.equals("clan-invites") || id.equals("player-trades") ? "dm" : "chat");
  }

  private void drawControls(Menu m, Player p, String tab) {
    drawControls(m, p, tab, 0, -1);
  }

  /** Switch cards from {@code profile.setting-controls} whose {@code tab} matches. */
  private void drawControls(Menu m, Player p, String tab, int first, int total) {
    var controls = plugin.getConfig().getConfigurationSection("profile.setting-controls");
    if (controls == null) return;
    var ids =
        controls.getKeys(false).stream()
            .filter(id -> tabOf(controls, id).equals(tab))
            .limit(14)
            .toList();
    int count = total < 0 ? ids.size() : total;
    int index = first;
    for (String id : ids) {
      String path = id + ".";
      Material material = Material.matchMaterial(controls.getString(path + "material", "LEVER"));
      if (material == null || material.isAir() || !material.isItem()) material = Material.LEVER;
      String linkId = controls.getString(path + "link", id);
      if (id.equals("channel")) {
        String state = placeholders.papi(p, p, "%lovechat_channel%").toLowerCase(Locale.ROOT);
        if (state.equals("global") || state.contains("глоб")) linkId = "channel-local";
      }
      link(
          m,
          contentSlot(m, index++, count),
          linkId,
          controls.getString(path + "title-key", "profile-menu.settings"),
          controls.getString(path + "lore-key", "profile-menu.settings-lore"),
          material,
          p,
          false);
    }
  }

  private void drawAlerts(Menu m, Player p) {
    var notify = love.service(dev.lovelace.lovecore.api.notify.LoveNotify.class);
    var channels = dev.lovelace.lovecore.api.notify.LoveNotify.Channel.values();
    for (int i = 0; i < channels.length; i++) {
      var channel = channels[i];
      String id = channel.name().toLowerCase(Locale.ROOT).replace("_", "");
      boolean on = notify.map(n -> n.isChannelEnabled(p.getUniqueId(), channel)).orElse(false);
      int slot = contentSlot(m, i, channels.length);
      var lore = new ArrayList<>(textLines("profile-menu.notification-lore", p, p));
      lore.add(lang.render(on ? "profile-menu.enabled" : "profile-menu.disabled"));
      if (notify.isEmpty()) lore.add(lang.render("profile-menu.integration-unavailable"));
      m.inventory.setItem(
          slot,
          icon(on ? "enabled" : "disabled", Material.LEVER, lang.render("profile-menu.notify-" + id), lore));
      m.actions.put(
          slot,
          () -> {
            var live =
                love.service(dev.lovelace.lovecore.api.notify.LoveNotify.class)
                    .orElseThrow(
                        () -> new IllegalArgumentException("profile-menu.integration-unavailable"));
            boolean before = live.isChannelEnabled(p.getUniqueId(), channel);
            boolean after = live.toggleChannel(p.getUniqueId(), channel);
            if (before && after) lang.send(p, "profile-menu.last-channel");
            repaintNext(p);
          });
    }
  }

  public void players(Player viewer, String action, int page) {
    String module =
        action.equals("invsee") ? "inventory" : action.equals("profile") ? "profile" : "messages";
    String permission =
        "loveessential."
            + (module.equals("inventory")
                ? "invsee"
                : module.equals("profile") ? "profile" : "messages");
    var m =
        menu(
            viewer,
            "players.list-title",
            Map.of(),
            () -> plugin.module(module) && viewer.hasPermission(permission));
    var players =
        Bukkit.getOnlinePlayers().stream()
            .filter(p -> visible(viewer, p))
            .filter(p -> !action.equals("profile") || privacy.canView(viewer, p))
            .filter(p -> action.equals("profile") || !p.equals(viewer))
            .sorted(Comparator.comparing(Player::getName))
            .toList();
    int count = Math.max(0, Math.min(21, players.size() - page * 21));
    for (int i = 0; i < count; i++) {
      Player target = players.get(page * 21 + i);
      int slot = MenuStyle.slot(i, count);
      m.inventory.setItem(
          slot,
          head(
              target,
              lang.render("players.entry", Map.of("player", target.getName())),
              List.of(
                  lang.render("players.select-" + action),
                  lang.render("gui.separator"),
                  lang.render("gui.click.open"))));
      m.actions.put(
          slot,
          () -> {
            if (!visible(viewer, target)) throw new IllegalArgumentException("players.unavailable");
            switch (action) {
              case "invsee" -> inventories.open(viewer, target, false);
              case "msg" -> suggestMessage(viewer, target);
              case "ignore" -> messages.command(viewer, "ignore", new String[] {target.getName()});
              default -> profile(viewer, target);
            }
          });
    }
    if (page > 0)
      button(
          m,
          36,
          Material.ARROW,
          "gui.previous",
          "gui.click.open",
          () -> players(viewer, action, page - 1));
    if ((page + 1) * 21 < players.size())
      button(
          m,
          44,
          Material.ARROW,
          "common.next",
          "gui.click.open",
          () -> players(viewer, action, page + 1));
    if (module.equals("messages"))
      button(
          m,
          4,
          Material.COMPARATOR,
          "messages.settings",
          "gui.click.open",
          () -> preferences(viewer, 0));
    show(m);
  }

  public void preferences(Player viewer, int page) {
    var m = menu(viewer, "messages.settings", Map.of(), () -> plugin.module("messages"));
    var prefs = messages.preferences(viewer);
    var ignored = prefs.ignored().entrySet().stream().sorted(Map.Entry.comparingByValue()).toList();
    button(
        m,
        4,
        prefs.enabled() ? Material.LIME_DYE : Material.GRAY_DYE,
        prefs.enabled() ? "messages.enabled" : "messages.muted",
        "gui.click.run",
        () ->
            messages.toggle(
                viewer,
                () -> {
                  if (menus.get(viewer.getUniqueId()) == m) preferences(viewer, page);
                }));
    int count = Math.max(0, Math.min(21, ignored.size() - page * 21));
    for (int i = 0; i < count; i++) {
      var entry = ignored.get(page * 21 + i);
      int slot = MenuStyle.slot(i, count);
      m.inventory.setItem(
          slot,
          MenuStyle.item(
              Material.PAPER,
              lang.render("players.entry", Map.of("player", entry.getValue())),
              List.of(lang.render("messages.unignore"))));
      m.actions.put(
          slot,
          () ->
              messages.unignore(
                  viewer,
                  entry.getKey(),
                  () -> {
                    if (menus.get(viewer.getUniqueId()) == m)
                      preferences(viewer, Math.min(page, Math.max(0, (ignored.size() - 2) / 21)));
                  }));
    }
    if (page > 0)
      button(
          m,
          36,
          Material.ARROW,
          "gui.previous",
          "gui.click.open",
          () -> preferences(viewer, page - 1));
    if ((page + 1) * 21 < ignored.size())
      button(
          m,
          44,
          Material.ARROW,
          "common.next",
          "gui.click.open",
          () -> preferences(viewer, page + 1));
    button(
        m,
        50,
        Material.REDSTONE,
        "players.ignore",
        "gui.click.open",
        () -> players(viewer, "ignore", 0));
    button(m, 51, Material.ARROW, "common.back", "gui.click.open", () -> players(viewer, "msg", 0));
    show(m);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    boolean cancelled = e.isCancelled();
    e.setCancelled(true);
    if (cancelled
        || (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT)
        || e.getRawSlot() < 0
        || e.getRawSlot() >= m.inventory.getSize()) return;
    var choice = m.choices.get(e.getRawSlot());
    Runnable action =
        choice != null
            ? () -> choice.accept(e.getClick())
            : e.getClick() == ClickType.LEFT ? m.actions.get(e.getRawSlot()) : null;
    if (action != null)
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (menus.get(m.viewer.getUniqueId()) != m || !permitted(m)) return;
                try {
                  MenuStyle.action(m.viewer, action);
                } catch (IllegalArgumentException error) {
                  lang.error(m.viewer, error);
                }
              });
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @EventHandler
  public void closed(InventoryCloseEvent e) {
    if (e.getInventory().getHolder() instanceof Menu m) {
      menus.remove(m.viewer.getUniqueId(), m);
      requests.remove(m.viewer.getUniqueId());
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    if (love.authenticated(e.getPlayer())) capture(e.getPlayer());
    var data = snapshots.remove(id);
    if (data != null) persist(id, data);
    menus.remove(id);
    requests.remove(id);
    blockStatistics.remove(id);
  }

  @Override
  public void close() {
    if (task != null) task.cancel();
    for (var m : List.copyOf(menus.values())) m.viewer.closeInventory();
    closing = true;
    var finalSnapshots = new HashMap<>(pendingSnapshots);
    finalSnapshots.putAll(snapshots);
    finalSnapshots.forEach(
        (id, data) ->
            store
                .profile(id, "loveessential.player-info", gson.toJson(data))
                .exceptionally(
                    error -> {
                      plugin
                          .getLogger()
                          .log(
                              java.util.logging.Level.WARNING,
                              lang.text("players.save-failed"),
                              error);
                      return null;
                    }));
    pendingSnapshots.clear();
    for (var future : List.copyOf(snapshotWrites.values()))
      future.completeExceptionally(
          new java.util.concurrent.CancellationException("plugin-disabled"));
    snapshotWrites.clear();
    privacy.close();
    snapshots.clear();
    requests.clear();
    blockStatistics.clear();
    menus.clear();
    reports.close();
    HandlerList.unregisterAll(this);
  }
}
