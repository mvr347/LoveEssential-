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
    /** Where Back leads when the menu is a root opened with a way out (see {@code /profile back}). */
    Runnable back;

    public Runnable guiBack() {
      return back;
    }

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
    return skinHead(
        saved.data().getOrDefault("skin_url", ""),
        lang.render("profile-menu.saved-heading", vars),
        lang.lines("profile-menu.saved-heading-lore").stream()
            .map(t -> lang.renderText(t, vars))
            .toList());
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
   * Slots of the 3x3 block reserved for the category buttons (rows 1-3, columns 5-7, counting from 0), by how many buttons
   * there are. Five, the shipped count, sit like the dots on a die; an admin who kept the six categories
   * of the old layout still gets all six.
   */
  private static final int[][] STAT_SLOTS = {
    {},
    {24},
    {23, 25},
    {14, 24, 34},
    {14, 16, 32, 34},
    {14, 16, 24, 32, 34},
    {14, 16, 23, 25, 32, 34}
  };

  private static final int STAT_BUTTONS = STAT_SLOTS.length - 1;

  private void drawStatistics(Menu m, Player viewer) {
    capture(viewer);
    var values = displayValues(snapshots.get(viewer.getUniqueId()));
    m.variables = values;
    MenuStyle.frame(m.inventory);
    m.actions.clear();
    m.choices.clear();
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
          24,
          icon(
              "disabled",
              Material.GRAY_DYE,
              lang.render("profile-menu.stats-empty"),
              List.of(lang.render("profile-menu.integration-unavailable"))));
    } else {
      int[] slots = STAT_SLOTS[cards.size()];
      for (int i = 0; i < cards.size(); i++) statisticButton(m, cards.get(i), slots[i], values);
    }
    statisticProfile(m, viewer);
    if (menus.get(viewer.getUniqueId()) == m) MenuStyle.apply(viewer, m.inventory);
  }

  /** A hover region over the drawn portrait, with the same information as the root profile head. */
  private void statisticProfile(Menu menu, Player viewer) {
    String root = "profile.gui.menus.profile-menu-statistics.profile-info";
    if (!plugin.getConfig().getBoolean(root + ".enabled", true)) return;
    var custom =
        dev.lovelace.loveessential.gui.ItemsAdderItems.find(
            plugin.getConfig().getString(root + ".item", "voidcore:invisible"));
    if (custom.isEmpty()) return;
    ItemStack item = custom.get();
    item.setAmount(1);
    var meta = item.getItemMeta();
    meta.displayName(
        placeholders
            .render("profile-menu.heading", viewer, viewer)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
    meta.lore(
        textLines("profile-menu.root-head-lore", viewer, viewer).stream()
            .map(
                line ->
                    line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
            .toList());
    meta.setHideTooltip(false);
    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
    item.setItemMeta(meta);
    for (int slot : new LinkedHashSet<>(plugin.getConfig().getIntegerList(root + ".slots"))) {
      if (slot < 0 || slot >= menu.inventory.getSize()) continue;
      var occupied = menu.inventory.getItem(slot);
      // Never replace a category, a navigation button or another configured control.
      if (occupied != null
          && !occupied.getType().isAir()
          && !occupied.getType().name().endsWith("STAINED_GLASS_PANE")) continue;
      menu.inventory.setItem(slot, item.clone());
    }
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
            Map.of("name", plain(lang.render(current.getFirst().group(), values)))));
    lore.add(Component.empty());
    for (var metric : current)
      lore.add(
          lang.renderText(
              lang.text("profile-menu.metric-line"),
              Map.of("label", metric.label(), "value", plain(lang.renderText(metric.value(), values)))));
    lore.add(Component.empty());
    if (count > 1) lore.add(lang.render("gui.separator"));
    if (count > 1) lore.addAll(lang.lines("profile-menu.cycle-hint").stream()
        .map(text -> lang.renderText(text, Map.of())).toList());
    else lore.add(lang.render("profile-menu.cycle-single"));

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
    var skin = target.getPlayerProfile().getTextures().getSkin();
    return skinHead(skin == null ? "" : skin.toExternalForm(), title, lore);
  }

  public void skinChanged(Player player) {
    MainThread.require();
    capture(player);
    for (var menu : List.copyOf(menus.values()))
      if (player.getUniqueId().equals(menu.subject) && menu.repaint != null && permitted(menu))
        repaint(menu);
  }

  /** Never give a skull an account identity: Paper may try to complete it through Mojang. */
  private static ItemStack skinHead(String url, Component title, List<Component> lore) {
    try {
      var uri = java.net.URI.create(url);
      if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && "textures.minecraft.net".equalsIgnoreCase(uri.getHost())
          && uri.getPath().matches("/texture/[a-fA-F0-9]{32,64}"))
        return MenuStyle.head(uri.getPath().substring(9), title, lore);
    } catch (IllegalArgumentException ignored) {
      // Missing or invalid saved textures use an ownerless fallback, without a network lookup.
    }
    return MenuStyle.item(Material.PLAYER_HEAD, title, lore);
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
    // The own profile is 27 slots (one work row), a visited profile 45; the
    // notification channels 27; every other profile menu (settings included) is 54.
    int size =
        switch (title) {
          case "profile-menu.title", "profile-menu.notify" -> 27;
          case "public-profile.title" -> 45;
          default -> 54;
        };
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
        case "back" -> {
          profile(p, p, true);
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
          settings(p, "privacy");
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
    Component shown = placeholders.render(title, m.viewer, target);
    if (!stateToken.isEmpty()) shown = localizedStates(List.of(shown)).getFirst();
    m.inventory.setItem(slot, icon(iconKey, material, shown, lines));
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
    // Opened from the profile: the plugin gets its "came from profile" argument, so it can show Back.
    String fromProfile = plugin.getConfig().getString("profile.links." + id + ".from-profile", "").strip();
    if (close
        && !fromProfile.isBlank()
        && !fromProfile.contains("\n")
        && !value.endsWith(" " + fromProfile)) value = value + " " + fromProfile;
    // The command the opened menu runs for its Back button; the plugin checks that it exists.
    String backArgument = plugin.getConfig().getString("profile.links." + id + ".back-argument", "").strip();
    if (close && !backArgument.isBlank() && !backArgument.contains("\n")) value = value + " " + backArgument;
    String[] parts = value.split(" ", 2);
    var command = linkCommand(id);
    if (command == null) throw new IllegalArgumentException("profile-menu.integration-unavailable");
    if (close) {
      if (command.getPlugin() != plugin) MenuStyle.external(viewer);
      viewer.closeInventory();
    }
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
    profile(viewer, target, false);
  }

  /** {@code withBack} adds a Back button that runs {@code profile.back-command} (the hub menu). */
  private void profile(Player viewer, Player target, boolean withBack) {
    if (!canView(viewer, target)) throw new IllegalArgumentException("profile-menu.hidden");
    var m =
        menu(
            viewer,
            viewer.equals(target) ? "profile-menu.title" : "public-profile.title",
            Map.of("player", target.getName()),
            () -> access(viewer) && canView(viewer, target));
    m.subject = target.getUniqueId();
    String hub = plugin.getConfig().getString("profile.back-command", "menu").strip();
    if (withBack && viewer.equals(target) && !hub.isBlank())
      m.back = () -> Bukkit.dispatchCommand(viewer, hub);
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
            textLines(own ? "profile-menu.root-head-lore" : "profile-menu.heading-lore", viewer, target)));
    if (own) {
      // Own profile (27 slots): one row of buttons centred on slots 10-16; a missing plugin leaves no gap.
      var row = new ArrayList<java.util.function.IntConsumer>();
      if (linkAvailable("account", viewer))
        row.add(
            slot ->
                link(m, slot, "account", "profile-menu.account", "profile-menu.root-account-lore",
                    Material.TRIPWIRE_HOOK, target, true));
      row.add(
          slot ->
              card(m, slot, Material.COMPARATOR, "profile-menu.settings",
                  "profile-menu.root-settings-lore", target, () -> settings(viewer)));
      if (linkAvailable("customization", viewer))
        row.add(
            slot ->
                link(m, slot, "customization", "profile-menu.root-customization",
                    "profile-menu.root-customization-lore", Material.NAME_TAG, target, true));
      row.add(
          slot ->
              card(m, slot, Material.KNOWLEDGE_BOOK, "profile-menu.statistics-button",
                  "profile-menu.root-statistics-lore", target, () -> statistics(viewer, viewer)));
      if (linkAvailable("adaptations", viewer))
        row.add(
            slot ->
                link(m, slot, "adaptations", "profile-menu.adaptations",
                    "profile-menu.root-adaptations-lore", Material.HEART_OF_THE_SEA, target, true));
      if (linkAvailable("behavior", viewer))
        row.add(
            slot ->
                link(m, slot, "behavior", "profile-menu.behavior",
                    "profile-menu.root-behavior-lore", Material.SUNFLOWER, target, true));
      for (int i = 0; i < row.size(); i++) row.get(i).accept(contentSlot(m, i, row.size()));
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

  /** The single filter button of the settings menu cycles through these groups, in a circle. */
  private static final List<String> SETTINGS_FILTERS =
      List.of("all", "chat", "interaction", "privacy", "interface");

  public void settings(Player p) {
    settings(p, "all");
  }

  /** Settings with one filter group preselected ({@code /profile activity} opens the privacy group). */
  public void settings(Player p, String filter) {
    var m = menu(p, "profile-menu.settings", Map.of(), () -> access(p));
    m.subject = p.getUniqueId();
    m.tab = filter;
    m.repaint = () -> drawSettings(m, p);
    drawSettings(m, p);
    show(m);
  }

  /** One group of switches, each drawn into the slot it is given. */
  private List<java.util.function.IntConsumer> settingsGroup(Menu m, Player p, String group) {
    var draws = new ArrayList<java.util.function.IntConsumer>();
    switch (group) {
      case "chat" -> draws.addAll(controlDraws(m, p, "chat"));
      case "interaction" -> {
        draws.add(
            slot ->
                switchCard(m, slot, "messages", messages.loaded(p.getUniqueId()) && messages.preferences(p).enabled(),
                    "enabled", "disabled", () -> messages.toggle(p, () -> repaintNext(p)),
                    messages.loaded(p.getUniqueId())));
        draws.addAll(controlDraws(m, p, "dm"));
      }
      case "privacy" -> {
        var prefs = privacy.loaded(p.getUniqueId()) ? privacy.preferences(p) : null;
        boolean profileOn = prefs != null && prefs.profileVisible();
        boolean activityOn = prefs != null && prefs.activityVisible();
        draws.add(
            slot ->
                switchCard(m, slot, "visibility", profileOn, "enabled", "disabled",
                    () -> changePrivacy(p, false, "toggle", () -> repaintNext(p)), true));
        draws.add(
            slot ->
                switchCard(m, slot, "activity", activityOn, "enabled", "disabled",
                    () -> changePrivacy(p, true, "toggle", () -> repaintNext(p)), true));
      }
      default -> {
        draws.add(
            slot ->
                card(m, slot, Material.BELL, "profile-menu.notifications",
                    "profile-menu.notifications-lore", p, () -> notifications(p)));
        draws.add(
            slot ->
                link(m, slot, "scoreboard", "profile-menu.scoreboard",
                    "profile-menu.settings-scoreboard-lore", Material.MAP, p, true));
      }
    }
    return draws;
  }

  /**
   * Slot of button {@code index} among {@code count} buttons whose first row is {@code row} of the work
   * area; more than seven buttons continue on the next rows. Four sit in pairs around the middle, like the
   * reference. Null when the slot would fall into the footer.
   */
  private static Integer settingsSlot(int row, int index, int count) {
    int slot;
    if (count == 4) slot = 18 + row * 9 + new int[] {2, 3, 5, 6}[index];
    else slot = MenuStyle.slot(index, count) + row * 9;
    return slot < 45 ? slot : null;
  }

  /** Draws a group starting at {@code row}; returns the first free row below it. */
  private int placeGroup(List<java.util.function.IntConsumer> draws, int row) {
    for (int i = 0; i < draws.size(); i++) {
      Integer slot = settingsSlot(row, i, draws.size());
      if (slot != null) draws.get(i).accept(slot);
    }
    return row + Math.max(1, (draws.size() + 6) / 7);
  }

  private void drawSettings(Menu m, Player p) {
    m.actions.clear();
    m.choices.clear();
    MenuStyle.frame(m.inventory);
    if (!SETTINGS_FILTERS.contains(m.tab)) m.tab = SETTINGS_FILTERS.getFirst();
    m.inventory.setItem(
        0,
        head(
            p,
            placeholders.render("profile-menu.heading", p, p),
            textLines("profile-menu.settings-head-lore", p, p)));
    m.actions.put(0, () -> profile(p, p));
    drawFilter(m, p);
    if (m.tab.equals("all")) {
      // Chat first, then interaction with privacy, then the interface; a long group takes more rows.
      int row = placeGroup(settingsGroup(m, p, "chat"), 0);
      var middle = new ArrayList<java.util.function.IntConsumer>();
      middle.addAll(settingsGroup(m, p, "interaction"));
      middle.addAll(settingsGroup(m, p, "privacy"));
      row = placeGroup(middle, row);
      placeGroup(settingsGroup(m, p, "interface"), row);
    } else {
      placeGroup(settingsGroup(m, p, m.tab), 0);
    }
    if (menus.get(p.getUniqueId()) == m) MenuStyle.apply(p, m.inventory);
  }

  /** The one header control, in the style of the clan lists: options with the current one marked, LMB/RMB. */
  private void drawFilter(Menu m, Player p) {
    int count = SETTINGS_FILTERS.size();
    int index = Math.max(0, SETTINGS_FILTERS.indexOf(m.tab));
    var lore = new ArrayList<Component>();
    lore.add(lang.render("profile-menu.filter-lore"));
    lore.add(Component.empty());
    lore.add(lang.render("profile-menu.filter-options"));
    for (int i = 0; i < count; i++)
      lore.add(
          lang.renderText(
              lang.text(i == index ? "profile-menu.filter-current" : "profile-menu.filter-other"),
              Map.of("name", plain(lang.render("profile-menu.filter-" + SETTINGS_FILTERS.get(i))))));
    lore.add(Component.empty());
    lore.add(lang.render("gui.separator"));
    lore.add(lang.render("profile-menu.filter-next"));
    lore.add(lang.render("profile-menu.filter-previous"));
    m.inventory.setItem(
        4,
        icon(
            "profile-menu.filter",
            Material.HOPPER,
            lang.renderText(
                lang.text("profile-menu.filter"),
                Map.of("name", plain(lang.render("profile-menu.filter-" + m.tab)))),
            lore));
    m.choices.put(
        4,
        click -> {
          int now = Math.max(0, SETTINGS_FILTERS.indexOf(m.tab));
          m.tab =
              SETTINGS_FILTERS.get(
                  click == ClickType.RIGHT ? StatCycle.previous(now, count) : StatCycle.next(now, count));
          repaint(m);
        });
  }

  /** The three notification channels, one switch each. */
  public void notifications(Player p) {
    var m = menu(p, "profile-menu.notify", Map.of(), () -> access(p));
    m.subject = p.getUniqueId();
    m.repaint = () -> drawNotifications(m, p);
    drawNotifications(m, p);
    show(m);
  }

  private void drawNotifications(Menu m, Player p) {
    m.actions.clear();
    MenuStyle.frame(m.inventory);
    m.inventory.setItem(
        0,
        head(
            p,
            placeholders.render("profile-menu.heading", p, p),
            textLines("profile-menu.notify-head-lore", p, p)));
    drawAlerts(m, p);
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

  /** Switch cards from {@code profile.setting-controls} whose {@code tab} matches, ready to be placed. */
  private List<java.util.function.IntConsumer> controlDraws(Menu m, Player p, String tab) {
    var draws = new ArrayList<java.util.function.IntConsumer>();
    var controls = plugin.getConfig().getConfigurationSection("profile.setting-controls");
    if (controls == null) return draws;
    var ids =
        controls.getKeys(false).stream()
            .filter(id -> tabOf(controls, id).equals(tab))
            .limit(7)
            .toList();
    for (String id : ids) {
      String path = id + ".";
      Material material = Material.matchMaterial(controls.getString(path + "material", "LEVER"));
      if (material == null || material.isAir() || !material.isItem()) material = Material.LEVER;
      String linkId = controls.getString(path + "link", id);
      if (id.equals("channel")) {
        String state = placeholders.papi(p, p, "%lovechat_channel%").toLowerCase(Locale.ROOT);
        if (state.equals("global") || state.contains("глоб")) linkId = "channel-local";
      }
      if (id.equals("channel")) {
        final String configured = controls.getString(path + "link", id);
        draws.add(slot -> drawChannel(m, p, slot, configured));
        continue;
      }
      if (SWITCHES.contains(id)) {
        final String configured = controls.getString(path + "link", id);
        draws.add(slot -> drawSwitch(m, p, slot, id, configured));
        continue;
      }
      final String link = linkId;
      final Material icon = material;
      draws.add(
          slot ->
              link(
                  m,
                  slot,
                  link,
                  controls.getString(path + "title-key", "profile-menu.settings"),
                  controls.getString(path + "lore-key", "profile-menu.settings-lore"),
                  icon,
                  p,
                  false));
    }
    return draws;
  }

  /** Switches drawn in the reference style: state in the title, a check or a cross, the opposite action. */
  private static final Set<String> SWITCHES =
      Set.of("grammar", "silent", "mentions", "clan-invites", "player-trades");

  private String switchToken(String id) {
    return switch (id) {
      case "grammar" -> "lovechatfilter_grammar";
      case "silent" -> "lovechat_silent_mode";
      case "mentions" -> "lovechat_mentions_disabled";
      case "clan-invites" -> "clans_invites_enabled";
      default -> "lovetrades_requests_enabled";
    };
  }

  /** {@code link} is the integration the control is configured to run; it defaults to the control's own id. */
  private void drawSwitch(Menu m, Player p, int slot, String id, String link) {
    String state = placeholders.papi(p, p, "%" + switchToken(id) + "%").toLowerCase(Locale.ROOT);
    boolean on = state.equals("yes") || state.equals("true");
    if (id.equals("mentions")) on = state.equals("no") || state.equals("false");
    switchCard(m, slot, id, on, "profile-menu." + id, "disabled", () -> invoke(link, p, p, false), linkAvailable(link, p));
  }

  /**
   * One two-state switch. The words come from {@code profile.switch-texts.<id>}, the layout from the
   * {@code switch-*} language templates, so every switch reads the same way.
   */
  private void switchCard(
      Menu m, int slot, String id, boolean on, String iconOn, String iconOff, Runnable action, boolean available) {
    var texts = plugin.getConfig().getConfigurationSection("profile.switch-texts." + id);
    var vars = new HashMap<String, Component>();
    if (texts != null)
      for (String key : texts.getKeys(false))
        vars.put(key, lang.renderText(texts.getString(key, ""), Map.of()));
    var title = lang.renderText(lang.text(on ? "profile-menu.switch-title-on" : "profile-menu.switch-title-off"), Map.of(), vars);
    var lore = new ArrayList<Component>();
    for (String line : lang.lines(on ? "profile-menu.switch-lore-on" : "profile-menu.switch-lore-off"))
      lore.add(lang.renderText(line, Map.of(), vars));
    if (!available) lore.add(lang.render("profile-menu.integration-unavailable"));
    m.inventory.setItem(slot, icon(on ? iconOn : iconOff, Material.LEVER, title, lore));
    m.actions.put(slot, action);
  }

  /** The chat channel has three states, each with its own words (reference: local, global, admin). */
  private void drawChannel(Menu m, Player p, int slot, String configured) {
    String state = placeholders.papi(p, p, "%lovechat_channel%").toLowerCase(Locale.ROOT);
    if (state.contains("глоб")) state = "global";
    if (!Set.of("local", "global", "admin").contains(state)) state = "local";
    var texts = plugin.getConfig().getConfigurationSection("profile.switch-texts.channel." + state);
    var vars = new HashMap<String, Component>();
    if (texts != null)
      for (String key : texts.getKeys(false))
        vars.put(key, lang.renderText(texts.getString(key, ""), Map.of()));
    var lore = new ArrayList<Component>();
    for (String line : lang.lines("profile-menu.channel-switch-lore")) lore.add(lang.renderText(line, Map.of(), vars));
    // The stock pair switches local <-> global; a customised link is used as it is, in both directions.
    String linkId = !configured.equals("channel") ? configured : state.equals("local") ? "channel" : "channel-local";
    if (!linkAvailable(linkId, p)) lore.add(lang.render("profile-menu.integration-unavailable"));
    m.inventory.setItem(
        slot,
        icon(
            "profile-menu.channel-" + state,
            Material.PAPER,
            lang.renderText(lang.text("profile-menu.channel-switch-title"), Map.of(), vars),
            lore));
    m.actions.put(slot, () -> invoke(linkId, p, p, false));
  }

  private void drawAlerts(Menu m, Player p) {
    var notify = love.service(dev.lovelace.lovecore.api.notify.LoveNotify.class);
    var channels = dev.lovelace.lovecore.api.notify.LoveNotify.Channel.values();
    for (int i = 0; i < channels.length; i++) {
      var channel = channels[i];
      String id = channel.name().toLowerCase(Locale.ROOT).replace("_", "");
      boolean on = notify.map(n -> n.isChannelEnabled(p.getUniqueId(), channel)).orElse(false);
      int slot = contentSlot(m, i, channels.length);
      var vars =
          Map.of(
              "name", plain(lang.render("profile-menu.notify-" + id + "-name")),
              "category", plain(lang.render("profile-menu.notify-" + id + "-category")),
              "function", plain(lang.render("profile-menu.notify-" + id + "-function")));
      var lore = new ArrayList<Component>();
      for (String line : lang.lines(on ? "profile-menu.notify-lore-on" : "profile-menu.notify-lore-off"))
        lore.add(lang.renderText(line, vars));
      if (notify.isEmpty()) lore.add(lang.render("profile-menu.integration-unavailable"));
      m.inventory.setItem(
          slot,
          icon(
              on ? "enabled" : "disabled",
              Material.LEVER,
              lang.renderText(lang.text(on ? "profile-menu.notify-title-on" : "profile-menu.notify-title-off"), vars),
              lore));
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
                  m.viewer.playSound(m.viewer.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1.4f);
                  MenuStyle.action(m.viewer, action);
                } catch (IllegalArgumentException error) {
                  m.viewer.playSound(m.viewer.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
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
