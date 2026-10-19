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
  private final ProfilePrivacy privacy;
  private BukkitTask task;
  private long ticks;

  private static final class Menu implements InventoryHolder {
    Inventory inventory;
    final Map<Integer, Runnable> actions = new HashMap<>();
    final Player viewer;
    final BooleanSupplier allowed;
    UUID subject;
    Runnable repaint;

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
                  if (ticks % 20 == 0) {
                    for (var m : List.copyOf(menus.values()))
                      if (!permitted(m)) m.viewer.closeInventory();
                      else if (m.repaint != null) m.repaint.run();
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
    int size = title.equals("profile-menu.title") ? 27 : 54;
    m.inventory = Bukkit.createInventory(m, size, lang.render(title, vars));
    if (size == 54) MenuStyle.frame(m.inventory);
    else {
      var fill = MenuStyle.item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
      for (int i = 0; i < 9; i++) {
        m.inventory.setItem(i, fill);
        m.inventory.setItem(18 + i, fill);
      }
    }
    m.inventory.setItem(0, head(viewer, lang.render(title, vars), List.of()));
    button(
        m,
        size == 27 ? 26 : 52,
        Material.BARRIER,
        "gui.close",
        "gui.click.close",
        viewer::closeInventory);
    return m;
  }

  private void show(Menu m) {
    if (m.viewer.openInventory(m.inventory) != null) menus.put(m.viewer.getUniqueId(), m);
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
                    && viewer.hasPermission("loveessential.profile")
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
    var cards = plugin.getConfig().getConfigurationSection("profile.cards");
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
    return plugin.ready()
        && plugin.module("profile")
        && p.hasPermission("loveessential.profile")
        && love.authenticated(p);
  }

  public void command(Player p, String[] args) {
    if (!plugin.module("profile")) throw new IllegalArgumentException("errors.module-disabled");
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
      profile(p, messages.target(p, args[1]));
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
    profile(p, messages.target(p, args[0]));
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
                new dev.lovelace.loveessential.MainThread(plugin)
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
    return placeholders.renderLines(key, viewer, target);
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
        MenuStyle.item(
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
    var lines = new ArrayList<>(textLines(lore, m.viewer, target));
    if (!linkAvailable(id, m.viewer))
      lines.add(lang.render("profile-menu.integration-unavailable"));
    m.inventory.setItem(
        slot, MenuStyle.item(material, placeholders.render(title, m.viewer, target), lines));
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
        && (permission.isBlank() || viewer.hasPermission(permission));
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
    placeholders.clear();
  }

  public void profile(Player viewer, Player target) {
    if (!canView(viewer, target)) throw new IllegalArgumentException("profile-menu.hidden");
    var m =
        menu(
            viewer,
            "profile-menu.title",
            Map.of("player", target.getName()),
            () -> access(viewer) && canView(viewer, target));
    m.subject = target.getUniqueId();
    m.repaint = () -> drawProfile(m, target);
    drawProfile(m, target);
    show(m);
  }

  private void drawProfile(Menu m, Player target) {
    capture(target);
    var viewer = m.viewer;
    boolean own = viewer.equals(target);
    m.actions.clear();
    m.inventory.setItem(
        0,
        head(
            target,
            placeholders.render("profile-menu.heading", viewer, target),
            textLines("profile-menu.heading-lore", viewer, target)));
    m.actions.put(0, () -> drawProfile(m, target));
    if (own) {
      link(
          m,
          11,
          "account",
          "profile-menu.account",
          "profile-menu.account-lore",
          Material.TRIPWIRE_HOOK,
          target,
          true);
      card(
          m,
          12,
          Material.COMPARATOR,
          "profile-menu.settings",
          "profile-menu.settings-lore",
          target,
          () -> settings(viewer));
      card(
          m,
          13,
          Material.NAME_TAG,
          "profile-menu.customization",
          "profile-menu.customization-lore",
          target,
          () -> customization(viewer));
    } else {
      card(
          m,
          11,
          Material.TRIPWIRE_HOOK,
          "profile-menu.account",
          "profile-menu.public-account-lore",
          target,
          null);
      card(
          m,
          12,
          privacy.canViewActivity(viewer, target) ? Material.CLOCK : Material.GRAY_DYE,
          "profile-menu.activity",
          privacy.canViewActivity(viewer, target)
              ? "profile-menu.public-activity-lore"
              : "profile-menu.activity-hidden-lore",
          target,
          () -> activity(viewer, target));
      card(
          m,
          13,
          Material.NAME_TAG,
          "profile-menu.customization",
          "profile-menu.public-customization-lore",
          target,
          null);
    }
    card(
        m,
        14,
        Material.HEART_OF_THE_SEA,
        "profile-menu.adaptations",
        "profile-menu.adaptations-lore",
        target,
        () -> adaptations(viewer, target));
    link(
        m,
        15,
        own ? "behavior" : "reputation",
        "profile-menu.reputation",
        own ? "profile-menu.behavior-lore" : "profile-menu.reputation-lore",
        Material.SUNFLOWER,
        target,
        true);
    if (!own && plugin.module("messages") && viewer.hasPermission("loveessential.messages"))
      button(
          m,
          6,
          Material.WRITABLE_BOOK,
          "players.message",
          "gui.click.open",
          () -> suggestMessage(viewer, target));
    if (!own && plugin.module("inventory") && viewer.hasPermission("loveessential.invsee"))
      button(
          m,
          8,
          Material.CHEST,
          "players.inventory",
          "gui.click.open",
          () -> inventories.open(viewer, target, false));
    button(
        m,
        25,
        Material.ARROW,
        "common.back",
        "gui.click.open",
        () -> {
          if (own) viewer.closeInventory();
          else players(viewer, "profile", 0);
        });
    button(m, 26, Material.BARRIER, "gui.close", "gui.click.close", viewer::closeInventory);
  }

  public void settings(Player p) {
    var m = menu(p, "profile-menu.settings", Map.of(), () -> access(p));
    m.subject = p.getUniqueId();
    m.repaint = () -> drawSettings(m, p);
    drawSettings(m, p);
    show(m);
  }

  private void drawSettings(Menu m, Player p) {
    m.actions.clear();
    button(m, 52, Material.BARRIER, "gui.close", "gui.click.close", p::closeInventory);
    m.inventory.setItem(
        0,
        head(
            p,
            placeholders.render("profile-menu.heading", p, p),
            textLines("profile-menu.settings-lore", p, p)));
    card(
        m,
        19,
        Material.CLOCK,
        "profile-menu.activity",
        "profile-menu.activity-settings-lore",
        p,
        () -> activity(p, p));
    card(
        m,
        20,
        Material.WRITABLE_BOOK,
        "messages.settings",
        "profile-menu.messages-lore",
        p,
        () -> preferences(p, 0));
    card(
        m,
        21,
        Material.BELL,
        "profile-menu.notifications",
        "profile-menu.notifications-lore",
        p,
        () -> notifications(p));
    card(
        m,
        22,
        Material.NAME_TAG,
        "profile-menu.customization",
        "profile-menu.customization-lore",
        p,
        () -> customization(p));
    link(
        m,
        23,
        "scoreboard",
        "profile-menu.scoreboard",
        "profile-menu.scoreboard-lore",
        Material.MAP,
        p,
        true);
    var controls = plugin.getConfig().getConfigurationSection("profile.setting-controls");
    if (controls != null) {
      int index = 0;
      for (String id : controls.getKeys(false)) {
        if (index >= 14) break;
        int slot = MenuStyle.CONTENT[7 + index++];
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
            slot,
            linkId,
            controls.getString(path + "title-key", "profile-menu.settings"),
            controls.getString(path + "lore-key", "profile-menu.settings-lore"),
            material,
            p,
            false);
      }
    }
    button(m, 51, Material.ARROW, "common.back", "gui.click.open", () -> profile(p, p));
  }

  private void customization(Player p) {
    var m = menu(p, "profile-menu.customization", Map.of(), () -> access(p));
    m.subject = p.getUniqueId();
    m.inventory.setItem(
        0,
        head(
            p,
            placeholders.render("profile-menu.heading", p, p),
            textLines("profile-menu.customization-lore", p, p)));
    link(
        m,
        20,
        "nickname",
        "profile-menu.nickname",
        "profile-menu.nickname-lore",
        Material.NAME_TAG,
        p,
        true);
    link(
        m,
        24,
        "suffixes",
        "profile-menu.suffixes",
        "profile-menu.suffixes-lore",
        Material.FLOWER_BANNER_PATTERN,
        p,
        true);
    button(m, 51, Material.ARROW, "common.back", "gui.click.open", () -> profile(p, p));
    show(m);
  }

  private void notifications(Player p) {
    var m = menu(p, "profile-menu.notifications", Map.of(), () -> access(p));
    m.subject = p.getUniqueId();
    var notify = love.service(dev.lovelace.lovecore.api.notify.LoveNotify.class);
    int index = 0;
    for (var channel : dev.lovelace.lovecore.api.notify.LoveNotify.Channel.values()) {
      String id = channel.name().toLowerCase(Locale.ROOT).replace("_", "");
      boolean on = notify.map(n -> n.isChannelEnabled(p.getUniqueId(), channel)).orElse(false);
      int slot = 20 + index++ * 2;
      var lore = new ArrayList<>(textLines("profile-menu.notification-lore", p, p));
      lore.add(lang.render(on ? "profile-menu.enabled" : "profile-menu.disabled"));
      if (notify.isEmpty()) lore.add(lang.render("profile-menu.integration-unavailable"));
      m.inventory.setItem(
          slot,
          MenuStyle.item(
              on ? Material.LIME_DYE : Material.GRAY_DYE,
              lang.render("profile-menu.notify-" + id),
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
            notifications(p);
          });
    }
    button(m, 51, Material.ARROW, "common.back", "gui.click.open", () -> settings(p));
    show(m);
  }

  private void adaptations(Player viewer, Player target) {
    if (!canView(viewer, target)) throw new IllegalArgumentException("profile-menu.hidden");
    var m =
        menu(
            viewer,
            "profile-menu.adaptations",
            Map.of(),
            () -> access(viewer) && canView(viewer, target));
    m.subject = target.getUniqueId();
    m.repaint = () -> drawAdaptations(m, target);
    drawAdaptations(m, target);
    show(m);
  }

  private void drawAdaptations(Menu m, Player target) {
    var viewer = m.viewer;
    m.actions.clear();
    button(m, 52, Material.BARRIER, "gui.close", "gui.click.close", viewer::closeInventory);
    m.inventory.setItem(
        0,
        head(
            target,
            placeholders.render("profile-menu.heading", viewer, target),
            textLines("profile-menu.adaptations-lore", viewer, target)));
    var cards = plugin.getConfig().getConfigurationSection("profile.adaptation-cards");
    if (cards != null) {
      int i = 0;
      for (String id : cards.getKeys(false)) {
        if (i >= 21) break;
        Material material = Material.matchMaterial(cards.getString(id + ".material", "BOOK"));
        if (material == null || material.isAir() || !material.isItem()) material = Material.BOOK;
        card(
            m,
            MenuStyle.slot(i++, Math.min(21, cards.getKeys(false).size())),
            material,
            cards.getString(id + ".title-key", "profile-menu.adaptations"),
            cards.getString(id + ".lore-key", "profile-menu.adaptations-lore"),
            target,
            null);
      }
    }
    if (viewer.equals(target))
      link(
          m,
          50,
          "adaptations",
          "profile-menu.open-adaptations",
          "profile-menu.adaptations-lore",
          Material.HEART_OF_THE_SEA,
          target,
          true);
    button(m, 51, Material.ARROW, "common.back", "gui.click.open", () -> profile(viewer, target));
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
    if (cancelled
        || e.getClick() != ClickType.LEFT
        || e.getRawSlot() < 0
        || e.getRawSlot() >= m.inventory.getSize()) return;
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
    privacy.close();
    snapshots.clear();
    menus.clear();
    HandlerList.unregisterAll(this);
  }
}
