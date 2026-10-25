package dev.lovelace.loveessential.portal;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.condition.ActionEngine;
import dev.lovelace.loveessential.content.ContentLibrary;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.gui.MenuStyle;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.module.TeleportService;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;

/**
 * SQL portals, guided drafts and bounded client particles. Every action is checked on entry and
 * execution.
 */
public final class Portals implements Listener, AutoCloseable {
  private record Draft(PortalDefinition value, long targetRevision) {}

  private record Selection(int corner, long expires) {}

  private record Activation(String portal, long revision, long due) {}

  private static final class Menu implements dev.lovelace.loveessential.gui.GuiLayout {
    Inventory inventory;
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

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }
  }

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final TeleportService teleport;
  private final ActionEngine actions;
  private NativeDialogs ui;
  private ContentLibrary texts;
  private final Map<String, SqliteStore.Document<PortalDefinition>> portals = new TreeMap<>();
  private final Map<UUID, SqliteStore.Document<Draft>> drafts = new HashMap<>();
  private final Map<UUID, Selection> selections = new HashMap<>();
  private final Set<UUID> saving = new HashSet<>();
  private final Map<UUID, UUID> sessions = new HashMap<>();

  private UUID session(Player p) {
    return sessions.computeIfAbsent(p.getUniqueId(), ignored -> UUID.randomUUID());
  }

  private boolean currentSession(Player p, UUID token) {
    return p.isOnline() && token.equals(sessions.get(p.getUniqueId()));
  }

  private final Map<UUID, Set<String>> inside = new HashMap<>();
  private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
  private final Map<UUID, Activation> pending = new HashMap<>();
  private final Map<String, Long> created = new HashMap<>();
  private org.bukkit.scheduler.BukkitTask task;
  private long ticks;
  private Consumer<Player> parent = p -> {};
  private static final List<String> PARTICLES =
      List.of("DUST", "END_ROD", "PORTAL", "REVERSE_PORTAL", "ENCHANT", "SOUL_FIRE_FLAME");
  private static final String ICON =
      "d0d5a4eba140cbb34d45e40d62dd3f7e6584b2ab0b4155a0bcc603d5d750f793";

  public Portals(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      TeleportService teleport,
      ActionEngine actions) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.teleport = teleport;
    this.actions = actions;
  }

  public void ui(NativeDialogs ui, ContentLibrary texts, Consumer<Player> parent) {
    this.ui = ui;
    this.texts = texts;
    this.parent = parent;
  }

  public Collection<SqliteStore.Document<PortalDefinition>> all() {
    return List.copyOf(portals.values());
  }

  public CompletableFuture<Void> load() {
    return store
        .list("portal", PortalDefinition.class)
        .thenCompose(
            saved ->
                main.call(
                    () -> {
                      portals.clear();
                      saved.forEach(
                          d -> {
                            try {
                              validate(d.value(), false);
                              portals.put(d.id(), d);
                            } catch (RuntimeException e) {
                              // A limit tightened after the portal was saved must not take the
                              // whole plugin down; the record stays in the database untouched.
                              plugin
                                  .getLogger()
                                  .warning(
                                      "Skipping portal '" + d.id() + "': " + e.getMessage());
                            }
                          });
                      return null;
                    }))
        .thenCompose(v -> store.list("place", Gameplay.Place.class))
        .thenCompose(
            legacy -> {
              CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
              for (var d : legacy)
                if (d.id().startsWith("portal:")) {
                  String id = d.id().substring(7);
                  chain =
                      chain
                          .thenCompose(v -> main.call(() -> portals.containsKey(id)))
                          .thenCompose(
                              exists -> {
                                if (exists) return CompletableFuture.completedFuture(null);
                                var old = d.value();
                                var o = old.options();
                                var first = old.position();
                                var second =
                                    new Position(
                                        first.world(),
                                        Double.parseDouble(o.getOrDefault("x2", "" + first.x())),
                                        Double.parseDouble(o.getOrDefault("y2", "" + first.y())),
                                        Double.parseDouble(o.getOrDefault("z2", "" + first.z())),
                                        first.yaw(),
                                        first.pitch());
                                Position destination =
                                    o.containsKey("destination")
                                        ? new com.google.gson.Gson()
                                            .fromJson(o.get("destination"), Position.class)
                                        : null;
                                var value =
                                    new PortalDefinition(
                                        id,
                                        "portal-ui.migrated-name",
                                        first,
                                        second,
                                        destination,
                                        destination != null,
                                        "",
                                        List.of(),
                                        3,
                                        0,
                                        true,
                                        false,
                                        "DUST",
                                        "B68CFF",
                                        true,
                                        .5,
                                        List.of());
                                return main.call(() -> save(value, 0, "migration"))
                                    .thenCompose(future -> future)
                                    .thenApply(x -> null);
                              });
                }
              return chain;
            });
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2, 2);
  }

  private boolean admin(Player p) {
    return plugin.ready()
        && p.isOnline()
        && love.authenticated(p)
        && p.hasPermission("loveessential.admin");
  }

  private void requireAdmin(Player p) {
    if (!love.authenticated(p)) throw new IllegalArgumentException("errors.access");
    if (!admin(p)) throw new IllegalArgumentException("errors.permission");
  }

  private PortalDefinition defaults(Player p) {
    var at = Position.of(p.getLocation().getBlock().getLocation());
    return new PortalDefinition(
        "portal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
        "portal-ui.new-name",
        at,
        at,
        null,
        true,
        "",
        List.of(),
        plugin.getConfig().getLong("portal.default-cooldown-seconds", 3),
        0,
        true,
        false,
        "DUST",
        "B68CFF",
        true,
        .5,
        List.of());
  }

  public void command(Player p, String[] args) {
    requireAdmin(p);
    String op =
        args.length == 0
            ? "list"
            : switch (args[0].toLowerCase(Locale.ROOT)) {
              case "создать", "мастер" -> "wizard";
              case "изменить" -> "edit";
              case "удалить" -> "delete";
              case "отмена" -> "cancel";
              case "точка1" -> "pos1";
              case "точка2" -> "pos2";
              case "назначение" -> "destination";
              default -> args[0].toLowerCase(Locale.ROOT);
            };
    switch (op) {
      case "list" -> list(p, 0);
      case "create", "wizard" -> {
        if (args.length > 1) throw new IllegalArgumentException("portal-ui.usage");
        resume(p);
      }
      case "edit" -> {
        if (args.length != 2) throw new IllegalArgumentException("portal-ui.usage");
        edit(p, args[1]);
      }
      case "pos1", "pos2" -> {
        var d = drafts.get(p.getUniqueId());
        if (d == null) {
          resume(p);
          return;
        }
        select(p, op.equals("pos1") ? 1 : 2);
      }
      case "destination" -> {
        if (args.length == 2) {
          edit(p, args[1]);
        } else {
          var d = draft(p);
          change(
              p,
              copy(d.value().value(), null, null, Position.of(p.getLocation()), null),
              () -> wizard(p));
        }
      }
      case "delete" -> {
        if (args.length != 2) throw new IllegalArgumentException("portal-ui.usage");
        var d = portals.get(args[1]);
        if (d == null) throw new IllegalArgumentException("errors.not-found");
        confirmDelete(p, d);
      }
      case "cancel" -> {
        selections.remove(p.getUniqueId());
        discard(p);
      }
      default -> throw new IllegalArgumentException("portal-ui.usage");
    }
  }

  public List<String> suggestions(Player p, String[] args) {
    if (!admin(p)) return List.of();
    return args.length == 1
        ? List.of(
            "wizard",
            "мастер",
            "edit",
            "изменить",
            "delete",
            "удалить",
            "cancel",
            "отмена",
            "pos1",
            "pos2",
            "destination")
        : args.length == 2 ? List.copyOf(portals.keySet()) : List.of();
  }

  private void resume(Player p) {
    UUID token = session(p);
    if (drafts.containsKey(p.getUniqueId())) {
      wizard(p);
      return;
    }
    if (!saving.add(p.getUniqueId())) return;
    store
        .get("portal-draft", p.getUniqueId().toString(), Draft.class)
        .whenComplete(
            (d, e) ->
                main.execute(
                    () -> {
                      saving.remove(p.getUniqueId());
                      if (!currentSession(p, token) || !admin(p)) return;
                      if (e != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      drafts.put(
                          p.getUniqueId(),
                          d.orElseGet(
                              () ->
                                  new SqliteStore.Document<>(
                                      p.getUniqueId().toString(), 0, new Draft(defaults(p), 0))));
                      wizard(p);
                    }));
  }

  private void edit(Player p, String id) {
    UUID token = session(p);
    if (drafts.containsKey(p.getUniqueId()))
      throw new IllegalArgumentException("portal-ui.draft-exists");
    var d = portals.get(id);
    if (d == null) throw new IllegalArgumentException("errors.not-found");
    if (!saving.add(p.getUniqueId())) return;
    store
        .get("portal-draft", p.getUniqueId().toString(), Draft.class)
        .whenComplete(
            (saved, e) ->
                main.execute(
                    () -> {
                      saving.remove(p.getUniqueId());
                      if (!currentSession(p, token) || !admin(p)) return;
                      if (e != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      if (saved.isPresent()) {
                        drafts.put(p.getUniqueId(), saved.get());
                        lang.send(p, "portal-ui.draft-exists");
                        wizard(p);
                        return;
                      }
                      drafts.put(
                          p.getUniqueId(),
                          new SqliteStore.Document<>(
                              p.getUniqueId().toString(), 0, new Draft(d.value(), d.revision())));
                      wizard(p);
                    }));
  }

  private SqliteStore.Document<Draft> draft(Player p) {
    var d = drafts.get(p.getUniqueId());
    if (d == null) throw new IllegalArgumentException("portal-ui.no-draft");
    return d;
  }

  private PortalDefinition copy(
      PortalDefinition d,
      Position a,
      Position b,
      Position dest,
      List<dev.lovelace.loveessential.api.Action> action) {
    return new PortalDefinition(
        d.id(),
        d.nameKey(),
        a == null ? d.first() : a,
        b == null ? d.second() : b,
        dest == null ? d.destination() : dest,
        d.enabled(),
        d.permission(),
        d.gamemodes(),
        d.cooldownSeconds(),
        d.warmupTicks(),
        d.blockCombat(),
        d.allowVehicles(),
        d.particle(),
        d.color(),
        d.fill(),
        d.spacing(),
        action == null ? d.actions() : action);
  }

  private void change(Player p, PortalDefinition next, Runnable after) {
    UUID token = session(p);
    requireAdmin(p);
    validate(next, false);
    var old = draft(p);
    if (!saving.add(p.getUniqueId())) return;
    store
        .save(
            "portal-draft",
            p.getUniqueId().toString(),
            old.revision(),
            new Draft(next, old.value().targetRevision()),
            p.getUniqueId().toString())
        .whenComplete(
            (rev, e) ->
                main.execute(
                    () -> {
                      saving.remove(p.getUniqueId());
                      if (!currentSession(p, token) || drafts.get(p.getUniqueId()) != old) return;
                      if (e != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      drafts.put(
                          p.getUniqueId(),
                          new SqliteStore.Document<>(
                              p.getUniqueId().toString(),
                              rev,
                              new Draft(next, old.value().targetRevision())));
                      if (admin(p)) after.run();
                    }));
  }

  public CompletableFuture<Long> save(PortalDefinition d, long revision, String actor) {
    MainThread.require();
    validate(d, true);
    if (revision == 0 && portals.size() >= plugin.getConfig().getInt("portal.max-portals", 128))
      throw new IllegalArgumentException("portal-ui.limit");
    return store
        .save("portal", d.id(), revision, d, actor)
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      portals.put(d.id(), new SqliteStore.Document<>(d.id(), rev, d));
                      created.put(d.id(), ticks);
                      return rev;
                    }));
  }

  public void validate(PortalDefinition d, boolean publish) {
    if (d == null
        || d.id() == null
        || d.first() == null
        || d.second() == null
        || d.permission() == null
        || d.color() == null
        || !d.id().matches("[a-z0-9_-]{1,48}")
        || d.nameKey() == null
        || !d.nameKey().matches("[a-zA-Z0-9_.-]{1,200}")
        || !d.first().world().equals(d.second().world())
        || !Double.isFinite(d.volume())
        || d.volume() > plugin.getConfig().getDouble("portal.max-volume", 32768)
        || d.cooldownSeconds() < 0
        || d.cooldownSeconds() > 86400
        || d.warmupTicks() < 0
        || d.warmupTicks() > 1200
        || !PARTICLES.contains(d.particle())
        || !d.color().matches("[a-fA-F0-9]{6}")
        || !Double.isFinite(d.spacing())
        || d.spacing() < .2
        || d.spacing() > 4
        || d.actions().size() > 16) throw new IllegalArgumentException("portal-ui.invalid");
    for (var mode : d.gamemodes())
      try {
        GameMode.valueOf(mode.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("portal-ui.invalid");
      }
    for (var a : d.actions()) {
      if (a.type().equals("portal-command")) commandValue(a.args(), publish);
      else if (!actions.known(a.type())) throw new IllegalArgumentException("portal-ui.invalid");
    }
    if (publish && d.enabled() && d.destination() == null && d.actions().isEmpty())
      throw new IllegalArgumentException("portal-ui.no-action");
    for (Position p : List.of(d.first(), d.second()))
      if (!Double.isFinite(p.x())
          || !Double.isFinite(p.y())
          || !Double.isFinite(p.z())
          || Math.abs(p.x()) > 29999984
          || Math.abs(p.z()) > 29999984) throw new IllegalArgumentException("portal-ui.invalid");
  }

  private String commandValue(Map<String, String> args, boolean enforce) {
    String value = args.getOrDefault("value", "").strip();
    String sender = args.getOrDefault("sender", "player");
    if (value.isBlank()
        || value.length() > 512
        || value.startsWith("/")
        || value.contains("\n")
        || value.contains("\r")
        || value.indexOf('\0') >= 0
        || !Set.of("player", "console").contains(sender))
      throw new IllegalArgumentException("portal-ui.invalid");
    String root = value.split(" ", 2)[0].toLowerCase(Locale.ROOT);
    if (enforce
        && !plugin
            .getConfig()
            .getStringList("portal.allowed-" + sender + "-commands")
            .contains(root)) throw new IllegalArgumentException("portal-ui.command-denied");
    return value;
  }

  private Menu menu(Player p, int size, String title, Runnable back) {
    requireAdmin(p);
    var m = new Menu();
    m.menuId = title;
    m.inventory = Bukkit.createInventory(m, size, MenuStyle.title(lang, title, Map.of()));
    MenuStyle.frame(m.inventory);
    m.inventory.setItem(
        0, dev.lovelace.loveessential.module.PlayerProfiles.head(p, lang.render(title), List.of()));
    if (back != null) button(m, size - 3, "common.back", "gui.click.open", back);
    button(m, size - 2, "gui.close", "gui.click.close", p::closeInventory);
    return m;
  }

  private void button(Menu m, int slot, String title, String lore, Runnable run) {
    m.inventory.setItem(
        slot,
        MenuStyle.head(
            ICON, lang.render(title), List.of(lang.render("gui.separator"), lang.render(lore))));
    m.actions.put(slot, run);
  }

  public void list(Player p, int page) {
    var values = List.copyOf(portals.values());
    int actual = Math.clamp(page, 0, Math.max(0, (values.size() - 1) / 20));
    var m = menu(p, 54, "portal-ui.list", null);
    for (int i = actual * 20; i < Math.min(values.size(), (actual + 1) * 20); i++) {
      var d = values.get(i);
      int slot = MenuStyle.slot(i % 20, Math.min(20, values.size() - actual * 20) + 1);
      m.inventory.setItem(
          slot,
          MenuStyle.head(
              ICON,
              lang.render(d.value().nameKey()),
              List.of(
                  lang.render("portal-ui.id", Map.of("id", d.id())),
                  lang.render(
                      d.value().enabled() ? "profile-menu.enabled" : "profile-menu.disabled"),
                  lang.render("gui.separator"),
                  lang.render("gui.click.edit"))));
      m.actions.put(slot, () -> edit(p, d.id()));
    }
    int shown = Math.min(20, values.size() - actual * 20);
    button(
        m,
        MenuStyle.slot(shown, shown + 1),
        "portal-ui.create",
        "portal-ui.create-help",
        () -> resume(p));
    if (actual > 0) button(m, 36, "gui.previous", "gui.click.open", () -> list(p, actual - 1));
    if ((actual + 1) * 20 < values.size())
      button(m, 44, "common.next", "gui.click.open", () -> list(p, actual + 1));
    MenuStyle.open(p, m.inventory);
  }

  private void wizard(Player p) {
    var d = draft(p).value().value();
    var m = menu(p, 54, "portal-ui.wizard", () -> list(p, 0));
    String[] keys = {
      "first", "second", "destination", "actions", "access", "effects", "preview", "name", "publish"
    };
    Runnable[] handlers = {
      () -> select(p, 1),
      () -> select(p, 2),
      () -> destinationForm(p),
      () -> actionMenu(p),
      () -> accessForm(p),
      () -> effectForm(p),
      () -> preview(p),
      () -> nameForm(p),
      () -> publish(p)
    };
    for (int i = 0; i < keys.length; i++) {
      int slot = MenuStyle.slot(i, keys.length);
      String key = "portal-ui." + keys[i];
      button(m, slot, key, key + "-help", handlers[i]);
      var item = m.inventory.getItem(slot);
      var meta = item.getItemMeta();
      var lore = new ArrayList<>(meta.lore());
      if (i < 2) {
        var pos = i == 0 ? d.first() : d.second();
        lore.add(
            0,
            lang.render(
                "portal-ui.coordinates",
                Map.of("x", "" + (int) pos.x(), "y", "" + (int) pos.y(), "z", "" + (int) pos.z())));
      }
      if (i == 2)
        lore.add(
            0,
            lang.render(
                d.destination() == null ? "portal-ui.not-set" : "portal-ui.destination-set"));
      if (i == 3)
        lore.add(
            0, lang.render("portal-ui.action-count", Map.of("count", "" + d.actions().size())));
      meta.lore(lore);
      item.setItemMeta(meta);
    }
    button(
        m,
        50,
        "portal-ui.discard",
        "portal-ui.discard-help",
        () ->
            ui.confirm(
                p, "portal-ui.discard-confirm", () -> discard(p), () -> wizard(p), this::admin));
    MenuStyle.open(p, m.inventory);
  }

  private void select(Player p, int corner) {
    selections.put(p.getUniqueId(), new Selection(corner, System.currentTimeMillis() + 60000));
    p.closeInventory();
    lang.send(p, corner == 1 ? "portal-ui.select-first" : "portal-ui.select-second");
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void selectBlock(PlayerInteractEvent e) {
    var p = e.getPlayer();
    var selection = selections.get(p.getUniqueId());
    if (selection == null
        || e.getAction() != Action.RIGHT_CLICK_BLOCK
        || e.getClickedBlock() == null
        || e.getHand() != EquipmentSlot.HAND) return;
    e.setCancelled(true);
    selections.remove(p.getUniqueId());
    if (!admin(p) || selection.expires() < System.currentTimeMillis()) return;
    var d = draft(p).value().value();
    var pos = Position.of(e.getClickedBlock().getLocation());
    if (!pos.world().equals(d.first().world())) {
      lang.send(p, "portal-ui.same-world");
      return;
    }
    change(
        p,
        copy(
            d,
            selection.corner() == 1 ? pos : null,
            selection.corner() == 2 ? pos : null,
            null,
            null),
        () -> {
          preview(p);
          wizard(p);
        });
  }

  private void destinationForm(Player p) {
    var d = draft(p).value().value();
    ui.show(
        p,
        "portal-ui.destination",
        "portal-ui.destination-help",
        List.of(),
        List.of(
            ui.button(
                "here",
                "portal-ui.destination-here",
                values ->
                    change(
                        p,
                        copy(d, null, null, Position.of(p.getLocation()), null),
                        () -> wizard(p))),
            ui.button(
                "clear",
                "portal-ui.destination-clear",
                values ->
                    change(
                        p,
                        new PortalDefinition(
                            d.id(),
                            d.nameKey(),
                            d.first(),
                            d.second(),
                            null,
                            d.enabled(),
                            d.permission(),
                            d.gamemodes(),
                            d.cooldownSeconds(),
                            d.warmupTicks(),
                            d.blockCombat(),
                            d.allowVehicles(),
                            d.particle(),
                            d.color(),
                            d.fill(),
                            d.spacing(),
                            d.actions()),
                        () -> wizard(p))),
            ui.button("back", "common.back", values -> wizard(p))),
        this::admin);
  }

  private void nameForm(Player p) {
    var d = draft(p).value().value();
    ui.show(
        p,
        "portal-ui.name",
        "portal-ui.name-help",
        List.of(ui.text("name", "portal-ui.name", lang.text(d.nameKey()), 128)),
        List.of(
            ui.button(
                "save",
                "content.save",
                v -> {
                  String key = "portal-text." + d.id() + ".name";
                  texts
                      .texts(Map.of(key, v.get("name")))
                      .whenComplete(
                          (ignored, e) ->
                              main.execute(
                                  () -> {
                                    if (!admin(p)) return;
                                    if (e != null) {
                                      ui.error(p, e);
                                      return;
                                    }
                                    change(
                                        p,
                                        new PortalDefinition(
                                            d.id(),
                                            key,
                                            d.first(),
                                            d.second(),
                                            d.destination(),
                                            d.enabled(),
                                            d.permission(),
                                            d.gamemodes(),
                                            d.cooldownSeconds(),
                                            d.warmupTicks(),
                                            d.blockCombat(),
                                            d.allowVehicles(),
                                            d.particle(),
                                            d.color(),
                                            d.fill(),
                                            d.spacing(),
                                            d.actions()),
                                        () -> wizard(p));
                                  }));
                }),
            ui.button("back", "common.back", v -> wizard(p))),
        this::admin);
  }

  private NativeDialogs.Field choice(String id, String key, String value, List<String> options) {
    return NativeDialogs.Field.select(id, lang.render(key), value, options);
  }

  private void accessForm(Player p) {
    var d = draft(p).value().value();
    ui.show(
        p,
        "portal-ui.access",
        "portal-ui.access-help",
        List.of(
            ui.text("permission", "portal-ui.permission", d.permission(), 128),
            ui.text("modes", "portal-ui.modes", String.join(",", d.gamemodes()), 128),
            ui.text("cooldown", "portal-ui.cooldown", "" + d.cooldownSeconds(), 6),
            ui.text("warmup", "portal-ui.warmup", "" + d.warmupTicks(), 4),
            choice("enabled", "portal-ui.enabled", "" + d.enabled(), List.of("true", "false")),
            choice(
                "combat", "portal-ui.block-combat", "" + d.blockCombat(), List.of("true", "false")),
            choice(
                "vehicles",
                "portal-ui.vehicles",
                "" + d.allowVehicles(),
                List.of("true", "false"))),
        List.of(
            ui.button(
                "save",
                "content.save",
                v ->
                    change(
                        p,
                        new PortalDefinition(
                            d.id(),
                            d.nameKey(),
                            d.first(),
                            d.second(),
                            d.destination(),
                            Boolean.parseBoolean(v.get("enabled")),
                            v.get("permission"),
                            v.get("modes").isBlank()
                                ? List.of()
                                : Arrays.stream(v.get("modes").split(","))
                                    .map(String::strip)
                                    .toList(),
                            Long.parseLong(v.get("cooldown")),
                            Integer.parseInt(v.get("warmup")),
                            Boolean.parseBoolean(v.get("combat")),
                            Boolean.parseBoolean(v.get("vehicles")),
                            d.particle(),
                            d.color(),
                            d.fill(),
                            d.spacing(),
                            d.actions()),
                        () -> wizard(p))),
            ui.button("back", "common.back", v -> wizard(p))),
        this::admin);
  }

  private void effectForm(Player p) {
    var d = draft(p).value().value();
    ui.show(
        p,
        "portal-ui.effects",
        "portal-ui.effects-help",
        List.of(
            choice("particle", "portal-ui.particle", d.particle(), PARTICLES),
            ui.text("color", "portal-ui.color", d.color(), 6),
            choice("fill", "portal-ui.fill", "" + d.fill(), List.of("true", "false")),
            ui.text("spacing", "portal-ui.spacing", "" + d.spacing(), 4)),
        List.of(
            ui.button(
                "save",
                "content.save",
                v ->
                    change(
                        p,
                        new PortalDefinition(
                            d.id(),
                            d.nameKey(),
                            d.first(),
                            d.second(),
                            d.destination(),
                            d.enabled(),
                            d.permission(),
                            d.gamemodes(),
                            d.cooldownSeconds(),
                            d.warmupTicks(),
                            d.blockCombat(),
                            d.allowVehicles(),
                            v.get("particle"),
                            v.get("color"),
                            Boolean.parseBoolean(v.get("fill")),
                            Double.parseDouble(v.get("spacing")),
                            d.actions()),
                        () -> wizard(p))),
            ui.button("back", "common.back", v -> wizard(p))),
        this::admin);
  }

  private void actionMenu(Player p) {
    var d = draft(p).value().value();
    var m = menu(p, 54, "portal-ui.actions", () -> wizard(p));
    int count = d.actions().size();
    int total = count + (count < 16 ? 1 : 0);
    if (count < 16) button(m, MenuStyle.slot(count, total), "portal-ui.add-command", "portal-ui.command-help", () -> commandForm(p, -1));
    for (int i = 0; i < count; i++) {
      int index = i;
      var a = d.actions().get(i);
      int slot = MenuStyle.slot(i, total);
      m.inventory.setItem(
          slot,
          MenuStyle.head(
              ICON,
              lang.render("portal-ui.command-title", Map.of("index", "" + (i + 1))),
              List.of(
                  lang.render(
                      "portal-ui.command-value",
                      Map.of("value", a.args().getOrDefault("value", a.type()))),
                  lang.render("gui.separator"),
                  lang.render("gui.click.edit"))));
      m.actions.put(slot, () -> commandForm(p, index));
    }
    MenuStyle.open(p, m.inventory);
  }

  private void commandForm(Player p, int index) {
    var d = draft(p).value().value();
    var old =
        index < 0
            ? new dev.lovelace.loveessential.api.Action(
                "portal-command", Map.of("sender", "player", "value", ""))
            : d.actions().get(index);
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              var a =
                  new dev.lovelace.loveessential.api.Action(
                      "portal-command",
                      Map.of("value", v.get("command"), "sender", v.get("sender")));
              commandValue(a.args(), true);
              var list = new ArrayList<>(d.actions());
              if (index < 0) list.add(a);
              else list.set(index, a);
              change(p, copy(d, null, null, null, list), () -> actionMenu(p));
            }));
    if (index >= 0) {
      buttons.add(
          ui.button(
              "delete",
              "ui.delete",
              v -> {
                var list = new ArrayList<>(d.actions());
                list.remove(index);
                change(p, copy(d, null, null, null, list), () -> actionMenu(p));
              }));
      for (int direction : new int[] {-1, 1})
        if (index + direction >= 0 && index + direction < d.actions().size())
          buttons.add(
              ui.button(
                  direction < 0 ? "up" : "down",
                  direction < 0 ? "holo.up" : "holo.down",
                  v -> {
                    var list = new ArrayList<>(d.actions());
                    Collections.swap(list, index, index + direction);
                    change(p, copy(d, null, null, null, list), () -> actionMenu(p));
                  }));
    }
    buttons.add(ui.button("back", "common.back", v -> actionMenu(p)));
    ui.show(
        p,
        "portal-ui.command-title-base",
        "portal-ui.command-help",
        List.of(
            choice(
                "sender",
                "portal-ui.sender",
                old.args().getOrDefault("sender", "player"),
                List.of("player", "console")),
            ui.text("command", "portal-ui.command", old.args().getOrDefault("value", ""), 512)),
        buttons,
        this::admin);
  }

  private void publish(Player p) {
    var d = draft(p);
    UUID token = session(p);
    requireAdmin(p);
    if (!saving.add(p.getUniqueId())) return;
    CompletableFuture<Long> future;
    try {
      future = save(d.value().value(), d.value().targetRevision(), p.getUniqueId().toString());
    } catch (RuntimeException e) {
      saving.remove(p.getUniqueId());
      throw e;
    }
    future
        .thenCompose(
            rev ->
                d.revision() == 0
                    ? CompletableFuture.completedFuture(rev)
                    : store
                        .delete("portal-draft", d.id(), d.revision(), p.getUniqueId().toString())
                        .thenApply(ignored -> rev))
        .whenComplete(
            (rev, e) ->
                main.execute(
                    () -> {
                      saving.remove(p.getUniqueId());
                      if (!currentSession(p, token)) return;
                      if (e != null) {
                        ui.error(p, e);
                        return;
                      }
                      drafts.remove(p.getUniqueId(), d);
                      selections.remove(p.getUniqueId());
                      if (admin(p)) {
                        lang.send(p, "portal-ui.published");
                        list(p, 0);
                      }
                    }));
  }

  private void discard(Player p) {
    UUID token = session(p);
    var d = drafts.get(p.getUniqueId());
    selections.remove(p.getUniqueId());
    if (d == null || d.revision() == 0) {
      drafts.remove(p.getUniqueId());
      list(p, 0);
      return;
    }
    if (!saving.add(p.getUniqueId())) return;
    store
        .delete("portal-draft", d.id(), d.revision(), p.getUniqueId().toString())
        .whenComplete(
            (v, e) ->
                main.execute(
                    () -> {
                      saving.remove(p.getUniqueId());
                      if (!currentSession(p, token)) return;
                      if (e != null) {
                        if (p.isOnline()) ui.error(p, e);
                        return;
                      }
                      drafts.remove(p.getUniqueId());
                      if (admin(p)) list(p, 0);
                    }));
  }

  private void confirmDelete(Player p, SqliteStore.Document<PortalDefinition> d) {
    ui.confirm(
        p,
        "portal-ui.delete-confirm",
        () ->
            store
                .delete("portal", d.id(), d.revision(), p.getUniqueId().toString())
                .whenComplete(
                    (v, e) ->
                        main.execute(
                            () -> {
                              if (!admin(p)) return;
                              if (e != null) {
                                ui.error(p, e);
                                return;
                              }
                              portals.remove(d.id());
                              created.remove(d.id());
                              pending
                                  .entrySet()
                                  .removeIf(entry -> entry.getValue().portal().equals(d.id()));
                              list(p, 0);
                            })),
        () -> list(p, 0),
        this::admin);
  }

  private boolean allowed(Player p, PortalDefinition d) {
    return plugin.allows("portal", p)
        && d.enabled()
        && love.authenticated(p)
        && !plugin.getConfig().getStringList("portal.disabled-worlds").stream()
            .anyMatch(w -> w.equalsIgnoreCase(p.getWorld().getName()))
        && (d.permission().isBlank() || p.hasPermission(d.permission()))
        && (d.gamemodes().isEmpty()
            || d.gamemodes().stream().anyMatch(m -> m.equalsIgnoreCase(p.getGameMode().name())))
        && (!d.blockCombat() || !love.combat(p))
        && (d.allowVehicles() || !p.isInsideVehicle())
        && love.territorialAccess(p, p.getLocation());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    if (e.getTo() == null) return;
    var p = e.getPlayer();
    Set<String> previous = inside.getOrDefault(p.getUniqueId(), Set.of());
    Set<String> next = new HashSet<>();
    for (var doc : portals.values()) {
      var d = doc.value();
      if (!d.contains(Position.of(e.getTo()))) continue;
      next.add(d.id());
      if (previous.contains(d.id()) || !allowed(p, d) || pending.containsKey(p.getUniqueId()))
        continue;
      if (cooldowns.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(d.id(), 0L)
          > System.currentTimeMillis()) continue;
      pending.put(p.getUniqueId(), new Activation(d.id(), doc.revision(), ticks + d.warmupTicks()));
      if (d.warmupTicks() > 0) p.sendActionBar(lang.render("portal-ui.warming"));
    }
    inside.put(p.getUniqueId(), Set.copyOf(next));
  }

  private void tick() {
    ticks += 2;
    for (var entry : List.copyOf(pending.entrySet())) {
      var p = Bukkit.getPlayer(entry.getKey());
      var a = entry.getValue();
      var d = portals.get(a.portal());
      if (p == null
          || d == null
          || d.revision() != a.revision()
          || !allowed(p, d.value())
          || !d.value().contains(Position.of(p.getLocation()))) {
        pending.remove(entry.getKey());
        continue;
      }
      if (ticks < a.due()) continue;
      pending.remove(entry.getKey());
      cooldowns
          .computeIfAbsent(entry.getKey(), id -> new HashMap<>())
          .put(a.portal(), System.currentTimeMillis() + d.value().cooldownSeconds() * 1000);
      activate(p, d.value());
    }
    selections.entrySet().removeIf(e -> e.getValue().expires() < System.currentTimeMillis());
    if (plugin.module("portal")
        && ticks % Math.max(2, plugin.getConfig().getInt("portal.particles.refresh-ticks", 6)) == 0)
      render();
  }

  private void activate(Player p, PortalDefinition d) {
    for (var a : d.actions()) {
      if (!allowed(p, d) || !d.contains(Position.of(p.getLocation()))) return;
      if (a.type().equals("portal-command")) {
        String value;
        try {
          value = commandValue(a.args(), true).replace("{player}", p.getName());
        } catch (IllegalArgumentException e) {
          lang.send(p, e.getMessage());
          return;
        }
        boolean success =
            a.args().getOrDefault("sender", "player").equals("console")
                ? Bukkit.dispatchCommand(Bukkit.getConsoleSender(), value)
                : p.performCommand(value);
        if (!success) return;
      } else if (!actions.execute(p, List.of(a))) return;
    }
    if (d.destination() != null && allowed(p, d)) teleport.teleport(p, d.destination());
  }

  private void preview(Player p) {
    var d = draft(p).value().value();
    if (!d.first().world().equals(p.getWorld().getUID())) return;
    particles(
        p,
        d,
        1,
        Math.min(200, plugin.getConfig().getInt("portal.particles.per-portal-budget", 64)));
  }

  private void render() {
    double range = plugin.getConfig().getDouble("portal.particles.view-distance", 32);
    int duration = plugin.getConfig().getInt("portal.particles.creation-ticks", 60);
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      if (!love.authenticated(viewer) || !plugin.allows("portal", viewer)) continue;
      int remaining = plugin.getConfig().getInt("portal.particles.per-player-budget", 256);
      for (var doc : portals.values()) {
        var d = doc.value();
        if (!d.enabled() || !d.first().world().equals(viewer.getWorld().getUID())) continue;
        var center =
            d.first()
                .location()
                .add(
                    (d.second().x() - d.first().x()) / 2,
                    (d.second().y() - d.first().y()) / 2,
                    (d.second().z() - d.first().z()) / 2);
        if (viewer.getLocation().distanceSquared(center) > range * range) continue;
        int budget =
            Math.min(
                remaining, plugin.getConfig().getInt("portal.particles.per-portal-budget", 64));
        if (budget <= 0) break;
        long age = ticks - created.getOrDefault(d.id(), ticks - duration);
        double progress = duration <= 0 ? 1 : Math.min(1, age / (double) duration);
        particles(viewer, d, progress, budget);
        remaining -= budget;
      }
    }
    created.entrySet().removeIf(e -> ticks - e.getValue() > duration);
  }

  private void particles(Player p, PortalDefinition d, double progress, int budget) {
    Particle particle = Particle.valueOf(d.particle());
    Object data =
        particle == Particle.DUST
            ? new Particle.DustOptions(Color.fromRGB(Integer.parseInt(d.color(), 16)), .8f)
            : null;
    for (var point : PortalGeometry.points(d, budget, progress, ticks))
      p.spawnParticle(
          particle,
          new Location(p.getWorld(), point.getX(), point.getY(), point.getZ()),
          1,
          .015,
          .015,
          .015,
          0,
          data);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)
        || e.getClick() != ClickType.LEFT
        || e.getRawSlot() < 0
        || e.getRawSlot() >= m.inventory.getSize()) return;
    var action = m.actions.get(e.getRawSlot());
    if (action != null)
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (!admin(p) || p.getOpenInventory().getTopInventory() != m.inventory) return;
                try {
                  MenuStyle.action(p, action);
                } catch (RuntimeException error) {
                  ui.error(p, error);
                }
              });
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    sessions.remove(id);
    selections.remove(id);
    inside.remove(id);
    pending.remove(id);
    cooldowns.remove(id);
    drafts.remove(id);
  }

  public void close() {
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    pending.clear();
    sessions.clear();
    selections.clear();
    drafts.clear();
    portals.clear();
    inside.clear();
    cooldowns.clear();
    created.clear();
  }
}
