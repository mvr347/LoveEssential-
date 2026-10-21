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
      boolean resolved) {}

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

  private void submit(Player p, UUID target, String name, String text, BooleanSupplier visible) {
    if (!ready(p) || !visible.getAsBoolean())
      throw new IllegalArgumentException("profile-menu.hidden");
    text = text.strip();
    if (text.length() < 8 || text.length() > 1024)
      throw new IllegalArgumentException("profile-reports.reason-length");
    if (!submitting.add(p.getUniqueId())) throw new IllegalArgumentException("wizard.busy");
    String prefix = p.getUniqueId() + ":";
    long now = System.currentTimeMillis();
    long cooldown =
        Math.clamp(plugin.getConfig().getLong("profile.reports.cooldown-seconds", 300), 0, 86400)
            * 1000;
    String actor = p.getUniqueId().toString();
    var report = new Report(p.getUniqueId(), p.getName(), target, name, text, now, false);
    store
        .listPrefix("player-report", prefix, Report.class)
        .thenCompose(
            saved -> {
              long latest = saved.stream().mapToLong(d -> d.value().created()).max().orElse(0);
              if (now - latest < cooldown)
                throw new IllegalArgumentException("profile-reports.cooldown");
              return store.save("player-report", prefix + UUID.randomUUID(), 0, report, actor);
            })
        .whenComplete(
            (rev, e) ->
                main.execute(
                    () -> {
                      submitting.remove(p.getUniqueId());
                      if (!p.isOnline()) return;
                      if (e != null) {
                        ui.error(p, e);
                        return;
                      }
                      lang.send(p, "profile-reports.sent");
                      plugin.getCommand("profile").execute(p, "profile", new String[0]);
                    }));
  }

  public void command(Player p, String[] args) {
    if (!staff(p)) throw new IllegalArgumentException("errors.permission");
    if (args.length != 0) throw new IllegalArgumentException("errors.input");
    list(p, 0);
  }

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
                      var records =
                          saved.stream()
                              .sorted(
                                  Comparator.<SqliteStore.Document<Report>, Boolean>comparing(
                                          d -> d.value().resolved())
                                      .thenComparing(
                                          Comparator.comparingLong(
                                                  (SqliteStore.Document<Report> d) ->
                                                      d.value().created())
                                              .reversed()))
                              .toList();
                      int actual = Math.clamp(page, 0, Math.max(0, (records.size() - 1) / 21));
                      var m = new Menu();
                      m.inventory =
                          Bukkit.createInventory(m, 54, lang.render("profile-reports.title"));
                      MenuStyle.frame(m.inventory);
                      m.inventory.setItem(
                          0,
                          PlayerProfiles.head(p, lang.render("profile-reports.title"), List.of()));
                      for (int i = actual * 21;
                          i < Math.min(records.size(), (actual + 1) * 21);
                          i++) {
                        var d = records.get(i);
                        var report = d.value();
                        int slot = MenuStyle.CONTENT[i % 21];
                        m.inventory.setItem(
                            slot,
                            MenuStyle.item(
                                Material.PLAYER_HEAD,
                                lang.render(
                                    "profile-reports.entry",
                                    Map.of("player", report.subjectName())),
                                List.of(
                                    lang.render(
                                        "profile-reports.author",
                                        Map.of("player", report.authorName())),
                                    lang.render(
                                        report.resolved()
                                            ? "profile-reports.resolved"
                                            : "profile-reports.new"),
                                    lang.render("gui.separator"),
                                    lang.render("gui.click.open"))));
                        m.actions.put(slot, () -> detail(p, d, actual));
                      }
                      if (actual > 0) {
                        m.inventory.setItem(
                            36, MenuStyle.button(lang, Material.ARROW, "gui.previous"));
                        m.actions.put(36, () -> list(p, actual - 1));
                      }
                      if ((actual + 1) * 21 < records.size()) {
                        m.inventory.setItem(
                            44, MenuStyle.button(lang, Material.ARROW, "common.next"));
                        m.actions.put(44, () -> list(p, actual + 1));
                      }
                      m.inventory.setItem(
                          51, MenuStyle.button(lang, Material.ARROW, "common.back"));
                      m.actions.put(
                          51,
                          () ->
                              plugin
                                  .getCommand("loveessential")
                                  .execute(p, "loveessential", new String[0]));
                      m.inventory.setItem(
                          52, MenuStyle.button(lang, Material.BARRIER, "gui.close"));
                      m.actions.put(52, p::closeInventory);
                      MenuStyle.open(p, m.inventory);
                    }));
  }

  private void detail(Player p, SqliteStore.Document<Report> d, int page) {
    var r = d.value();
    ui.show(
        p,
        "profile-reports.detail",
        "profile-reports.detail-help",
        List.of(
            ui.text("author", "profile-reports.author-label", r.authorName(), 64),
            ui.text("target", "profile-reports.target-label", r.subjectName(), 64),
            ui.text("reason", "profile-reports.reason", r.text(), 1024)),
        List.of(
            ui.button(
                "resolve",
                "profile-reports.resolve",
                values -> {
                  if (!staff(p)) return;
                  var value =
                      new Report(
                          r.author(),
                          r.authorName(),
                          r.subject(),
                          r.subjectName(),
                          r.text(),
                          r.created(),
                          true);
                  store
                      .save(
                          "player-report", d.id(), d.revision(), value, p.getUniqueId().toString())
                      .whenComplete(
                          (rev, e) ->
                              main.execute(
                                  () -> {
                                    if (!staff(p)) return;
                                    if (e != null) ui.error(p, e);
                                    else list(p, page);
                                  }));
                }),
            ui.button("back", "common.back", v -> list(p, page))),
        this::staff);
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
