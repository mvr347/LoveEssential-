package dev.lovelace.loveessential.module;

import com.google.gson.Gson;
import dev.lovelace.loveessential.LoveEssentialPlugin;
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
  private final Lang lang;
  private final LoveBridge love;
  private final Gameplay gameplay;
  private final SqliteStore store;
  private final Placeholders placeholders;
  private final PrivateMessages messages;
  private final InventoryInspector inventories;
  private final Map<UUID, Map<String, String>> snapshots = new ConcurrentHashMap<>();
  private final Map<UUID, Menu> menus = new HashMap<>();
  private final Gson gson = new Gson();
  private BukkitTask task;
  private long ticks;

  private static final class Menu implements InventoryHolder {
    Inventory inventory;
    final Map<Integer, Runnable> actions = new HashMap<>();
    final Player viewer;
    final BooleanSupplier allowed;

    Menu(Player viewer, BooleanSupplier allowed) {
      this.viewer = viewer;
      this.allowed = allowed;
    }

    public Inventory getInventory() {
      return inventory;
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
    this.lang = lang;
    this.love = love;
    this.gameplay = gameplay;
    this.store = store;
    this.placeholders = placeholders;
    this.messages = messages;
    this.inventories = inventories;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    task =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  ticks++;
                  if (ticks % 20 == 0) {
                    for (var m : List.copyOf(menus.values()))
                      if (!permitted(m)) m.viewer.closeInventory();
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
    return snapshots.getOrDefault(id, Map.of());
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
    long seconds = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
    m.put("playtime_seconds", "" + seconds);
    m.put("playtime_hours", "" + seconds / 3600);
    m.put("playtime_minutes", "" + seconds / 60 % 60);
    m.put("first_join", Instant.ofEpochMilli(p.getFirstPlayed()).toString());
    m.put("last_seen", Instant.now().toString());
    m.put("homes", "" + gameplay.names(p, "home").size());
    var textures = p.getPlayerProfile().getTextures();
    m.put("skin_url", textures.getSkin() == null ? "" : textures.getSkin().toExternalForm());
    m.put("skin_model", textures.getSkinModel().name().toLowerCase(Locale.ROOT));
    for (String flag :
        List.of("afk", "vanished", "authenticated", "combat", "flying", "flight_allowed"))
      m.put(
          flag + "_state",
          lang.text("players." + (Boolean.parseBoolean(m.get(flag)) ? "yes" : "no")));
    snapshots.put(p.getUniqueId(), Map.copyOf(m));
  }

  private static String number(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private void persist(UUID uuid, Map<String, String> data) {
    store
        .profile(uuid, "loveessential.player-info", gson.toJson(data))
        .exceptionally(
            error -> {
              plugin
                  .getLogger()
                  .log(java.util.logging.Level.WARNING, lang.text("players.save-failed"), error);
              return null;
            });
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
    var m = new Menu(viewer, allowed);
    if (!permitted(m)) throw new IllegalArgumentException("errors.permission");
    m.inventory = Bukkit.createInventory(m, 54, lang.render(title, vars));
    MenuStyle.frame(m.inventory);
    m.inventory.setItem(0, head(viewer, lang.render(title, vars), List.of()));
    button(m, 52, Material.BARRIER, "gui.close", "gui.click.close", viewer::closeInventory);
    return m;
  }

  private void show(Menu m) {
    if (m.viewer.openInventory(m.inventory) != null) menus.put(m.viewer.getUniqueId(), m);
  }

  private void button(Menu m, int slot, Material icon, String title, String hint, Runnable action) {
    m.inventory.setItem(slot, MenuStyle.button(lang, icon, title, "gui.separator", hint));
    m.actions.put(slot, action);
  }

  public void profile(Player viewer, Player target) {
    var m =
        menu(
            viewer,
            "players.title",
            Map.of("player", target.getName()),
            () ->
                plugin.module("profile")
                    && viewer.hasPermission("loveessential.profile")
                    && visible(viewer, target));
    capture(target);
    m.inventory.setItem(
        0,
        head(target, lang.render("players.title", Map.of("player", target.getName())), List.of()));
    var cards = plugin.getConfig().getConfigurationSection("profile.cards");
    if (cards != null) {
      var keys =
          cards.getKeys(false).stream()
              .filter(
                  key -> {
                    String permission = cards.getString(key + ".permission", "");
                    return cards.getBoolean(key + ".enabled", true)
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
    if (!viewer.equals(target)
        && plugin.module("messages")
        && viewer.hasPermission("loveessential.messages")) {
      button(
          m,
          3,
          Material.WRITABLE_BOOK,
          "players.message",
          "gui.click.open",
          () -> suggestMessage(viewer, target));
      button(
          m,
          5,
          Material.REDSTONE,
          "players.ignore",
          "gui.click.run",
          () -> messages.command(viewer, "ignore", new String[] {target.getName()}));
    }
    if (!viewer.equals(target)
        && plugin.module("inventory")
        && viewer.hasPermission("loveessential.invsee"))
      button(
          m,
          7,
          Material.CHEST,
          "players.inventory",
          "gui.click.open",
          () -> inventories.open(viewer, target, false));
    button(
        m, 50, Material.CLOCK, "players.refresh", "gui.click.open", () -> profile(viewer, target));
    show(m);
  }

  private void suggestMessage(Player viewer, Player target) {
    if (!visible(viewer, target)) throw new IllegalArgumentException("players.unavailable");
    viewer.closeInventory();
    viewer.sendMessage(
        lang.render("messages.compose", Map.of("player", target.getName()))
            .clickEvent(ClickEvent.suggestCommand("/le msg " + target.getName() + " ")));
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
    var m =
        menu(
            viewer,
            "messages.settings",
            Map.of(),
            () -> plugin.module("messages") && viewer.hasPermission("loveessential.messages"));
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
    if (cancelled || e.getClick() != ClickType.LEFT || e.getRawSlot() < 0 || e.getRawSlot() >= 54)
      return;
    Runnable action = m.actions.get(e.getRawSlot());
    if (action != null)
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (menus.get(m.viewer.getUniqueId()) != m || !permitted(m)) return;
                try {
                  action.run();
                } catch (IllegalArgumentException error) {
                  lang.send(
                      m.viewer,
                      lang.contains(error.getMessage()) ? error.getMessage() : "errors.input");
                }
              });
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @EventHandler
  public void closed(InventoryCloseEvent e) {
    if (e.getInventory().getHolder() instanceof Menu m) menus.remove(m.viewer.getUniqueId(), m);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    if (love.authenticated(e.getPlayer())) capture(e.getPlayer());
    var data = snapshots.remove(id);
    if (data != null) persist(id, data);
    menus.remove(id);
  }

  @Override
  public void close() {
    if (task != null) task.cancel();
    for (var m : List.copyOf(menus.values())) m.viewer.closeInventory();
    snapshots.forEach(this::persist);
    snapshots.clear();
    menus.clear();
    HandlerList.unregisterAll(this);
  }
}
