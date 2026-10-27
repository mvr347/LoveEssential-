package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.gui.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.*;

/** Complaints are a persisted staff inbox; sending one never automatically changes reputation. */
public final class PlayerReports implements Listener, AutoCloseable {
  public record Report(
      UUID author,
      String authorName,
      UUID subject,
      String subjectName,
      String text,
      long created,
      boolean resolved,
      String status,
      String handledBy,
      long handledAt) {
    public Report(
        UUID author,
        String authorName,
        UUID subject,
        String subjectName,
        String text,
        long created,
        boolean resolved) {
      this(author, authorName, subject, subjectName, text, created, resolved, null, null, 0);
    }

    /** Records saved before statuses existed carry only {@code resolved}. */
    ReportRules.State state() {
      return ReportRules.state(status, resolved);
    }

    Report handled(ReportRules.State next, String by, long at) {
      return new Report(
          author, authorName, subject, subjectName, text, created, true, next.name(), by, at);
    }
  }

  private static final class Menu implements GuiLayout {
    Inventory inventory;
    final Map<Integer, Runnable> actions = new HashMap<>();

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }

    public String guiId() {
      return "profile-reports.title";
    }

    public Map<Integer, ?> guiActions() {
      return actions;
    }

    public void guiPut(int slot, Runnable action) {
      actions.put(slot, action);
    }
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private NativeDialogs ui;
  private final Set<UUID> submitting = new HashSet<>();

