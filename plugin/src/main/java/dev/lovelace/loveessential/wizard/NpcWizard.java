package dev.lovelace.loveessential.wizard;

import com.google.gson.Gson;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.npc.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class NpcWizard implements Listener, AutoCloseable {
  private final JavaPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final NpcService npcs;
  private final LoveBridge love;
  private final Gson gson = new Gson();
  private final ConcurrentHashMap<UUID, Working> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Prompt> prompts = new ConcurrentHashMap<>();
  private final Set<UUID> loading = new HashSet<>();
  private final Map<UUID, String> createIds = new HashMap<>();

  private static final class ConditionMenu implements InventoryHolder {
    Inventory inventory;
    final String field;

    ConditionMenu(String field) {
      this.field = field;
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  private static final class ActionMenu implements InventoryHolder {
    Inventory inventory;

    ActionMenu() {}

    public Inventory getInventory() {
      return inventory;
    }
  }

  private static final List<String> CONDITION_TYPES =
      List.of(
          "authenticated",
          "balance",
          "permission",
          "world",
          "reputation",
          "clan",
          "territory",
          "not-in-combat",
          "item",
          "time",
          "weather");
  private static final List<String> ACTION_TYPES =
      List.of(
          "message",
          "charge",
          "give",
          "actionbar",
          "sound",
          "reputation",
          "stat",
          "command",
          "lovecontracts:board",
          "lovecontracts:player-board",
          "lovecontracts:my",
          "loveshops:banker");
  private final NamespacedKey toolKey;

  private static final class Working {
    NpcDraft draft;
    long revision;
    boolean busy, recording;
    final Deque<Route> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    Working(NpcDraft d, long r) {
      draft = d;
      revision = r;
    }
  }

  private record Prompt(long expiry, BiConsumer<Player, String> consumer) {}

  private static final class Menu implements InventoryHolder {
    Inventory inventory;
    final int step;

    Menu(int step) {
      this.step = step;
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  private static final int LAST_STEP = 6;

  public NpcWizard(
      JavaPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      NpcService npcs,
      LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.npcs = npcs;
    this.love = love;
    toolKey = new NamespacedKey(plugin, "route-tool");
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  private boolean permitted(Player p) {
    if (!((LoveEssentialPlugin) plugin).module("npc")
        || !p.hasPermission("loveessential.npc.edit")
        || !love.authenticated(p)) {
      lang.send(p, "errors.permission");
      return false;
    }
    return true;
  }

  public void create(Player p, String id) {
    if (!p.hasPermission("loveessential.npc.create"))
      throw new IllegalArgumentException("errors.permission");
    if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("validation.id");
    if (sessions.containsKey(p.getUniqueId()))
      throw new IllegalArgumentException("wizard.existing-draft");
    createIds.put(p.getUniqueId(), id);
    open(p);
  }

  public void open(Player p) {
    if (!permitted(p)) return;
    var existing = sessions.get(p.getUniqueId());
    if (existing != null) {
      show(p);
      return;
    }
    if (!loading.add(p.getUniqueId())) return;
    store
        .get("draft", p.getUniqueId().toString(), NpcDraft.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      loading.remove(p.getUniqueId());
                      if (!p.isOnline()) return;
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      var w =
                          saved
                              .map(d -> new Working(d.value(), d.revision()))
                              .orElseGet(
                                  () ->
                                      new Working(
                                          NpcDraft.create(
                                              p.getUniqueId(), Position.of(p.getLocation())),
                                          0));
                      sessions.put(p.getUniqueId(), w);
                      String create = createIds.remove(p.getUniqueId());
                      if (create != null) {
                        if (saved.isPresent()) lang.send(p, "wizard.existing-draft");
                        else set(p, "id", create);
                      }
                      show(p);
                    }));
  }

  public void edit(Player p, String id) {
    edit(p, id, 0);
  }

  public void edit(Player p, String id, int page) {
    if (!permitted(p)) return;
    var n = npcs.get(id).orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
    var existing = sessions.get(p.getUniqueId());
    if (existing != null) {
      if (existing.draft.npc().id().equals(id)) {
        existing.draft = existing.draft.step(page);
        show(p);
        return;
      }
      throw new IllegalArgumentException("wizard.existing-draft");
    }
    if (!loading.add(p.getUniqueId())) return;
    store
        .get("draft", p.getUniqueId().toString(), NpcDraft.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      loading.remove(p.getUniqueId());
                      if (!p.isOnline()) return;
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      if (saved.isPresent() && !saved.get().value().npc().id().equals(id)) {
                        sessions.put(
                            p.getUniqueId(),
                            new Working(saved.get().value(), saved.get().revision()));
                        lang.send(p, "wizard.existing-draft");
                        show(p);
                        return;
                      }
                      sessions.put(
                          p.getUniqueId(),
                          saved
                              .map(d -> new Working(d.value(), d.revision()))
                              .orElseGet(() -> new Working(new NpcDraft(n, 0, Map.of()), 0)));
                      sessions.get(p.getUniqueId()).draft =
                          sessions.get(p.getUniqueId()).draft.step(page);
                      show(p);
                    }));
  }

  public void imported(Player p, NpcDraft draft) {
    if (!permitted(p)) return;
    if (sessions.containsKey(p.getUniqueId()))
      throw new IllegalArgumentException("wizard.existing-draft");
    if (!loading.add(p.getUniqueId())) return;
    store
        .get("draft", p.getUniqueId().toString(), NpcDraft.class)
        .whenComplete(
            (existing, error) ->
                main.execute(
                    () -> {
                      loading.remove(p.getUniqueId());
                      if (!p.isOnline() || !permitted(p)) return;
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      if (existing.isPresent()) {
                        lang.send(p, "wizard.existing-draft");
                        return;
                      }
                      sessions.put(p.getUniqueId(), new Working(draft, 0));
                      show(p);
                    }));
  }

  public void page(Player p, int step) {
    if (!sessions.containsKey(p.getUniqueId()) && loading.contains(p.getUniqueId())) return;
    var w = require(p);
    w.draft = w.draft.step(Math.max(0, Math.min(LAST_STEP, step)));
    show(p);
  }

  private static String token() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private Working require(Player p) {
    if (!permitted(p)) throw new IllegalArgumentException("errors.permission");
    var w = sessions.get(p.getUniqueId());
    if (w == null) throw new IllegalArgumentException("wizard.no-draft");
    if (w.busy) throw new IllegalArgumentException("wizard.busy");
    return w;
  }

  public void input(Player p, String value) {
    var prompt = prompts.remove(p.getUniqueId());
    if (prompt == null || prompt.expiry() < System.currentTimeMillis()) {
      lang.send(p, "wizard.no-prompt");
      return;
    }
    if (!permitted(p)) return;
    try {
      prompt.consumer().accept(p, value);
      if (sessions.containsKey(p.getUniqueId()) && !prompts.containsKey(p.getUniqueId())) show(p);
    } catch (RuntimeException e) {
      lang.send(p, lang.contains(e.getMessage()) ? e.getMessage() : "errors.input");
    }
  }

  private void ask(Player p, String key, BiConsumer<Player, String> consumer) {
    p.closeInventory();
    prompts.put(p.getUniqueId(), new Prompt(System.currentTimeMillis() + 180000, consumer));
    lang.send(p, key);
  }

  public void set(Player p, String key, String value) {
    var w = require(p);
    var n = w.draft.npc();
    switch (key) {
      case "id" -> {
        if (!value.matches("[a-z0-9_-]{1,48}") || n.revision() > 0)
          throw new IllegalArgumentException("validation.id");
        w.draft =
            w.draft.change(
                value,
                "npc.names." + value + "_" + token(),
                n.entityType(),
                n.spawn(),
                n.settings(),
                n.visibility(),
                n.access(),
                n.route(),
                n.dialog(),
                n.actions(),
                n.enabled());
        if (!lang.contains(w.draft.npc().nameKey()))
          w.draft = w.draft.text(w.draft.npc().nameKey(), value);
      }
      case "name" -> {
        if (n.id().isEmpty() || value.length() > 256)
          throw new IllegalArgumentException("validation.id");
        String textKey = "npc.names." + n.id() + "_" + token();
        w.draft =
            w.draft
                .change(
                    n.id(),
                    textKey,
                    n.entityType(),
                    n.spawn(),
                    n.settings(),
                    n.visibility(),
                    n.access(),
                    n.route(),
                    n.dialog(),
                    n.actions(),
                    n.enabled())
                .text(textKey, value);
      }
      case "type" -> {
        String type = value.toUpperCase(Locale.ROOT);
        if (!plugin.getConfig().getStringList("npc.allowed-types").contains(type))
          throw new IllegalArgumentException("validation.entity");
        w.draft =
            w.draft.change(
                n.id(),
                n.nameKey(),
                type,
                n.spawn(),
                n.settings(),
                n.visibility(),
                n.access(),
                n.route(),
                n.dialog(),
                n.actions(),
                n.enabled());
      }
      case "visibility", "access" -> {
        Condition c = gson.fromJson(value, Condition.class);
        if (c == null) throw new IllegalArgumentException("validation.condition");
        w.draft =
            w.draft.change(
                n.id(),
                n.nameKey(),
                n.entityType(),
                n.spawn(),
                n.settings(),
                key.equals("visibility") ? c : n.visibility(),
                key.equals("access") ? c : n.access(),
                n.route(),
                n.dialog(),
                n.actions(),
                n.enabled());
      }
      case "actions" -> {
        var type = new com.google.gson.reflect.TypeToken<List<Action>>() {}.getType();
        List<Action> a = gson.fromJson(value, type);
        w.draft =
            w.draft.change(
                n.id(),
                n.nameKey(),
                n.entityType(),
                n.spawn(),
                n.settings(),
                n.visibility(),
                n.access(),
                n.route(),
                n.dialog(),
                a,
                n.enabled());
      }
      case "enabled" -> {
        if (!Set.of("true", "false").contains(value))
          throw new IllegalArgumentException("errors.input");
        w.draft =
            w.draft.change(
                n.id(),
                n.nameKey(),
                n.entityType(),
                n.spawn(),
                n.settings(),
                n.visibility(),
                n.access(),
                n.route(),
                n.dialog(),
                n.actions(),
                Boolean.parseBoolean(value));
      }
      default -> {
        if (!NpcValidator.validOption(key, value))
          throw new IllegalArgumentException("validation.option");
        w.draft = w.draft.option(key, value);
      }
    }
    lang.send(p, "wizard.changed", Map.of("field", key));
  }

  public void spawnHere(Player p) {
    var w = require(p);
    if (!love.territorialAccess(p, p.getLocation()))
      throw new IllegalArgumentException("errors.territory");
    var n = w.draft.npc();
    w.draft =
        w.draft.change(
            n.id(),
            n.nameKey(),
            n.entityType(),
            Position.of(p.getLocation()),
            n.settings(),
            n.visibility(),
            n.access(),
            n.route(),
            n.dialog(),
            n.actions(),
            n.enabled());
  }

  public void template(Player p, String template) {
    var w = require(p);
    var base =
        Map.of(
            "look-at",
            "true",
            "invulnerable",
            "true",
            "collidable",
            "false",
            "require-auth",
            "true");
    for (var e : base.entrySet()) w.draft = w.draft.option(e.getKey(), e.getValue());
    switch (template) {
      case "guard" -> {
        w.draft = w.draft.option("main-hand", "IRON_SWORD").option("helmet", "IRON_HELMET");
      }
      case "guide" -> w.draft = w.draft.option("movement", "follow");
      case "merchant", "quest", "decoration" -> {}
      default -> throw new IllegalArgumentException("errors.input");
    }
    lang.send(p, "wizard.changed", Map.of("field", template));
  }

  public void save(Player p) {
    var w = require(p);
    w.busy = true;
    var snapshot = w.draft;
    store
        .save("draft", p.getUniqueId().toString(), w.revision, snapshot, p.getUniqueId().toString())
        .whenComplete(
            (rev, error) ->
                main.execute(
                    () -> {
                      w.busy = false;
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      w.revision = rev;
                      lang.send(p, "wizard.saved");
                    }));
  }

  public void discard(Player p) {
    var w = require(p);
    w.busy = true;
    CompletableFuture<?> result =
        w.revision == 0
            ? CompletableFuture.completedFuture(null)
            : store.delete(
                "draft", p.getUniqueId().toString(), w.revision, p.getUniqueId().toString());
    result.whenComplete(
        (v, error) ->
            main.execute(
                () -> {
                  w.busy = false;
                  if (error != null) {
                    lang.send(p, "errors.storage");
                    return;
                  }
                  sessions.remove(p.getUniqueId(), w);
                  prompts.remove(p.getUniqueId());
                  npcs.removePreview(p);
                  p.closeInventory();
                  lang.send(p, "wizard.discarded");
                }));
  }

  public void publish(Player p) {
    var w = require(p);
    if (!p.hasPermission("loveessential.npc.publish"))
      throw new IllegalArgumentException("errors.permission");
    var errors =
        npcs.validator(k -> lang.contains(k) || w.draft.texts().containsKey(k))
            .validate(w.draft.npc(), plugin.getConfig().getInt("npc.max-route-points", 128));
    if (!errors.isEmpty()) {
      errors.forEach(key -> lang.send(p, key));
      return;
    }
    if (!love.territorialAccess(p, w.draft.npc().spawn().location()))
      throw new IllegalArgumentException("errors.territory");
    w.busy = true;
    var snapshot = w.draft;
    String publication = p.getUniqueId() + ":" + UUID.randomUUID();
    store
        .prepare(publication, snapshot.texts())
        .thenCompose(
            v ->
                CompletableFuture.runAsync(
                    () -> {
                      try {
                        lang.putAll(snapshot.texts());
                      } catch (Exception e) {
                        throw new CompletionException(e);
                      }
                    },
                    pluginExecutor()))
        .thenCompose(
            v ->
                main.call(
                    () ->
                        npcs.save(
                            snapshot.npc(), snapshot.npc().revision(), p.getUniqueId().toString())))
        .thenCompose(Function.identity())
        .thenCompose(n -> store.complete(publication).thenApply(v -> n))
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      w.busy = false;
                      if (error != null) {
                        lang.send(
                            p,
                            root(error) instanceof SqliteStore.Conflict
                                ? "errors.conflict"
                                : "errors.storage");
                        return;
                      }
                      w.draft = w.draft.npc(saved);
                      npcs.removePreview(p);
                      lang.send(p, "wizard.published", Map.of("id", saved.id()));
                      save(p);
                    }));
  }

  private Executor pluginExecutor() {
    return ((dev.lovelace.loveessential.LoveEssentialPlugin) plugin).io();
  }

  private static Throwable root(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    return e;
  }

  public void preview(Player p) {
    var w = require(p);
    if (w.draft.npc().id().isEmpty()) throw new IllegalArgumentException("validation.id");
    npcs.preview(p, w.draft.npc(), w.draft.texts());
    lang.send(p, "wizard.preview");
  }

  private void routeChange(Working w, Route route) {
    w.undo.push(w.draft.npc().route());
    if (w.undo.size() > 30) w.undo.removeLast();
    w.redo.clear();
    w.draft = w.draft.route(route);
  }

  public void addPoint(Player p) {
    var w = require(p);
    if (!p.hasPermission("loveessential.npc.path"))
      throw new IllegalArgumentException("errors.permission");
    if (!love.territorialAccess(p, p.getLocation()))
      throw new IllegalArgumentException("errors.territory");
    var route = w.draft.npc().route();
    if (route.points().size() >= plugin.getConfig().getInt("npc.max-route-points", 128))
      throw new IllegalArgumentException("validation.points");
    var points = new ArrayList<>(route.points());
    points.add(
        new Route.Point(Position.of(p.getLocation()), 20, 1, .5, Condition.always(), List.of()));
    routeChange(w, new Route(route.mode(), points));
    lang.send(p, "wizard.point-added", Map.of("point", Integer.toString(points.size())));
  }

  public void path(Player p, String op, String[] args) {
    var w = require(p);
    if (!p.hasPermission("loveessential.npc.path"))
      throw new IllegalArgumentException("errors.permission");
    var route = w.draft.npc().route();
    var points = new ArrayList<>(route.points());
    switch (op) {
      case "add" -> addPoint(p);
      case "test" -> preview(p);
      case "pause", "resume", "step" -> npcs.controlPreview(p, op);
      case "mode" ->
          routeChange(w, new Route(Route.Mode.valueOf(args[0].toUpperCase(Locale.ROOT)), points));
      case "remove" -> {
        points.remove(Integer.parseInt(args[0]) - 1);
        routeChange(w, new Route(route.mode(), points));
      }
      case "move" -> {
        var point = points.remove(Integer.parseInt(args[0]) - 1);
        points.add(Integer.parseInt(args[1]) - 1, point);
        routeChange(w, new Route(route.mode(), points));
      }
      case "point" -> {
        int i = Integer.parseInt(args[0]) - 1;
        var old = points.get(i);
        String field = args[1], value = args[2];
        var type = new com.google.gson.reflect.TypeToken<List<Action>>() {}.getType();
        points.set(
            i,
            new Route.Point(
                field.equals("position") ? Position.of(p.getLocation()) : old.position(),
                field.equals("wait") ? Integer.parseInt(value) : old.waitTicks(),
                field.equals("speed") ? Double.parseDouble(value) : old.speed(),
                field.equals("radius") ? Double.parseDouble(value) : old.radius(),
                field.equals("condition") ? gson.fromJson(value, Condition.class) : old.condition(),
                field.equals("actions") ? gson.fromJson(value, type) : old.actions()));
        routeChange(w, new Route(route.mode(), points));
      }
      case "undo" -> {
        if (!w.undo.isEmpty()) {
          w.redo.push(route);
          w.draft = w.draft.route(w.undo.pop());
        }
      }
      case "redo" -> {
        if (!w.redo.isEmpty()) {
          w.undo.push(route);
          w.draft = w.draft.route(w.redo.pop());
        }
      }
      case "record" -> {
        w.recording = !w.recording;
        lang.send(p, "wizard.recording", Map.of("state", Boolean.toString(w.recording)));
      }
      case "tool" -> {
        var item = button(Material.BLAZE_ROD, "wizard.route-tool");
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        if (p.getInventory().firstEmpty() < 0)
          throw new IllegalArgumentException("errors.inventory");
        p.getInventory().addItem(item);
      }
      case "show" -> {
        for (var pt : points) {
          var at = pt.position().location();
          if (at.getWorld().equals(p.getWorld()) && at.distanceSquared(p.getLocation()) < 128 * 128)
            p.spawnParticle(Particle.HAPPY_VILLAGER, at, 3, .1, .1, .1, 0);
        }
      }
      default -> throw new IllegalArgumentException("errors.input");
    }
  }

  public void dialog(Player p, String op, String[] args) {
    var w = require(p);
    if (!p.hasPermission("loveessential.npc.dialog"))
      throw new IllegalArgumentException("errors.permission");
    var d = w.draft.npc().dialog();
    var nodes = new LinkedHashMap<>(d.nodes());
    String start = d.start();
    switch (op) {
      case "node" -> {
        String id = args[0];
        if (!id.matches("[a-z0-9_-]{1,48}") || w.draft.npc().id().isEmpty())
          throw new IllegalArgumentException("validation.id");
        String key = "dialogs." + w.draft.npc().id() + "." + id + "_" + token();
        nodes.put(
            id, new Dialog.Node(key, nodes.containsKey(id) ? nodes.get(id).answers() : List.of()));
        w.draft = w.draft.text(key, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        if (start.isEmpty()) start = id;
      }
      case "start" -> start = args[0];
      case "remove" -> nodes.remove(args[0]);
      case "answer" -> {
        String node = args[0], id = args[1], next = args[2].equals("end") ? "" : args[2];
        var old = Objects.requireNonNull(nodes.get(node));
        String key = "dialogs." + w.draft.npc().id() + "." + node + "_" + id + "_" + token();
        var answers = new ArrayList<>(old.answers());
        answers.removeIf(a -> a.id().equals(id));
        answers.add(new Dialog.Answer(id, key, next, Condition.always(), List.of()));
        nodes.put(node, new Dialog.Node(old.textKey(), answers));
        w.draft = w.draft.text(key, String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
      }
      case "answer-condition", "answer-actions" -> {
        var old = Objects.requireNonNull(nodes.get(args[0]));
        var out = new ArrayList<Dialog.Answer>();
        for (var a : old.answers()) {
          if (a.id().equals(args[1])) {
            var type = new com.google.gson.reflect.TypeToken<List<Action>>() {}.getType();
            a =
                new Dialog.Answer(
                    a.id(),
                    a.textKey(),
                    a.next(),
                    op.equals("answer-condition")
                        ? gson.fromJson(args[2], Condition.class)
                        : a.condition(),
                    op.equals("answer-actions") ? gson.fromJson(args[2], type) : a.actions());
          }
          out.add(a);
        }
        nodes.put(args[0], new Dialog.Node(old.textKey(), out));
      }
      default -> throw new IllegalArgumentException("errors.input");
    }
    w.draft = w.draft.dialog(new Dialog(start, nodes));
    lang.send(p, "wizard.changed", Map.of("field", "dialog"));
  }

  private void showConditions(Player p, String field) {
    require(p);
    var holder = new ConditionMenu(field);
    holder.inventory = Bukkit.createInventory(holder, 54, lang.render("wizard.condition-title"));
    for (int i = 0; i < CONDITION_TYPES.size(); i++)
      holder.inventory.setItem(
          10 + i, button(Material.PAPER, "wizard.conditions." + CONDITION_TYPES.get(i)));
    holder.inventory.setItem(45, button(Material.ARROW, "common.back"));
    holder.inventory.setItem(49, button(Material.BARRIER, "wizard.clear-conditions"));
    p.openInventory(holder.inventory);
  }

  private void buildCondition(Player p, String field, String type) {
    List<String> parameters =
        switch (type) {
          case "permission", "world", "clan", "weather" -> List.of("value");
          case "balance" -> List.of("amount");
          case "reputation" -> List.of("min");
          case "item" -> List.of("material", "amount");
          case "time" -> List.of("from", "to");
          default -> List.of();
        };
    collect(
        p,
        parameters,
        0,
        new LinkedHashMap<>(),
        values -> {
          var w = require(p);
          Condition leaf = new Condition(type, values, List.of());
          var n = w.draft.npc();
          Condition previous = field.equals("visibility") ? n.visibility() : n.access();
          Condition combined =
              previous.type().equals("all")
                  ? new Condition(
                      "all",
                      Map.of(),
                      java.util.stream.Stream.concat(
                              previous.children().stream(), java.util.stream.Stream.of(leaf))
                          .toList())
                  : new Condition("all", Map.of(), List.of(previous, leaf));
          set(p, field, gson.toJson(combined));
        });
  }

  private void collect(
      Player p,
      List<String> keys,
      int index,
      Map<String, String> values,
      Consumer<Map<String, String>> done) {
    if (index >= keys.size()) {
      done.accept(Map.copyOf(values));
      return;
    }
    String key = keys.get(index);
    ask(
        p,
        "wizard.parameters." + key,
        (who, v) -> {
          values.put(key, v);
          collect(who, keys, index + 1, values, done);
        });
  }

  private void showActions(Player p) {
    require(p);
    var holder = new ActionMenu();
    holder.inventory = Bukkit.createInventory(holder, 54, lang.render("wizard.action-title"));
    for (int i = 0; i < ACTION_TYPES.size(); i++)
      holder.inventory.setItem(
          10 + i,
          button(Material.PAPER, "wizard.action-types." + ACTION_TYPES.get(i).replace(':', '_')));
    holder.inventory.setItem(45, button(Material.ARROW, "common.back"));
    holder.inventory.setItem(49, button(Material.BARRIER, "wizard.clear-actions"));
    p.openInventory(holder.inventory);
  }

  private void buildAction(Player p, String type) {
    if (type.equals("message") || type.equals("actionbar")) {
      ask(
          p,
          "wizard.prompt-text",
          (who, text) -> {
            var w = require(who);
            String key =
                "actions."
                    + w.draft.npc().id()
                    + ".text_"
                    + UUID.randomUUID().toString().replace("-", "");
            w.draft = w.draft.text(key, text);
            appendAction(w, new Action(type, Map.of("key", key)));
          });
      return;
    }
    List<String> parameters =
        switch (type) {
          case "charge", "give" -> List.of("amount");
          case "sound" -> List.of("sound");
          case "reputation" -> List.of("delta");
          case "stat" -> List.of("metric", "amount");
          case "command" -> List.of("value");
          default -> List.of();
        };
    collect(
        p,
        parameters,
        0,
        new LinkedHashMap<>(),
        values -> appendAction(require(p), new Action(type, values)));
  }

  private void appendAction(Working w, Action action) {
    var n = w.draft.npc();
    var list = new ArrayList<>(n.actions());
    list.add(action);
    w.draft =
        w.draft.change(
            n.id(),
            n.nameKey(),
            n.entityType(),
            n.spawn(),
            n.settings(),
            n.visibility(),
            n.access(),
            n.route(),
            n.dialog(),
            list,
            n.enabled());
  }

  private void show(Player p) {
    var w = sessions.get(p.getUniqueId());
    if (w == null || !p.isOnline()) return;
    int step = w.draft.step();
    var holder = new Menu(step);
    var inv =
        Bukkit.createInventory(
            holder,
            54,
            lang.render(
                "wizard.title",
                Map.of(
                    "step", Integer.toString(step + 1), "total", Integer.toString(LAST_STEP + 1))));
    holder.inventory = inv;
    inv.setItem(45, button(Material.ARROW, "common.back"));
    inv.setItem(53, button(Material.ARROW, "common.next"));
    inv.setItem(47, button(Material.WRITABLE_BOOK, "common.save-draft"));
    inv.setItem(48, button(Material.BARRIER, "common.discard"));
    inv.setItem(49, button(Material.ENDER_EYE, "common.preview"));
    inv.setItem(51, button(Material.LIME_CONCRETE, "common.publish"));
    String[][] keys = {
      {"template", "id", "name", "type", "spawn"},
      {
        "skin",
        "helmet",
        "chestplate",
        "leggings",
        "boots",
        "main-hand",
        "off-hand",
        "pose",
        "scale",
        "baby",
        "glowing",
        "name-visible",
        "silent"
      },
      {
        "personal",
        "visibility",
        "access",
        "require-auth",
        "interaction-range",
        "cooldown-ms",
        "interaction-mode",
        "line-of-sight",
        "interaction-sound",
        "interaction-volume",
        "interaction-pitch"
      },
      {
        "movement",
        "speed",
        "look-at",
        "look-range",
        "pause-on-interact",
        "stuck-policy",
        "activation-range"
      },
      {
        "point-add",
        "point-mode",
        "point-record",
        "point-tool",
        "point-show",
        "point-edit",
        "point-undo",
        "point-redo"
      },
      {
        "dialog-node",
        "dialog-answer",
        "dialog-start",
        "dialog-condition",
        "dialog-actions",
        "actions"
      },
      {
        "enabled-hours",
        "hour-from",
        "hour-to",
        "enabled",
        "collidable",
        "gravity",
        "invulnerable",
        "avoid-water",
        "can-open-doors",
        "teleport-on-stuck"
      }
    };
    for (int i = 0; i < keys[step].length; i++) {
      String key = keys[step][i];
      var item = button(Material.PAPER, "wizard.fields." + key);
      var meta = item.getItemMeta();
      String value =
          w.draft
              .npc()
              .settings()
              .getOrDefault(
                  key,
                  switch (key) {
                    case "id" -> w.draft.npc().id();
                    case "name" ->
                        w.draft
                            .texts()
                            .getOrDefault(
                                w.draft.npc().nameKey(), lang.text(w.draft.npc().nameKey()));
                    case "type" -> w.draft.npc().entityType();
                    default -> "";
                  });
      meta.lore(List.of(lang.render("wizard.value", Map.of("value", value))));
      item.setItemMeta(meta);
      inv.setItem(10 + i, item);
    }
    p.openInventory(inv);
  }

  private ItemStack button(Material material, String key) {
    var item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.displayName(lang.render(key));
    item.setItemMeta(meta);
    return item;
  }

  @EventHandler
  public void conditionClick(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof ConditionMenu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)) return;
    try {
      if (e.getRawSlot() == 45) {
        show(p);
        return;
      }
      if (e.getRawSlot() == 49) {
        set(p, m.field, gson.toJson(Condition.always()));
        show(p);
        return;
      }
      int i = e.getRawSlot() - 10;
      if (i >= 0 && i < CONDITION_TYPES.size()) {
        buildCondition(p, m.field, CONDITION_TYPES.get(i));
        if (!prompts.containsKey(p.getUniqueId())) show(p);
      }
    } catch (RuntimeException ex) {
      lang.send(p, "errors.input");
    }
  }

  @EventHandler
  public void actionClick(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof ActionMenu)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)) return;
    try {
      if (e.getRawSlot() == 45) {
        show(p);
        return;
      }
      if (e.getRawSlot() == 49) {
        set(p, "actions", "[]");
        show(p);
        return;
      }
      int i = e.getRawSlot() - 10;
      if (i >= 0 && i < ACTION_TYPES.size()) {
        buildAction(p, ACTION_TYPES.get(i));
        if (!prompts.containsKey(p.getUniqueId())) show(p);
      }
    } catch (RuntimeException ex) {
      lang.send(p, "errors.input");
    }
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() < 0 || e.getRawSlot() >= 54)
      return;
    try {
      int slot = e.getRawSlot();
      if (slot == 45) {
        page(p, menu.step - 1);
        return;
      }
      if (slot == 53) {
        page(p, menu.step + 1);
        return;
      }
      if (slot == 47) {
        save(p);
        return;
      }
      if (slot == 48) {
        ask(
            p,
            "wizard.confirm-discard",
            (who, v) -> {
              if (v.equalsIgnoreCase("DELETE")) discard(who);
            });
        return;
      }
      if (slot == 49) {
        preview(p);
        return;
      }
      if (slot == 51) {
        publish(p);
        return;
      }
      String[][] keys = {
        {"template", "id", "name", "type", "spawn"},
        {
          "skin",
          "helmet",
          "chestplate",
          "leggings",
          "boots",
          "main-hand",
          "off-hand",
          "pose",
          "scale",
          "baby",
          "glowing",
          "name-visible",
          "silent"
        },
        {
          "personal",
          "visibility",
          "access",
          "require-auth",
          "interaction-range",
          "cooldown-ms",
          "interaction-mode",
          "line-of-sight",
          "interaction-sound",
          "interaction-volume",
          "interaction-pitch"
        },
        {
          "movement",
          "speed",
          "look-at",
          "look-range",
          "pause-on-interact",
          "stuck-policy",
          "activation-range"
        },
        {
          "point-add",
          "point-mode",
          "point-record",
          "point-tool",
          "point-show",
          "point-edit",
          "point-undo",
          "point-redo"
        },
        {
          "dialog-node",
          "dialog-answer",
          "dialog-start",
          "dialog-condition",
          "dialog-actions",
          "actions"
        },
        {
          "enabled-hours",
          "hour-from",
          "hour-to",
          "enabled",
          "collidable",
          "gravity",
          "invulnerable",
          "avoid-water",
          "can-open-doors",
          "teleport-on-stuck"
        }
      };
      int index = slot - 10;
      if (index < 0 || index >= keys[menu.step].length) return;
      String key = keys[menu.step][index];
      switch (key) {
        case "spawn" -> {
          spawnHere(p);
          show(p);
        }
        case "visibility", "access" -> showConditions(p, key);
        case "actions" -> showActions(p);
        case "point-add" -> {
          addPoint(p);
          show(p);
        }
        case "point-record", "point-tool", "point-show", "point-undo", "point-redo" ->
            path(p, key.substring(6), new String[0]);
        case "point-mode" ->
            ask(p, "wizard.prompt-mode", (who, v) -> path(who, "mode", new String[] {v}));
        case "point-edit" ->
            ask(p, "wizard.prompt-point", (who, v) -> path(who, "point", v.split(" ", 3)));
        case "dialog-node" ->
            ask(p, "wizard.prompt-node", (who, v) -> dialog(who, "node", v.split(" ", 2)));
        case "dialog-answer" ->
            ask(p, "wizard.prompt-answer", (who, v) -> dialog(who, "answer", v.split(" ", 4)));
        case "dialog-start" ->
            ask(p, "wizard.prompt-start", (who, v) -> dialog(who, "start", new String[] {v}));
        case "dialog-condition" ->
            ask(
                p,
                "wizard.prompt-answer-condition",
                (who, v) -> dialog(who, "answer-condition", v.split(" ", 3)));
        case "dialog-actions" ->
            ask(
                p,
                "wizard.prompt-answer-actions",
                (who, v) -> dialog(who, "answer-actions", v.split(" ", 3)));
        case "template" -> ask(p, "wizard.prompt-template", this::template);
        default -> ask(p, "wizard.prompt-value", (who, v) -> set(who, key, v));
      }
    } catch (RuntimeException ex) {
      lang.send(p, lang.contains(ex.getMessage()) ? ex.getMessage() : "errors.input");
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu
        || e.getView().getTopInventory().getHolder() instanceof ConditionMenu
        || e.getView().getTopInventory().getHolder() instanceof ActionMenu) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void chat(AsyncChatEvent e) {
    var id = e.getPlayer().getUniqueId();
    var prompt = prompts.get(id);
    if (prompt == null) return;
    if (prompt.expiry() < System.currentTimeMillis()) {
      prompts.remove(id, prompt);
      return;
    }
    e.setCancelled(true);
    String input = PlainTextComponentSerializer.plainText().serialize(e.message());
    main.execute(() -> input(e.getPlayer(), input));
  }

  @EventHandler
  public void tool(PlayerInteractEvent e) {
    var item = e.getItem();
    if (item == null
        || !item.hasItemMeta()
        || !item.getItemMeta().getPersistentDataContainer().has(toolKey)) return;
    e.setCancelled(true);
    if (e.getHand() != EquipmentSlot.HAND) return;
    try {
      addPoint(e.getPlayer());
    } catch (RuntimeException ex) {
      lang.send(e.getPlayer(), "wizard.no-draft");
    }
  }

  @EventHandler
  public void move(PlayerMoveEvent e) {
    var w = sessions.get(e.getPlayer().getUniqueId());
    if (w == null || !w.recording || w.busy || e.getTo() == null) return;
    var points = w.draft.npc().route().points();
    if (points.isEmpty()
        || points.getLast().position().world().equals(e.getTo().getWorld().getUID())
            && points.getLast().position().location().distanceSquared(e.getTo()) >= 16) {
      try {
        addPoint(e.getPlayer());
      } catch (RuntimeException ex) {
        w.recording = false;
        lang.send(e.getPlayer(), "validation.points");
      }
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    var id = e.getPlayer().getUniqueId();
    prompts.remove(id);
    createIds.remove(id);
    loading.remove(id);
    var w = sessions.remove(id);
    if (w != null && !w.busy)
      store
          .save("draft", id.toString(), w.revision, w.draft, id.toString())
          .exceptionally(
              error -> {
                plugin.getLogger().warning(lang.text("logs.draft-save-failed"));
                return null;
              });
  }

  public void close() {
    HandlerList.unregisterAll(this);
    prompts.clear();
    sessions.forEach(
        (id, w) -> {
          if (!w.busy) store.save("draft", id.toString(), w.revision, w.draft, id.toString());
        });
    sessions.clear();
    createIds.clear();
    loading.clear();
  }
}
