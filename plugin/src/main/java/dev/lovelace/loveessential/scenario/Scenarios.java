package dev.lovelace.loveessential.scenario;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.condition.*;
import dev.lovelace.loveessential.content.ContentLibrary;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/**
 * Declarative event rules; durable cursors are reserved before side effects (no automatic replay).
 */
public final class Scenarios implements Listener, AutoCloseable {
  public enum Trigger {
    FIRST_JOIN,
    JOIN,
    AUTH,
    WORLD_ENTER,
    RESPAWN,
    MANUAL
  }

  public record Step(long delayTicks, Condition condition, Action action) {}

  public record Rule(
      String id,
      boolean enabled,
      Trigger trigger,
      boolean once,
      String world,
      Condition condition,
      List<Step> steps) {
    public Rule {
      steps = List.copyOf(steps);
    }
  }

  public record Run(long definitionRevision, int cursor, long dueAt, String state) {}

  private record Active(UUID session, SqliteStore.Document<Run> run) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final ConditionEngine conditions;
  private final ActionEngine actions;
  private final NativeDialogs ui;
  private final ContentLibrary texts;
  private final Map<String, SqliteStore.Document<Rule>> rules = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> sessions = new HashMap<>();
  private final Map<UUID, Boolean> joinedDuringLoad = new HashMap<>();
  private final Map<String, Active> active = new HashMap<>();
  private final com.github.benmanes.caffeine.cache.Cache<
          UUID, java.util.concurrent.atomic.AtomicInteger>
      starts =
          com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
              .maximumSize(4096)
              .expireAfterWrite(java.time.Duration.ofMinutes(1))
              .build();
  private final Set<String> loading = new HashSet<>();
  private final Map<String, org.bukkit.scheduler.BukkitTask> tasks = new HashMap<>();

  public Scenarios(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      ConditionEngine conditions,
      ActionEngine actions,
      NativeDialogs ui,
      ContentLibrary texts) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.conditions = conditions;
    this.actions = actions;
    this.ui = ui;
    this.texts = texts;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public CompletableFuture<Void> load() {
    return store
        .list("scenario", Rule.class)
        .thenAccept(saved -> saved.forEach(d -> rules.put(d.id(), d)));
  }

  public List<String> ids() {
    return rules.keySet().stream().sorted().toList();
  }

  public Optional<SqliteStore.Document<Rule>> rule(String id) {
    return Optional.ofNullable(rules.get(id));
  }

  private boolean admin(Player p) {
    return plugin.module("scenarios") && p.hasPermission("loveessential.scenarios.admin");
  }

  public static void validate(Rule r, int max) {
    ContentLibrary.id(r.id());
    if (r.steps().size() > max) throw new IllegalArgumentException("scenarios.limit");
    for (var s : r.steps())
      if (s.delayTicks() < 0
          || s.delayTicks() > 72000
          || s.action() == null
          || s.condition() == null) throw new IllegalArgumentException("errors.input");
  }