  public PlayerReports(LoveEssentialPlugin plugin, SqliteStore store, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = new MainThread(plugin);
    this.store = store;
    this.lang = lang;
    this.love = love;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public void ui(NativeDialogs ui) {
    this.ui = ui;
  }

  private boolean ready(Player p) {
    return plugin.ready() && plugin.module("profile") && p.isOnline() && love.authenticated(p);
  }

  private boolean staff(Player p) {
    return ready(p) && p.hasPermission("loveessential.admin");
  }

  public void create(Player p, UUID target, String name, BooleanSupplier visible) {
    if (!ready(p) || !visible.getAsBoolean() || p.getUniqueId().equals(target))
      throw new IllegalArgumentException("profile-menu.hidden");
    ui.show(
        p,
        "profile-reports.create",
        "profile-reports.help",
        List.of(ui.text("reason", "profile-reports.reason", "", 1024)),
        List.of(
            ui.button(
                "send",
                "profile-reports.send",
                values -> submit(p, target, name, values.get("reason"), visible)),
            ui.button(
                "back",
                "common.back",
                values ->
                    plugin
                        .getCommand("profile")
                        .execute(p, "profile", new String[] {"view", name}))),
        actor -> actor.equals(p) && ready(p) && visible.getAsBoolean());
  }

  private long windowMillis() {
    return Math.clamp(plugin.getConfig().getLong("profile.reports.window-days", 7), 1, 365)
        * 86_400_000L;
  }

  private int notifyThreshold() {
    return Math.clamp(plugin.getConfig().getInt("profile.reports.notify-threshold", 3), 1, 100);
  }

  private int dangerThreshold() {
    return Math.clamp(
        plugin.getConfig().getInt("profile.reports.danger-threshold", 5), notifyThreshold(), 100);
  }

  private void submit(Player p, UUID target, String name, String text, BooleanSupplier visible) {
    if (!ready(p) || !visible.getAsBoolean())
      throw new IllegalArgumentException("profile-menu.hidden");
    text = text.strip();
    if (text.length() < 8 || text.length() > 1024)
      throw new IllegalArgumentException("profile-reports.reason-length");
    if (!submitting.add(p.getUniqueId())) throw new IllegalArgumentException("wizard.busy");
    String prefix = p.getUniqueId() + ":";
    long now = System.currentTimeMillis();
    long window = windowMillis();
    long cooldown =
        Math.clamp(plugin.getConfig().getLong("profile.reports.cooldown-seconds", 300), 0, 86400)
            * 1000;
    String actor = p.getUniqueId().toString();
    var fresh = new Report(p.getUniqueId(), p.getName(), target, name, text, now, false, "OPEN", null, 0);
    store
        .listPrefix("player-report", prefix, Report.class)
        .thenCompose(
            saved -> {
              long latest = saved.stream().mapToLong(d -> d.value().created()).max().orElse(0);
              if (now - latest < cooldown)
                throw new IllegalArgumentException("profile-reports.cooldown");
              // One open complaint per author and subject: repeating it must not inflate the count.
              if (saved.stream()
                  .map(SqliteStore.Document::value)
                  .anyMatch(
                      r ->
                          target.equals(r.subject())
                              && r.state() == ReportRules.State.OPEN
                              && now - r.created() <= window))
                throw new IllegalArgumentException("profile-reports.duplicate");
              return store.save("player-report", prefix + UUID.randomUUID(), 0, fresh, actor);
            })
        .thenCompose(rev -> store.list("player-report", Report.class))
        .whenComplete(
            (all, e) ->
                main.execute(
                    () -> {
                      submitting.remove(p.getUniqueId());
                      if (e != null) {
                        if (p.isOnline()) ui.error(p, e);
                        return;
                      }
                      int after =
                          ReportRules.distinctOpenAuthors(
                              all.stream().map(SqliteStore.Document::value).toList(),
                              target,
                              System.currentTimeMillis(),
                              window);
                      alert(name, after - 1, after);
                      if (!p.isOnline()) return;
                      lang.send(p, "profile-reports.sent");
                      plugin.getCommand("profile").execute(p, "profile", new String[0]);
                    }));
  }

  /** Tells online staff when a subject has just reached the notify or the danger threshold. */
  private void alert(String subject, int before, int after) {
    String key = null;
    if (ReportRules.crossed(before, after, dangerThreshold())) key = "profile-reports.alert-danger";
    else if (ReportRules.crossed(before, after, notifyThreshold())) key = "profile-reports.alert";
    if (key == null) return;
    for (Player staff : Bukkit.getOnlinePlayers())
      if (ready(staff) && staff.hasPermission("loveessential.reports.notify"))
        love.notify(
            staff, lang.render(key, Map.of("player", subject, "count", Integer.toString(after))));
  }

  /** Summary for staff who join after the threshold was crossed. */
  private void summary(Player p) {
    if (!ready(p) || !p.hasPermission("loveessential.reports.notify")) return;
    long window = windowMillis();
    int threshold = notifyThreshold();
    store
        .list("player-report", Report.class)
        .whenComplete(
            (all, e) ->
                main.execute(
                    () -> {
                      if (e != null || !ready(p)) return;
                      var reports = all.stream().map(SqliteStore.Document::value).toList();
                      long now = System.currentTimeMillis();
                      long flagged =
                          reports.stream()
                              .map(Report::subject)
                              .distinct()
                              .filter(
                                  s -> ReportRules.distinctOpenAuthors(reports, s, now, window)
                                      >= threshold)
                              .count();
                      if (flagged > 0)
                        love.notify(
                            p,
                            lang.render(
                                "profile-reports.summary",
                                Map.of(
                                    "count", Long.toString(flagged),
                                    "threshold", Integer.toString(threshold))));
                    }));
  }

  /** Open complaints about one player, as shown on a profile to staff. */
  public void openCount(Player staff, UUID subject, java.util.function.IntConsumer result) {
    long window = windowMillis();
    store
        .list("player-report", Report.class)
        .whenComplete(
            (all, e) ->
                main.execute(
                    () -> {
                      if (e != null || !staff(staff)) return;
                      result.accept(
                          ReportRules.distinctOpenAuthors(
                              all.stream().map(SqliteStore.Document::value).toList(),
                              subject,
                              System.currentTimeMillis(),
                              window));
                    }));
  }

  public void command(Player p, String[] args) {
    if (!staff(p)) throw new IllegalArgumentException("errors.permission");
    if (args.length != 0) throw new IllegalArgumentException("errors.input");
    list(p, 0);
  }

  private static ItemStack skull(UUID player, Component title, List<Component> lore) {
    var item = MenuStyle.item(Material.PLAYER_HEAD, title, lore);
    var meta = (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();
    meta.setOwningPlayer(Bukkit.getOfflinePlayer(player));
    item.setItemMeta(meta);
    return item;
  }

  private Component bar(int value, int limit) {
    int filled = Math.min(16, Math.max(0, value * 16 / Math.max(1, limit)));
    var color = value >= limit ? "<red>" : value >= notifyThreshold() ? "<yellow>" : "<green>";
    return MiniMessage.miniMessage()
        .deserialize(color + "▮".repeat(filled) + "<dark_gray>" + "▮".repeat(16 - filled));
  }

  private Menu menu(String title) {
    var m = new Menu();
    m.inventory = Bukkit.createInventory(m, 54, lang.render(title));
    MenuStyle.frame(m.inventory);
    return m;
  }

  /** Complaints grouped by the accused player, most reported first. */
  private void list(Player p, int page) {
    if (!staff(p)) throw new IllegalArgumentException("errors.permission");
    store
        .list("player-report", Report.class)
        .whenComplete(
            (saved, e) ->
                main.execute(
                    () -> {
                      if (!staff(p)) return;
                      if (e != null) {
                        ui.error(p, e);
                        return;
                      }
                      long now = System.currentTimeMillis(), window = windowMillis();
                      var reports = saved.stream().map(SqliteStore.Document::value).toList();
                      var subjects = new LinkedHashMap<UUID, String>();
                      for (var r : reports) subjects.putIfAbsent(r.subject(), r.subjectName());
                      var order =
                          subjects.keySet().stream()
                              .sorted(
                                  Comparator.<UUID>comparingInt(
                                          s ->
                                              -ReportRules.distinctOpenAuthors(
                                                  reports, s, now, window))
                                      .thenComparing(
                                          s ->
                                              -reports.stream()
                                                  .filter(r -> r.subject().equals(s))
                                                  .mapToLong(Report::created)
                                                  .max()
                                                  .orElse(0)))
                              .toList();
                      int actual = Math.clamp(page, 0, Math.max(0, (order.size() - 1) / 21));
                      var m = menu("profile-reports.title");
                      m.inventory.setItem(
                          0,
                          PlayerProfiles.head(p, lang.render("profile-reports.title"), List.of()));
                      int danger = dangerThreshold();
                      for (int i = actual * 21;
                          i < Math.min(order.size(), (actual + 1) * 21);
                          i++) {
                        var subject = order.get(i);
                        String name = subjects.get(subject);
                        int open = ReportRules.distinctOpenAuthors(reports, subject, now, window);
                        int total = (int) reports.stream().filter(r -> r.subject().equals(subject)).count();
                        int slot = MenuStyle.CONTENT[i % 21];
                        var level = ReportRules.level(open, notifyThreshold(), danger);
                        m.inventory.setItem(
                            slot,
                            skull(
                                subject,
                                lang.render("profile-reports.subject", Map.of("player", name)),
                                List.of(
                                    lang.render(
                                        "profile-reports.open-count",
                                        Map.of("count", Integer.toString(open))),
                                    lang.rich(
                                        "profile-reports.progress",
                                        Map.of(
                                            "bar", bar(open, danger),
                                            "count", Component.text(open),
                                            "limit", Component.text(danger))),
                                    lang.render("profile-reports.level-" + level.name().toLowerCase(Locale.ROOT)),
                                    lang.render(
                                        "profile-reports.total",
                                        Map.of("count", Integer.toString(total))),
                                    lang.render("gui.separator"),
                                    lang.render("gui.click.open"))));
                        m.actions.put(slot, () -> subject(p, subject, name, 0, actual));
                      }
                      if (order.isEmpty())
                        m.inventory.setItem(
                            22,
                            MenuStyle.item(
                                Material.PAPER,
                                lang.render("profile-reports.empty"),
                                List.of(lang.render("profile-reports.empty-lore"))));
                      paging(m, actual, order.size(), a -> list(p, a));
                      MenuStyle.open(p, m.inventory);
                    }));
  }

  /** Previous/next live in the header (gui_gen: pagination is a control button). */
  private void paging(Menu m, int page, int count, java.util.function.IntConsumer open) {
    if (page > 0) {
      m.inventory.setItem(1, MenuStyle.button(lang, Material.ARROW, "gui.previous"));
      m.actions.put(1, () -> open.accept(page - 1));
    }
    if ((page + 1) * 21 < count) {
      m.inventory.setItem(2, MenuStyle.button(lang, Material.ARROW, "common.next"));
      m.actions.put(2, () -> open.accept(page + 1));
    }
  }

  private void subject(Player p, UUID subject, String name, int page, int back) {
    if (!staff(p)) throw new IllegalArgumentException("errors.permission");
    store
        .list("player-report", Report.class)
        .whenComplete(
            (saved, e) ->
                main.execute(
                    () -> {
                      if (!staff(p)) return;
                      if (e != null) {
                        ui.error(p, e);
                        return;
                      }
                      var docs =
                          saved.stream()
                              .filter(d -> d.value().subject().equals(subject))
                              .sorted(
                                  Comparator.<SqliteStore.Document<Report>, Boolean>comparing(
                                          d -> d.value().state() != ReportRules.State.OPEN)
                                      .thenComparing(
                                          Comparator.comparingLong(
                                                  (SqliteStore.Document<Report> d) ->
                                                      d.value().created())
                                              .reversed()))
                              .toList();
                      int actual = Math.clamp(page, 0, Math.max(0, (docs.size() - 1) / 21));
                      var m = menu("profile-reports.title");
                      m.inventory.setItem(
                          0,
                          skull(
                              subject,
                              lang.render("profile-reports.subject", Map.of("player", name)),
                              List.of()));
                      long window = windowMillis(), now = System.currentTimeMillis();
                      var all = saved.stream().map(SqliteStore.Document::value).toList();
                      int open = ReportRules.distinctOpenAuthors(all, subject, now, window);
                      for (int i = actual * 21;
                          i < Math.min(docs.size(), (actual + 1) * 21);
                          i++) {
                        var d = docs.get(i);
                        var report = d.value();
                        int slot = MenuStyle.CONTENT[i % 21];
                        String preview =
                            report.text().length() > 48
                                ? report.text().substring(0, 48) + "…"
                                : report.text();
                        m.inventory.setItem(
                            slot,
                            MenuStyle.item(
                                report.state() == ReportRules.State.OPEN
                                    ? Material.WRITABLE_BOOK
                                    : Material.BOOK,
                                lang.render(
                                    "profile-reports.author",
                                    Map.of("player", report.authorName())),
                                List.of(
                                    lang.render(
                                        "profile-reports.status-"
                                            + report.state().name().toLowerCase(Locale.ROOT)),
                                    Component.text(preview, NamedTextColor.GRAY),
                                    lang.render("gui.separator"),
                                    lang.render("gui.click.open"))));
                        m.actions.put(slot, () -> detail(p, d, name, actual, back));
                      }
                      if (open >= dangerThreshold() && p.hasPermission("loveessential.reports.ban")) {
                        m.inventory.setItem(
                            7,
                            MenuStyle.button(
                                lang,
                                Material.NETHERITE_AXE,
                                "profile-reports.ban",
                                "gui.separator",
                                "gui.click.open"));
                        m.actions.put(7, () -> ban(p, subject, name, actual, back));
                      }
                      paging(m, actual, docs.size(), a -> subject(p, subject, name, a, back));
                      MenuStyle.open(p, m.inventory);
                    }));
  }

  private void ban(Player p, UUID subject, String name, int page, int back) {
    if (!staff(p) || !p.hasPermission("loveessential.reports.ban"))
      throw new IllegalArgumentException("errors.permission");
    var commands = plugin.getConfig().getStringList("profile.reports.tempban.commands");
    if (commands.isEmpty()) throw new IllegalArgumentException("profile-reports.ban-unconfigured");
    ui.show(
        p,
        "profile-reports.ban",
        "profile-reports.ban-help",
        List.of(),
        List.of(
            ui.button(
                "ban",
                "profile-reports.ban-confirm",
                values -> {
                  if (!staff(p) || !p.hasPermission("loveessential.reports.ban")) return;
                  String duration =
                      plugin.getConfig().getString("profile.reports.tempban.duration", "1d");
                  String reason =
                      plugin
                          .getConfig()
                          .getString("profile.reports.tempban.reason", "Жалобы игроков");
                  // The reason is fixed text from the config, never complaint text from players.
                  for (String command : commands)
                    Bukkit.dispatchCommand(
                        Bukkit.getConsoleSender(),
                        command
                            .replace("{player}", name)
                            .replace("{duration}", duration)
                            .replace("{reason}", reason));
                  lang.send(p, "profile-reports.banned", Map.of("player", name));
                  subject(p, subject, name, page, back);
                }),
            ui.button("back", "common.back", v -> subject(p, subject, name, page, back))),
        this::staff);
  }

  private void detail(
      Player p, SqliteStore.Document<Report> d, String name, int page, int back) {
    var r = d.value();
    var buttons = new ArrayList<NativeDialogs.Button>();
    if (r.state() == ReportRules.State.OPEN) {
      buttons.add(
          ui.button(
              "confirm",
              "profile-reports.confirm",
              values -> handle(p, d, ReportRules.State.CONFIRMED, name, page, back)));
      buttons.add(
          ui.button(
              "reject",
              "profile-reports.reject",
              values -> handle(p, d, ReportRules.State.REJECTED, name, page, back)));
    }
    buttons.add(ui.button("back", "common.back", v -> subject(p, r.subject(), name, page, back)));
    ui.show(
        p,
        "profile-reports.detail",
        "profile-reports.detail-help",
        List.of(
            ui.text("author", "profile-reports.author-label", r.authorName(), 64),
            ui.text("target", "profile-reports.target-label", r.subjectName(), 64),
            ui.text("reason", "profile-reports.reason", r.text(), 1024)),
        buttons,
        this::staff);
  }

  private void handle(
      Player p,
      SqliteStore.Document<Report> d,
      ReportRules.State next,
      String name,
      int page,
      int back) {
    if (!staff(p)) return;
    var r = d.value();
    var value = r.handled(next, p.getName(), System.currentTimeMillis());
    store
        .save("player-report", d.id(), d.revision(), value, p.getUniqueId().toString())
        .whenComplete(
            (rev, e) ->
                main.execute(
                    () -> {
                      if (e != null) {
                        if (p.isOnline() && staff(p)) ui.error(p, e);
                        return;
                      }
                      if (next == ReportRules.State.CONFIRMED)
                        love.modifyReputation(
                            r.subject(),
                            plugin
                                .getConfig()
                                .getInt("profile.reports.confirm-politeness-delta", -100));
                      var author = Bukkit.getPlayer(r.author());
                      if (author != null && plugin.getConfig().getBoolean("profile.reports.notify-author", true))
                        lang.send(
                            author,
                            next == ReportRules.State.CONFIRMED
                                ? "profile-reports.outcome-confirmed"
                                : "profile-reports.outcome-rejected",
                            Map.of("player", r.subjectName()));
                      if (p.isOnline() && staff(p)) subject(p, r.subject(), name, page, back);
                    }));
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    Bukkit.getScheduler().runTaskLater(plugin, () -> summary(e.getPlayer()), 100L);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)
        || e.getRawSlot() < 0
        || e.getRawSlot() >= 54
        || e.getClick() != ClickType.LEFT) return;
    var action = m.actions.get(e.getRawSlot());
    if (action != null)
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (staff(p) && p.getOpenInventory().getTopInventory() == m.inventory) action.run();
              });
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  public void close() {
    HandlerList.unregisterAll(this);
    submitting.clear();
  }
}