  public CompletableFuture<Void> save(
      Rule rule, long revision, String actor, Map<String, String> values) {
    validate(rule, plugin.getConfig().getInt("scenarios.max-steps", 32));
    if (revision == 0 && rules.size() >= plugin.getConfig().getInt("scenarios.max-rules", 256))
      throw new IllegalArgumentException("scenarios.limit");
    for (var step : rule.steps())
      if (!actions.known(step.action().type())) throw new IllegalArgumentException("errors.input");
    return texts
        .texts(values)
        .thenCompose(v -> store.save("scenario", rule.id(), revision, rule, actor))
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      rules.put(rule.id(), new SqliteStore.Document<>(rule.id(), rev, rule));
                      return null;
                    }));
  }

  private boolean allowed(Player p, Rule rule) {
    return plugin.ready()
        && plugin.allows("scenarios", p)
        && p.isOnline()
        && love.authenticated(p)
        && rule.enabled()
        && (rule.world().isBlank() || p.getWorld().getName().equals(rule.world()))
        && conditions.test(p, rule.condition()) == LoveEssentialService.Verdict.ALLOW;
  }

  private String key(Player p, String id) {
    return p.getUniqueId() + "/" + id;
  }

  public void trigger(Player p, Trigger event) {
    if (!plugin.ready() || !plugin.module("scenarios")) return;
    sessions.putIfAbsent(p.getUniqueId(), UUID.randomUUID());
    for (var d : rules.values())
      if (d.value().enabled() && d.value().trigger() == event) enroll(p, d);
  }

  private void enroll(Player p, SqliteStore.Document<Rule> definition) {
    String key = key(p, definition.id());
    UUID session = sessions.computeIfAbsent(p.getUniqueId(), id -> UUID.randomUUID());
    if (active.containsKey(key) || loading.contains(key)) return;
    int count =
        starts
            .get(p.getUniqueId(), id -> new java.util.concurrent.atomic.AtomicInteger())
            .incrementAndGet();
    int limit = plugin.getConfig().getInt("scenarios.max-starts-per-minute", 60);
    if (count > limit) {
      if (count == limit + 1) lang.send(p, "scenarios.rate-limit");
      return;
    }
    loading.add(key);
    store
        .get("scenario-run", key, Run.class)
        .thenCompose(
            old -> {
              if (old.isPresent()
                  && (definition.value().once() || old.get().value().state().equals("PENDING")))
                return CompletableFuture.completedFuture(old.get());
              var run = new Run(definition.revision(), 0, 0, "PENDING");
              return store
                  .save(
                      "scenario-run",
                      key,
                      old.map(SqliteStore.Document::revision).orElse(0L),
                      run,
                      "event")
                  .thenApply(rev -> new SqliteStore.Document<>(key, rev, run));
            })
        .whenComplete(
            (run, error) ->
                main.execute(
                    () -> {
                      loading.remove(key);
                      if (error != null) {
                        ui.error(p, error);
                        return;
                      }
                      if (!Objects.equals(sessions.get(p.getUniqueId()), session)
                          || !run.value().state().equals("PENDING")) return;
                      active.put(key, new Active(session, run));
                      resume(p, key);
                    }));
  }

  public void resumePlayer(Player p) {
    if (!plugin.ready() || !plugin.module("scenarios")) return;
    UUID session = sessions.computeIfAbsent(p.getUniqueId(), id -> UUID.randomUUID());
    store
        .listPrefix("scenario-run", p.getUniqueId() + "/", Run.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      if (error != null || !Objects.equals(sessions.get(p.getUniqueId()), session))
                        return;
                      for (var d : saved)
                        if (d.value().state().equals("PENDING")) {
                          active.putIfAbsent(d.id(), new Active(session, d));
                          resume(p, d.id());
                        }
                    }));
  }

  private boolean valid(Player p, String key, Active a) {
    return active.get(key) == a
        && Objects.equals(sessions.get(p.getUniqueId()), a.session())
        && p.isOnline();
  }

  private void resume(Player p, String key) {
    var a = active.get(key);
    if (a == null || !valid(p, key, a) || tasks.containsKey(key)) return;
    String id = key.substring(key.indexOf('/') + 1);
    var d = rules.get(id);
    if (d == null || d.revision() != a.run().value().definitionRevision()) {
      finish(p, key, a, "STALE");
      return;
    }
    if (!allowed(p, d.value())) return;
    var run = a.run().value();
    if (run.cursor() >= d.value().steps().size()) {
      finish(p, key, a, "DONE");
      return;
    }
    var step = d.value().steps().get(run.cursor());
    if (run.dueAt() == 0) {
      persist(
          p,
          key,
          a,
          new Run(
              run.definitionRevision(),
              run.cursor(),
              System.currentTimeMillis() + step.delayTicks() * 50,
              "PENDING"),
          next -> resume(p, key));
      return;
    }
    long ticks = Math.max(1, (run.dueAt() - System.currentTimeMillis() + 49) / 50);
    tasks.put(
        key,
        Bukkit.getScheduler()
            .runTaskLater(
                plugin,
                () -> {
                  tasks.remove(key);
                  if (!valid(p, key, a) || !allowed(p, d.value())) return;
                  if (rules.get(d.id()) != d) {
                    finish(p, key, a, "STALE");
                    return;
                  }
                  boolean execute =
                      conditions.test(p, step.condition()) == LoveEssentialService.Verdict.ALLOW;
                  // The cursor is durable before the Bukkit action; crashes may skip, but cannot
                  // replay a charge/reward.
                  persist(
                      p,
                      key,
                      a,
                      new Run(run.definitionRevision(), run.cursor() + 1, 0, "PENDING"),
                      next -> {
                        if (rules.get(d.id()) != d) {
                          finish(p, key, next, "STALE");
                          return;
                        }
                        if (execute
                            && !actions.execute(
                                p, step.action() == null ? List.of() : List.of(step.action())))
                          finish(p, key, next, "FAILED");
                        else resume(p, key);
                      });
                },
                ticks));
  }

  private void persist(
      Player p, String key, Active old, Run value, java.util.function.Consumer<Active> next) {
    if (loading.contains(key)) return;
    loading.add(key);
    store
        .save("scenario-run", key, old.run().revision(), value, "scenario")
        .whenComplete(
            (rev, error) ->
                main.execute(
                    () -> {
                      loading.remove(key);
                      if (!valid(p, key, old)) return;
                      if (error != null) {
                        active.remove(key);
                        ui.error(p, error);
                        return;
                      }
                      var updated =
                          new Active(old.session(), new SqliteStore.Document<>(key, rev, value));
                      active.put(key, updated);
                      next.accept(updated);
                    }));
  }

  private void finish(Player p, String key, Active a, String state) {
    persist(
        p,
        key,
        a,
        new Run(a.run().value().definitionRevision(), a.run().value().cursor(), 0, state),
        next -> active.remove(key, next));
  }

  public void start() {
    for (var e : joinedDuringLoad.entrySet()) {
      var p = Bukkit.getPlayer(e.getKey());
      if (p != null) {
        if (e.getValue()) trigger(p, Trigger.FIRST_JOIN);
        trigger(p, Trigger.JOIN);
        resumePlayer(p);
      }
    }
    joinedDuringLoad.clear();
  }

  public void tick() {
    if (!plugin.ready() || !plugin.module("scenarios")) return;
    for (var e : List.copyOf(active.entrySet())) {
      var p = Bukkit.getPlayer(UUID.fromString(e.getKey().substring(0, e.getKey().indexOf('/'))));
      if (p != null) resume(p, e.getKey());
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void join(PlayerJoinEvent e) {
    sessions.putIfAbsent(e.getPlayer().getUniqueId(), UUID.randomUUID());
    if (!plugin.ready()) {
      joinedDuringLoad.put(e.getPlayer().getUniqueId(), !e.getPlayer().hasPlayedBefore());
      return;
    }
    if (!e.getPlayer().hasPlayedBefore()) trigger(e.getPlayer(), Trigger.FIRST_JOIN);
    trigger(e.getPlayer(), Trigger.JOIN);
    resumePlayer(e.getPlayer());
  }

  @EventHandler
  public void auth(dev.lovelace.lovecore.api.auth.PlayerAuthenticatedEvent e) {
    main.execute(
        () -> {
          trigger(e.player(), Trigger.AUTH);
          resumePlayer(e.player());
        });
  }

  @EventHandler
  public void world(PlayerChangedWorldEvent e) {
    trigger(e.getPlayer(), Trigger.WORLD_ENTER);
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent e) {
    Bukkit.getScheduler().runTask(plugin, () -> trigger(e.getPlayer(), Trigger.RESPAWN));
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    sessions.remove(id);
    for (String key : List.copyOf(active.keySet()))
      if (key.startsWith(id + "/")) {
        active.remove(key);
        var task = tasks.remove(key);
        if (task != null) task.cancel();
      }
  }

  public void command(Player p, String[] args) {
    if (!admin(p)) throw new IllegalArgumentException("errors.permission");
    if (args.length == 1 && args[0].equals("create"))
      args =
          new String[] {
            "create", "scenario_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
          };
    if (args.length == 0 || args[0].equals("list")) {
      menu(p, 0);
      return;
    }
    if (args.length != 2) throw new IllegalArgumentException("scenarios.usage");
    String id = args[1];
    ContentLibrary.id(id);
    switch (args[0]) {
      case "create" -> {
        if (rules.containsKey(id)) throw new IllegalArgumentException("errors.conflict");
        done(
            p,
            save(
                new Rule(id, false, Trigger.FIRST_JOIN, true, "", Condition.always(), List.of()),
                0,
                p.getUniqueId().toString(),
                Map.of()),
            () -> edit(p, id));
      }
      case "edit" -> edit(p, id);
      case "run" -> {
        var d = rules.get(id);
        if (d == null) throw new IllegalArgumentException("errors.not-found");
        if (!d.value().enabled()) throw new IllegalArgumentException("scenarios.disabled");
        enroll(p, d);
      }
      case "reset" -> {
        String key = key(p, id);
        if (loading.contains(key)) throw new IllegalArgumentException("wizard.busy");
        ui.confirm(
            p,
            "scenarios.reset-confirm",
            () -> {
              if (loading.contains(key)) throw new IllegalArgumentException("wizard.busy");
              active.remove(key);
              var task = tasks.remove(key);
              if (task != null) task.cancel();
              done(
                  p,
                  store
                      .get("scenario-run", key, Run.class)
                      .thenCompose(
                          old ->
                              old.isEmpty()
                                  ? CompletableFuture.completedFuture(null)
                                  : store.delete(
                                      "scenario-run",
                                      key,
                                      old.get().revision(),
                                      p.getUniqueId().toString())),
                  () -> edit(p, id));
            },
            () -> edit(p, id),
            this::admin);
      }
      case "delete" -> {
        var d = rules.get(id);
        if (d == null) throw new IllegalArgumentException("errors.not-found");
        ui.confirm(
            p,
            "scenarios.delete-confirm",
            () ->
                done(
                    p,
                    store
                        .delete("scenario", id, d.revision(), p.getUniqueId().toString())
                        .thenCompose(
                            v ->
                                main.call(
                                    () -> {
                                      rules.remove(id);
                                      return null;
                                    })),
                    () -> menu(p, 0)),
            () -> edit(p, id),
            this::admin);
      }
      default -> throw new IllegalArgumentException("scenarios.usage");
    }
  }

  public List<String> suggestions(Player p, String[] args) {
    if (!admin(p)) return List.of();
    if (args.length == 1) return List.of("list", "create", "edit", "run", "reset", "delete");
    if (args.length == 2 && !args[0].equals("create")) return ids();
    return List.of();
  }

  private void done(Player p, CompletableFuture<?> f, Runnable after) {
    f.whenComplete(
        (v, e) ->
            main.execute(
                () -> {
                  if (!p.isOnline()) return;
                  if (e != null) ui.error(p, e);
                  else {
                    lang.send(p, "chat.saved");
                    if (admin(p)) after.run();
                  }
                }));
  }

  public void menu(Player p, int page) {
    var ids = ids();
    int start = Math.max(0, page) * 16;
    var buttons = new ArrayList<NativeDialogs.Button>();
    for (String id : ids.subList(Math.min(start, ids.size()), Math.min(start + 16, ids.size())))
      buttons.add(
          new NativeDialogs.Button(
              id, lang.render("scenarios.entry", Map.of("id", id)), v -> edit(p, id)));
    buttons.add(ui.button("create", "content.create", v -> command(p, new String[] {"create"})));
    if (page > 0) buttons.add(ui.button("prev", "gui.previous", v -> menu(p, page - 1)));
    if (start + 16 < ids.size())
      buttons.add(ui.button("next", "common.next", v -> menu(p, page + 1)));
    ui.show(p, "scenarios.title", "scenarios.help", List.of(), buttons, this::admin);
  }

  public void edit(Player p, String id) {
    var d = rules.get(id);
    if (d == null) throw new IllegalArgumentException("errors.not-found");
    var r = d.value();
    ui.show(
        p,
        "scenarios.edit",
        "scenarios.edit-help",
        List.of(
            ui.select("enabled", "scenarios.enabled", "" + r.enabled(), List.of("true", "false")),
            ui.select(
                "trigger",
                "scenarios.trigger",
                r.trigger().name(),
                Arrays.stream(Trigger.values()).map(Enum::name).toList()),
            ui.select("once", "scenarios.once", "" + r.once(), List.of("true", "false")),
            ui.text("world", "worlds.name", r.world(), 48),
            ui.text(
                "permission",
                "worlds.permission",
                r.condition().type().equals("permission")
                    ? r.condition().args().getOrDefault("value", "")
                    : "",
                128)),
        List.of(
            ui.button(
                "save",
                "content.save",
                v ->
                    done(
                        p,
                        save(
                            new Rule(
                                id,
                                Boolean.parseBoolean(v.get("enabled")),
                                Trigger.valueOf(v.get("trigger")),
                                Boolean.parseBoolean(v.get("once")),
                                v.get("world"),
                                v.get("permission").isBlank()
                                    ? Condition.always()
                                    : new Condition(
                                        "permission",
                                        Map.of("value", v.get("permission")),
                                        List.of()),
                                r.steps()),
                            d.revision(),
                            p.getUniqueId().toString(),
                            Map.of()),
                        () -> edit(p, id))),
            ui.button("steps", "scenarios.steps", v -> steps(p, d, 0)),
            ui.button("run", "scenarios.run", v -> command(p, new String[] {"run", id})),
            ui.button("reset", "scenarios.reset", v -> command(p, new String[] {"reset", id})),
            ui.button("delete", "ui.delete", v -> command(p, new String[] {"delete", id})),
            ui.button("back", "common.back", v -> menu(p, 0))),
        this::admin);
  }

  private void steps(Player p, SqliteStore.Document<Rule> d, int page) {
    var list = d.value().steps();
    int start = page * 16;
    var buttons = new ArrayList<NativeDialogs.Button>();
    for (int i = start; i < Math.min(start + 16, list.size()); i++) {
      int index = i;
      buttons.add(
          new NativeDialogs.Button(
              "step" + i,
              lang.render(
                  "scenarios.step-entry",
                  Map.of("index", "" + (i + 1), "type", list.get(i).action().type())),
              v -> step(p, d, index)));
    }
    buttons.add(ui.button("add", "scenarios.add-step", v -> step(p, d, -1)));
    if (page > 0) buttons.add(ui.button("prev", "gui.previous", v -> steps(p, d, page - 1)));
    if (start + 16 < list.size())
      buttons.add(ui.button("next", "common.next", v -> steps(p, d, page + 1)));
    buttons.add(ui.button("back", "common.back", v -> edit(p, d.id())));
    ui.show(p, "scenarios.steps", "scenarios.steps-help", List.of(), buttons, this::admin);
  }

  private void step(Player p, SqliteStore.Document<Rule> d, int index) {
    var r = d.value();
    var old =
        index < 0
            ? new Step(0, Condition.always(), new Action("message", Map.of("key", "")))
            : r.steps().get(index);
    String type = old.action().type();
    String argument = old.action().args().getOrDefault(argumentKey(type), "");
    if (Set.of("message", "actionbar").contains(type) && lang.contains(argument))
      argument = lang.text(argument);
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              var values = new HashMap<String, String>();
              String arg = v.get("argument"), t = v.get("type");
              if (Set.of("message", "actionbar").contains(t)) {
                String k = "scenario-text." + r.id() + "." + UUID.randomUUID();
                values.put(k, arg);
                arg = k;
              }
              var a =
                  new Action(
                      t,
                      Set.of("starter", "recipes").contains(t)
                          ? Map.of()
                          : Map.of(argumentKey(t), arg));
              var list = new ArrayList<>(r.steps());
              var s = new Step(Long.parseLong(v.get("delay")), Condition.always(), a);
              if (index < 0) list.add(s);
              else list.set(index, s);
              done(
                  p,
                  save(
                      new Rule(
                          r.id(),
                          r.enabled(),
                          r.trigger(),
                          r.once(),
                          r.world(),
                          r.condition(),
                          list),
                      d.revision(),
                      p.getUniqueId().toString(),
                      values),
                  () -> edit(p, r.id()));
            }));
    if (index >= 0) {
      buttons.add(
          ui.button(
              "remove",
              "scenarios.remove-step",
              v -> {
                var list = new ArrayList<>(r.steps());
                list.remove(index);
                done(
                    p,
                    save(
                        new Rule(
                            r.id(),
                            r.enabled(),
                            r.trigger(),
                            r.once(),
                            r.world(),
                            r.condition(),
                            list),
                        d.revision(),
                        p.getUniqueId().toString(),
                        Map.of()),
                    () -> edit(p, r.id()));
              }));
      if (index > 0)
        buttons.add(
            ui.button(
                "up",
                "scenarios.move-up",
                v -> {
                  var list = new ArrayList<>(r.steps());
                  Collections.swap(list, index, index - 1);
                  done(
                      p,
                      save(
                          new Rule(
                              r.id(),
                              r.enabled(),
                              r.trigger(),
                              r.once(),
                              r.world(),
                              r.condition(),
                              list),
                          d.revision(),
                          p.getUniqueId().toString(),
                          Map.of()),
                      () -> edit(p, r.id()));
                }));
    }
    buttons.add(ui.button("back", "common.back", v -> steps(p, d, 0)));
    ui.show(
        p,
        "scenarios.step",
        "scenarios.step-help",
        List.of(
            ui.select(
                "type",
                "scenarios.action",
                type,
                List.of(
                    "message",
                    "actionbar",
                    "sound",
                    "command",
                    "dialog",
                    "world",
                    "starter",
                    "recipes",
                    "charge",
                    "give")),
            ui.text("argument", "scenarios.argument", argument, 2048),
            ui.text("delay", "scenarios.delay", "" + old.delayTicks(), 6)),
        buttons,
        this::admin);
  }

  private static String argumentKey(String type) {
    return switch (type) {
      case "message", "actionbar" -> "key";
      case "dialog", "world" -> "id";
      case "charge", "give" -> "amount";
      case "sound" -> "sound";
      default -> "value";
    };
  }

  @Override
  public void close() {
    tasks.values().forEach(org.bukkit.scheduler.BukkitTask::cancel);
    tasks.clear();
    active.clear();
    sessions.clear();
    HandlerList.unregisterAll(this);
  }
}
