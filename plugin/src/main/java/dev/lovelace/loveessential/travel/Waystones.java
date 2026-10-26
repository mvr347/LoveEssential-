package dev.lovelace.loveessential.travel;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.lovecore.api.revive.DownedOracle;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.Position;
import dev.lovelace.loveessential.dialog.NativeDialogs;
import dev.lovelace.loveessential.gui.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** All world/inventory access is on the server thread; SQLite owns the durable definitions. */
public final class Waystones implements Listener, AutoCloseable {
  private static final String KIND = "waystone";
  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final NamespacedKey key;
  private final Map<String, SqliteStore.Document<Waystone>> nodes =
      new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
  private final Map<String, List<Entity>> models = new HashMap<>();
  private final Map<String, Integer> numbers = new HashMap<>();
  private final Map<String, SqliteStore.Document<Model>> catalog = new TreeMap<>();
  private final Set<String> modelWrites = new HashSet<>();
  private final Set<String> modelNamesInFlight = new HashSet<>();

  public record Model(String name, String style, String item) {
    public Model {
      if (name == null
          || name.isBlank()
          || name.length() > 64
          || style == null
          || !Set.of("wood", "stone", "rune", "itemsadder").contains(style)
          || item == null
          || item.length() > 128
          || style.equals("itemsadder") && !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
        throw new IllegalArgumentException("waystones.invalid");
    }
  }

  private record Saved(long revision, int number) {}

  private final Map<String, SqliteStore.Document<WaystoneRender>> renderProfiles = new HashMap<>();
  private final Set<String> profileWrites = new HashSet<>();

  private final Map<String, Long> retries = new HashMap<>();
  private final Set<String> writing = new HashSet<>();
  private final Map<UUID, Object> sessions = new HashMap<>();
  private final Map<UUID, Flight> flights = new HashMap<>();
  private final Map<UUID, Long> cooldowns = new HashMap<>();
  private final Map<UUID, Object> loads = new HashMap<>();
  private final Set<UUID> loading = new HashSet<>();
  private final Map<String, SqliteStore.Document<Refund>> refunds = new HashMap<>();
  private final Set<String> refunding = new HashSet<>();
  private NativeDialogs ui;
  private BukkitTask task;
  private boolean closed;
  private long ticks;
  private TeleportPlatform platform =
      new TeleportPlatform() {
        public CompletableFuture<Chunk> loadChunk(World world, int x, int z, boolean generate) {
          return TeleportPlatform.PAPER.loadChunk(world, x, z, generate);
        }

        public boolean standable(Location at) {
          return TeleportService.standable(at);
        }

        // Chunks are already loaded. Synchronous move keeps physical payment and outcome on one
        // tick.
        public CompletableFuture<Boolean> move(Player p, Location at) {
          return CompletableFuture.completedFuture(p.teleport(at));
        }
      };

  public record Refund(UUID player, long amount) {}

  private static final class Flight {
    final Player player;
    final Waystone source, destination;
    final Waystone.Route route;
    final Location start;
    final long due;
    LoveEconomy chargedBy;
    boolean loading, moving, settled;

    Flight(Player p, Waystone from, Waystone to, Waystone.Route route, long due) {
      player = p;
      source = from;
      destination = to;
      this.route = route;
      start = p.getLocation().clone();
      this.due = due;
    }
  }

  private static final class Menu implements GuiLayout {
    Inventory inventory;
    final String id;
    final Object route;
    Map<String, String> variables = Map.of();

    public Map<String, String> guiVariables() {
      return variables;
    }

    Predicate<Player> allowed = p -> true;
    Runnable reopen;

    public boolean guiAllowed(Player p) {
      return allowed.test(p);
    }

    public Runnable guiReopen() {
      return reopen;
    }

    final Map<Integer, Runnable> actions = new HashMap<>();

    Menu(String id, Object route) {
      this.id = id;
      this.route = route;
    }

    public String guiId() {
      return id;
    }

    public Object guiRoute() {
      return route;
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory inv) {
      inventory = inv;
    }

    public Map<Integer, ?> guiActions() {
      return actions;
    }

    public void guiPut(int slot, Runnable action) {
      actions.put(slot, action);
    }
  }

  public Waystones(
      LoveEssentialPlugin plugin, MainThread main, SqliteStore store, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    key = new NamespacedKey(plugin, "waystone");
  }

  public static void validate(org.bukkit.configuration.ConfigurationSection c) {
    for (var bound :
        List.of(
            new Object[] {"max-stones", 1, 999},
            new Object[] {"max-concurrent", 1, 64},
            new Object[] {"interact-radius", 1, 16})) {
      String path = "waystones." + bound[0];
      double value = c.getDouble(path, (int) bound[1]);
      if (!Double.isFinite(value) || value < (int) bound[1] || value > (int) bound[2])
        throw new IllegalArgumentException("waystones.invalid");
    }
  }

  public void ui(NativeDialogs ui) {
    this.ui = ui;
  }

  public CompletableFuture<Void> load() {
    return store
        .list(KIND, Waystone.class)
        .thenCompose(
            saved ->
                main.call(
                    () -> {
                      nodes.clear();
                      for (var d : saved) {
                        var n = d.value();
                        // Revalidate Gson payloads (Gson does not validate non-record legacy data).
                        new Waystone(
                            n.id(),
                            n.name(),
                            n.anchor(),
                            n.arrival(),
                            n.style(),
                            n.model(),
                            n.routes());
                        nodes.put(d.id(), d);
                      }
                      return null;
                    }))
        .thenCompose(v -> store.waystoneNumbers(nodes.keySet()))
        .thenCompose(
            loaded ->
                main.call(
                    () -> {
                      numbers.clear();
                      numbers.putAll(loaded);
                      return null;
                    }))
        .thenCompose(v -> seedModels())
        .thenCompose(v -> store.list("waystone-model", Model.class))
        .thenCompose(
            loaded ->
                main.call(
                    () -> {
                      catalog.clear();
                      for (var d : loaded) {
                        var m = d.value();
                        new Model(m.name(), m.style(), m.item());
                        catalog.put(d.id(), d);
                      }
                      return null;
                    }))
        .thenCompose(v -> store.list("waystone-render", WaystoneRender.class))
        .thenCompose(
            saved ->
                main.call(
                    () -> {
                      renderProfiles.clear();
                      for (var d : saved) {
                        var r = d.value();
                        new WaystoneRender(
                            r.modelX(),
                            r.modelY(),
                            r.modelZ(),
                            r.scale(),
                            r.height(),
                            r.width(),
                            r.centerX(),
                            r.centerZ(),
                            r.gap(),
                            r.textScale(),
                            r.text(),
                            r.context());
                        renderProfiles.put(d.id(), d);
                      }
                      for (String id : List.copyOf(models.keySet())) removeModel(id);
                      return null;
                    }))
        .thenCompose(v -> store.list("waystone-refund", Refund.class))
        .thenCompose(
            saved ->
                main.call(
                    () -> {
                      refunds.clear();
                      for (var r : saved) {
                        if (r.value().player() == null
                            || r.value().amount() <= 0
                            || r.value().amount() > 1_000_000_000L)
                          throw new IllegalArgumentException("waystones.invalid");
                        refunds.put(r.id(), r);
                      }
                      return null;
                    }));
  }

  private CompletableFuture<Void> seedModels() {
    return store
        .get("waystone-catalog", "initialized", Boolean.class)
        .thenCompose(
            marker -> {
              if (marker.isPresent()) return CompletableFuture.completedFuture(null);
              var defaults = new LinkedHashMap<String, Model>();
              defaults.put("wood", new Model("Деревянный", "wood", ""));
              defaults.put("stone", new Model("Каменный", "stone", ""));
              defaults.put("rune", new Model("Рунический", "rune", ""));
              for (int i = 1; i <= 4; i++)
                defaults.put(
                    "voidcore-" + i,
                    new Model("Указатели · " + i, "itemsadder", "voidcore:waystone_" + i));
              CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
              for (var entry : defaults.entrySet())
                chain =
                    chain.thenCompose(
                        v ->
                            store
                                .get("waystone-model", entry.getKey(), Model.class)
                                .thenCompose(
                                    existing ->
                                        existing.isPresent()
                                            ? CompletableFuture.completedFuture(null)
                                            : store
                                                .save(
                                                    "waystone-model",
                                                    entry.getKey(),
                                                    0,
                                                    entry.getValue(),
                                                    "bootstrap")
                                                .thenApply(r -> null)));
              return chain.thenCompose(
                  v ->
                      store
                          .save("waystone-catalog", "initialized", 0, true, "bootstrap")
                          .thenApply(r -> null));
            });
  }

  public Collection<Model> availableModels() {
    return catalog.values().stream().map(SqliteStore.Document::value).toList();
  }

  private List<String> modelChoices() {
    var labels = new ArrayList<String>();
    for (Model m : availableModels()) if (!labels.contains(m.name())) labels.add(m.name());
    labels.add("ItemsAdder");
    return List.copyOf(labels);
  }

  private Model chosenModel(String label, String manual) {
    if (label.equals("ItemsAdder")) return new Model("ItemsAdder", "itemsadder", manual);
    return availableModels().stream()
        .filter(m -> m.name().equals(label))
        .findFirst()
        .orElseGet(
            () ->
                switch (label) {
                  case "Деревянный" -> new Model(label, "wood", "");
                  case "Каменный" -> new Model(label, "stone", "");
                  case "Рунический" -> new Model(label, "rune", "");
                  default -> throw new IllegalArgumentException("waystones.changed");
                });
  }

  private String modelLabel(Waystone n) {
    return availableModels().stream()
        .filter(m -> m.style().equals(n.style()) && m.item().equals(n.model()))
        .map(Model::name)
        .findFirst()
        .orElseGet(
            () ->
                switch (n.style()) {
                  case "wood" -> "Деревянный";
                  case "stone" -> "Каменный";
                  case "rune" -> "Рунический";
                  default -> "ItemsAdder";
                });
  }

  private void modelMenu(Player p, int requested) {
    modelMenu(p, requested, null);
  }

  private void modelMenu(Player p, int requested, Runnable parent) {
    staff(p);
    var entries = List.copyOf(catalog.values());
    int page = Math.clamp(requested, 0, Math.max(0, (entries.size() - 1) / 20));
    var m = menu(p, "models", page);
    m.allowed = who -> enabled() && admin(who);
    int count = Math.min(20, entries.size() - page * 20);
    for (int i = 0; i < count; i++) {
      var entry = entries.get(page * 20 + i);
      card(
          m,
          MenuStyle.CONTENT[i],
          setting("models", "entry-title", "<gold><name>"),
          plugin.getConfig().getStringList("waystones.gui.menus.waystones-models.entry-lore"),
          Map.of(
              "name",
              entry.value().name(),
              "item",
              entry.value().item().isBlank() ? "Встроенная модель" : entry.value().item()),
          () -> modelForm(p, entry, page, parent));
    }
    button(
        m,
        entries.isEmpty() ? 22 : MenuStyle.CONTENT[count],
        "add-model",
        () -> modelForm(p, null, page, parent));
    if (page > 0) button(m, 36, "previous", () -> modelMenu(p, page - 1, parent));
    if ((page + 1) * 20 < entries.size())
      button(m, 44, "next", () -> modelMenu(p, page + 1, parent));
    if (parent != null) back(m, parent);
    MenuStyle.open(p, m.inventory);
  }

  private void modelForm(Player p, SqliteStore.Document<Model> doc, int page, Runnable parent) {
    staff(p);
    Model current =
        doc == null ? new Model("Новая модель", "itemsadder", "voidcore:waystone_1") : doc.value();
    String id = doc == null ? UUID.randomUUID().toString() : doc.id();
    sessions.put(p.getUniqueId(), new Object());
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "waystones.model-save",
            values -> {
              staff(p);
              if (doc == null && catalog.size() + modelWrites.size() >= 128
                  || doc != null && catalog.get(id) != doc)
                throw new IllegalArgumentException("waystones.changed");
              String style =
                  switch (values.get("style")) {
                    case "Деревянный" -> "wood";
                    case "Каменный" -> "stone";
                    case "Рунический" -> "rune";
                    default -> "itemsadder";
                  };
              var model =
                  new Model(
                      values.get("name"),
                      style,
                      style.equals("itemsadder") ? values.get("item") : "");
              if (catalog.values().stream()
                      .anyMatch(d -> !d.id().equals(id) && d.value().name().equals(model.name()))
                  || modelNamesInFlight.contains(model.name())
                  || model.name().equals("ItemsAdder"))
                throw new IllegalArgumentException("waystones.invalid");
              if (style.equals("itemsadder")) customItem(model.item());
              if (!modelWrites.add(id)) throw new IllegalArgumentException("waystones.busy");
              modelNamesInFlight.add(model.name());
              var pending =
                  store.save(
                      "waystone-model",
                      id,
                      doc == null ? 0 : doc.revision(),
                      model,
                      p.getUniqueId().toString());
              modelMenu(p, page, parent);
              Inventory open = p.getOpenInventory().getTopInventory();
              pending.whenComplete(
                  (revision, error) ->
                      main.execute(
                          () -> {
                            modelWrites.remove(id);
                            modelNamesInFlight.remove(model.name());
                            if (error != null) {
                              if (p.isOnline()) lang.send(p, "errors.storage");
                              return;
                            }
                            catalog.put(id, new SqliteStore.Document<>(id, revision, model));
                            if (p.isOnline()
                                && enabled()
                                && admin(p)
                                && p.getOpenInventory().getTopInventory() == open)
                              modelMenu(p, page, parent);
                          }));
            }));
    if (doc != null)
      buttons.add(
          ui.button(
              "render", "waystones.render-settings", values -> renderForm(p, doc, page, parent)));
    if (doc != null)
      buttons.add(
          ui.button(
              "delete",
              "waystones.model-delete",
              values -> {
                staff(p);
                if (catalog.get(id) != doc) throw new IllegalArgumentException("waystones.changed");
                ui.show(
                    p,
                    "waystones.model-delete",
                    "waystones.model-delete-help",
                    List.of(),
                    List.of(
                        ui.button(
                            "confirm",
                            "gui.remove",
                            ignored -> {
                              staff(p);
                              if (!modelWrites.add(id))
                                throw new IllegalArgumentException("waystones.busy");
                              var pending =
                                  store.delete(
                                      "waystone-model",
                                      id,
                                      doc.revision(),
                                      p.getUniqueId().toString());
                              modelMenu(p, page, parent);
                              Inventory open = p.getOpenInventory().getTopInventory();
                              pending.whenComplete(
                                  (v, error) ->
                                      main.execute(
                                          () -> {
                                            modelWrites.remove(id);
                                            if (error != null) {
                                              if (p.isOnline()) lang.send(p, "errors.storage");
                                              return;
                                            }
                                            catalog.remove(id);
                                            if (p.isOnline()
                                                && enabled()
                                                && admin(p)
                                                && p.getOpenInventory().getTopInventory() == open)
                                              modelMenu(p, page, parent);
                                          }));
                            }),
                        ui.button(
                            "back", "common.back", ignored -> modelForm(p, doc, page, parent))),
                    this::admin);
              }));
    buttons.add(ui.button("back", "common.back", values -> modelMenu(p, page, parent)));
    String styleLabel =
        switch (current.style()) {
          case "wood" -> "Деревянный";
          case "stone" -> "Каменный";
          case "rune" -> "Рунический";
          default -> "ItemsAdder";
        };
    ui.show(
        p,
        "waystones.models",
        "waystones.models-help",
        List.of(
            ui.text("name", "waystones.name", current.name(), 64),
            NativeDialogs.Field.select(
                "style",
                lang.render("waystones.style"),
                styleLabel,
                List.of("Деревянный", "Каменный", "Рунический", "ItemsAdder")),
            ui.text("item", "waystones.model-item", current.item(), 128)),
        buttons,
        this::admin);
  }

  private WaystoneRender renderProfile(String style, String item) {
    var saved = renderProfiles.get(WaystoneRender.key(style, item));
    return saved == null ? WaystoneRender.defaults(style, item) : saved.value();
  }

  private static double decimal(String value) {
    return Double.parseDouble(value.strip().replace(',', '.'));
  }

  private static double[] coordinates(String value, int count) {
    String[] parts = value.strip().split("\\s+");
    if (parts.length != count) throw new IllegalArgumentException("waystones.render-invalid");
    double[] result = new double[count];
    for (int i = 0; i < count; i++) result[i] = decimal(parts[i]);
    return result;
  }

  private void renderForm(Player p, SqliteStore.Document<Model> model, int page, Runnable parent) {
    staff(p);
    if (catalog.get(model.id()) != model) throw new IllegalArgumentException("waystones.changed");
    String id = WaystoneRender.key(model.value().style(), model.value().item());
    var doc = renderProfiles.get(id);
    var r = renderProfile(model.value().style(), model.value().item());
    ui.show(
        p,
        "waystones.render-settings",
        "waystones.render-help",
        List.of(
            ui.text(
                "offset",
                "waystones.render-offset",
                r.modelX() + " " + r.modelY() + " " + r.modelZ(),
                100),
            ui.text("scale", "waystones.render-scale", Double.toString(r.scale()), 24),
            ui.text("height", "waystones.render-height", Double.toString(r.height()), 24),
            ui.text("width", "waystones.render-width", Double.toString(r.width()), 24),
            ui.text("center", "waystones.render-center", r.centerX() + " " + r.centerZ(), 80),
            ui.text("gap", "waystones.render-gap", Double.toString(r.gap()), 24),
            ui.text(
                "text-scale", "waystones.render-text-scale", Double.toString(r.textScale()), 24),
            NativeDialogs.Field.select(
                "text",
                lang.render("waystones.render-text"),
                r.text() ? "Показывать" : "Скрыть",
                List.of("Показывать", "Скрыть")),
            NativeDialogs.Field.select(
                "context",
                lang.render("waystones.render-context"),
                r.context().equals("FIXED") ? "Фиксированный" : "Без преобразований",
                List.of("Фиксированный", "Без преобразований"))),
        List.of(
            ui.button(
                "save",
                "waystones.render-save",
                values -> {
                  staff(p);
                  if (catalog.get(model.id()) != model || renderProfiles.get(id) != doc)
                    throw new IllegalArgumentException("waystones.changed");
                  WaystoneRender next;
                  try {
                    double[] offset = coordinates(values.get("offset"), 3),
                        center = coordinates(values.get("center"), 2);
                    next =
                        new WaystoneRender(
                            offset[0],
                            offset[1],
                            offset[2],
                            decimal(values.get("scale")),
                            decimal(values.get("height")),
                            decimal(values.get("width")),
                            center[0],
                            center[1],
                            decimal(values.get("gap")),
                            decimal(values.get("text-scale")),
                            values.get("text").equals("Показывать"),
                            values.get("context").equals("Фиксированный") ? "FIXED" : "NONE");
                  } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException("waystones.render-invalid", invalid);
                  }
                  if (!profileWrites.add(id)) throw new IllegalArgumentException("waystones.busy");
                  var pending =
                      store.save(
                          "waystone-render",
                          id,
                          doc == null ? 0 : doc.revision(),
                          next,
                          p.getUniqueId().toString());
                  modelMenu(p, page, parent);
                  Inventory open = p.getOpenInventory().getTopInventory();
                  pending.whenComplete(
                      (revision, error) ->
                          main.execute(
                              () -> {
                                profileWrites.remove(id);
                                if (error != null) {
                                  if (p.isOnline()) lang.send(p, "errors.storage");
                                  return;
                                }
                                renderProfiles.put(
                                    id, new SqliteStore.Document<>(id, revision, next));
                                for (Waystone n : all())
                                  if (WaystoneRender.key(n.style(), n.model()).equals(id)) {
                                    removeModel(n.id());
                                    retries.remove(n.id());
                                  }
                                if (p.isOnline()
                                    && enabled()
                                    && admin(p)
                                    && p.getOpenInventory().getTopInventory() == open)
                                  modelMenu(p, page, parent);
                              }));
                }),
            ui.button("back", "common.back", values -> modelForm(p, model, page, parent))),
        this::admin);
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    for (var p : Bukkit.getOnlinePlayers()) loadPlayer(p);
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
  }

  private boolean enabled() {
    return !closed && plugin.module("waystones");
  }

  private boolean admin(Player p) {
    return p.isOp() || p.hasPermission("loveessential.waystones.admin");
  }

  private void staff(Player p) {
    if (!enabled() || !admin(p)) throw new IllegalArgumentException("errors.permission");
  }

  private void require(Player p) {
    if (!enabled() || !plugin.ready()) throw new IllegalArgumentException("errors.module-disabled");
    plugin.requireAccess("waystones", p);
    if (!love.authenticated(p)
        || love.combat(p)
        || p.isInsideVehicle()
        || !p.getPassengers().isEmpty()
        || Bukkit.getServicesManager().getRegistrations(DownedOracle.class).stream()
            .anyMatch(r -> r.getProvider().downed(p.getUniqueId())))
      throw new IllegalArgumentException("waystones.blocked");
  }

  private boolean near(Player p, Waystone source) {
    return p.getWorld().getUID().equals(source.anchor().world())
        && p.getLocation().distanceSquared(source.anchor().location())
            <= Math.pow(plugin.getConfig().getDouble("waystones.interact-radius", 5), 2);
  }

  private Waystone node(String id) {
    if (id == null || !id.matches("[1-9][0-9]{0,9}"))
      throw new IllegalArgumentException("waystones.not-found");
    var d = nodes.get(id);
    if (d == null) throw new IllegalArgumentException("waystones.not-found");
    return d.value();
  }

  public Collection<Waystone> all() {
    return nodes.values().stream()
        .map(SqliteStore.Document::value)
        .sorted(Comparator.comparingInt(n -> number(n.id())))
        .toList();
  }

  public int number(String id) {
    return numbers.getOrDefault(id, 0);
  }

  private String byNumber(String value) {
    return numbers.entrySet().stream()
        .filter(e -> e.getValue().toString().equals(value))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("waystones.not-found"));
  }

  public void command(Player p, String[] args) {
    if (args.length > 0) {
      if (Set.of("cancel", "отмена").contains(args[0])) {
        var f = flights.get(p.getUniqueId());
        if (f != null && !f.moving) finish(f, false);
        return;
      }
      staff(p);
      if (Set.of("models", "модели").contains(args[0])) modelMenu(p, 0);
      else if (Set.of("create", "создать").contains(args[0])) create(p);
      else if (Set.of("edit", "изменить", "меню").contains(args[0])) {
        if (args.length == 1) list(p, 0);
        else editor(p, byNumber(args[1]));
      } else throw new IllegalArgumentException("waystones.usage");
      return;
    }
    require(p);
    var closest =
        all().stream()
            .filter(n -> near(p, n))
            .min(
                Comparator.comparingDouble(
                    n -> p.getLocation().distanceSquared(n.anchor().location())))
            .orElseThrow(() -> new IllegalArgumentException("waystones.nearby"));
    travelMenu(p, closest.id(), 0);
  }

  public List<String> suggestions(Player p, String[] args) {
    if (!admin(p)) return args.length <= 1 ? List.of("cancel", "отмена") : List.of();
    if (args.length <= 1)
      return List.of(
          "create", "создать", "edit", "изменить", "models", "модели", "cancel", "отмена");
    if (args.length == 2 && Set.of("edit", "изменить").contains(args[0]))
      return numbers.values().stream().sorted().map(String::valueOf).toList();
    return List.of();
  }

  private Component text(String value, Map<String, String> vars) {
    return lang.renderText(value, vars);
  }

  private Menu menu(Player p, String screen, Object context) {
    sessions.put(p.getUniqueId(), new Object());
    var m = new Menu("waystones." + screen, List.of(screen, context));
    String nodeId =
        context instanceof String id
            ? id
            : context instanceof List<?> parts && !parts.isEmpty()
                ? String.valueOf(parts.getFirst())
                : "";
    if (nodeId.matches("[1-9][0-9]{0,9}") && nodes.containsKey(nodeId))
      m.variables = Map.of("name", node(nodeId).name());
    int size =
        screen.equals("list")
                || screen.equals("models")
                || screen.equals("routes")
                || screen.equals("travel")
            ? 54
            : 27;
    m.inventory =
        Bukkit.createInventory(
            m, size, text(setting(screen, "title", "<gold>Путевые столбы"), m.variables));
    m.allowed = who -> enabled() && love.authenticated(who);
    MenuStyle.frame(m.inventory);
    m.inventory.setItem(
        size - 1,
        MenuStyle.head(head(), lang.render("gui.close"), List.of(lang.render("gui.click.close"))));
    m.actions.put(size - 1, p::closeInventory);
    return m;
  }

  private String setting(String screen, String field, String fallback) {
    return plugin
        .getConfig()
        .getString("waystones.gui.menus.waystones-" + screen + "." + field, fallback);
  }

  private String head() {
    return plugin
        .getConfig()
        .getString(
            "waystones.gui.texture",
            "bd69e06e5dadfd84e5f3d1c21063f2553b2fa945ee1d4d7152fdc5425bc12a9");
  }

  private void card(
      Menu m,
      int slot,
      String title,
      List<String> lore,
      Map<String, String> vars,
      Runnable action) {
    m.inventory.setItem(
        slot,
        MenuStyle.head(head(), text(title, vars), lore.stream().map(s -> text(s, vars)).toList()));
    if (action != null) m.actions.put(slot, action);
  }

  private void button(Menu m, int slot, String id, Runnable action) {
    String path = "waystones.gui.buttons." + id;
    card(
        m,
        slot,
        plugin.getConfig().getString(path + ".title", id),
        plugin.getConfig().getStringList(path + ".lore"),
        Map.of(),
        action);
  }

  private void back(Menu m, Runnable action) {
    button(m, m.inventory.getSize() - 2, "back", action);
  }

  private void pages(Menu m, int page, int count, IntConsumer open) {
    if (page > 0) button(m, 36, "previous", () -> open.accept(page - 1));
    if ((page + 1) * 21 < count) button(m, 44, "next", () -> open.accept(page + 1));
  }

  private int page(int page, int count) {
    return Math.clamp(page, 0, Math.max(0, (count - 1) / 21));
  }

  private void list(Player p, int requested) {
    staff(p);
    var list = List.copyOf(all());
    int page = Math.clamp(requested, 0, Math.max(0, (list.size() - 1) / 20));
    var m = menu(p, "list", page);
    int count = Math.min(20, list.size() - page * 20);
    for (int i = 0; i < count; i++) {
      var n = list.get(page * 20 + i);
      card(
          m,
          MenuStyle.CONTENT[i],
          setting("list", "entry-title", "<gold><name> · №<id>"),
          plugin.getConfig().getStringList("waystones.gui.menus.waystones-list.entry-lore"),
          Map.of("name", n.name(), "id", Integer.toString(number(n.id()))),
          () -> editor(p, n.id()));
    }
    button(m, list.isEmpty() ? 22 : MenuStyle.CONTENT[count], "create", () -> create(p));
    if (page > 0) button(m, 36, "previous", () -> list(p, page - 1));
    if ((page + 1) * 20 < list.size()) button(m, 44, "next", () -> list(p, page + 1));
    button(m, 51, "models", () -> modelMenu(p, 0, () -> list(p, page)));
    MenuStyle.open(p, m.inventory);
  }

  private void create(Player p) {
    staff(p);
    sessions.put(p.getUniqueId(), new Object());
    ui.show(
        p,
        "waystones.create",
        "waystones.create-help",
        List.of(
            NativeDialogs.Field.text("name", lang.render("waystones.name"), "Путевой столб", 64),
            NativeDialogs.Field.select(
                "style",
                lang.render("waystones.style"),
                modelChoices().contains("Каменный") ? "Каменный" : modelChoices().getFirst(),
                modelChoices()),
            NativeDialogs.Field.select(
                "direction",
                lang.render("waystones.direction"),
                "Север",
                List.of("Север", "Восток", "Юг", "Запад")),
            NativeDialogs.Field.text("model", lang.render("waystones.model"), "", 128)),
        List.of(
            ui.button(
                "create",
                "waystones.place",
                v -> {
                  staff(p);
                  if (nodes.size() + writing.size()
                      >= plugin.getConfig().getInt("waystones.max-stones", 100))
                    throw new IllegalArgumentException("waystones.limit");
                  Model chosen = chosenModel(v.get("style"), v.get("model"));
                  String style = chosen.style();
                  float yaw =
                      switch (v.get("direction")) {
                        case "Восток" -> 270;
                        case "Юг" -> 0;
                        case "Запад" -> 90;
                        default -> 180;
                      };
                  var at = p.getLocation().getBlock().getLocation().add(.5, 0, .5);
                  at.setYaw(yaw);
                  if (all().stream()
                      .anyMatch(
                          n ->
                              n.anchor().world().equals(at.getWorld().getUID())
                                  && n.anchor().location().distanceSquared(at) < 4))
                    throw new IllegalArgumentException("waystones.overlap");
                  if (style.equals("itemsadder")) customItem(chosen.item());
                  var arrival = at.clone().add(at.getDirection().multiply(2));
                  if (v.get("name").isBlank())
                    throw new IllegalArgumentException("waystones.invalid");
                  Object session = sessions.get(p.getUniqueId());
                  store
                      .nextSequence("waystone", Integer.MAX_VALUE)
                      .whenComplete(
                          (number, error) ->
                              main.execute(
                                  () -> {
                                    if (!p.isOnline()
                                        || !enabled()
                                        || session != sessions.get(p.getUniqueId())) return;
                                    if (error != null) {
                                      lang.send(p, "errors.storage");
                                      return;
                                    }
                                    String id = number.toString();
                                    if (nodes.size() + writing.size()
                                        >= plugin.getConfig().getInt("waystones.max-stones", 100)) {
                                      lang.send(p, "waystones.limit");
                                      return;
                                    }
                                    var n =
                                        new Waystone(
                                            id,
                                            v.get("name"),
                                            Position.of(at),
                                            Position.of(arrival),
                                            style,
                                            chosen.item(),
                                            List.of());
                                    save(p, n, 0, () -> editor(p, id));
                                  }));
                }),
            ui.button("back", "common.back", v -> list(p, 0))),
        this::admin);
  }

  private void editor(Player p, String id) {
    staff(p);
    var n = node(id);
    var m = menu(p, "editor", id);
    m.allowed = who -> enabled() && admin(who) && nodes.containsKey(id) && node(id) == n;
    m.reopen = () -> editor(p, id);
    button(m, 11, "appearance", () -> appearance(p, n));
    button(m, 10, "directions", () -> routes(p, id, 0));
    button(
        m,
        13,
        "arrival",
        () ->
            save(
                p,
                new Waystone(
                    id,
                    n.name(),
                    n.anchor(),
                    Position.of(p.getLocation()),
                    n.style(),
                    n.model(),
                    n.routes()),
                nodes.get(id).revision(),
                () -> editor(p, id)));
    button(
        m,
        15,
        "rotate",
        () -> {
          var a = n.anchor();
          save(
              p,
              new Waystone(
                  id,
                  n.name(),
                  new Position(a.world(), a.x(), a.y(), a.z(), (a.yaw() + 90) % 360, 0),
                  n.arrival(),
                  n.style(),
                  n.model(),
                  n.routes()),
              nodes.get(id).revision(),
              () -> editor(p, id));
        });
    button(
        m,
        16,
        "delete",
        () ->
            ui.show(
                p,
                "waystones.delete",
                "waystones.delete-help",
                List.of(),
                List.of(
                    ui.button("delete", "gui.remove", v -> remove(p, n)),
                    ui.button("back", "common.back", v -> editor(p, id))),
                this::admin));
    back(m, () -> list(p, 0));
    MenuStyle.open(p, m.inventory);
  }

  private void appearance(Player p, Waystone n) {
    staff(p);
    var doc = nodes.get(n.id());
    List<String> styles = new ArrayList<>(modelChoices());
    String selected = modelLabel(n);
    // Preserve a manually selected legacy model if its catalog entry was removed.
    if (!styles.contains(selected)) styles.add(selected);
    ui.show(
        p,
        "waystones.appearance",
        "waystones.appearance-help",
        List.of(
            ui.text("name", "waystones.name", n.name(), 64),
            NativeDialogs.Field.select("style", lang.render("waystones.style"), selected, styles),
            ui.text("model", "waystones.model", n.model(), 128)),
        List.of(
            ui.button(
                "save",
                "content.save",
                v -> {
                  staff(p);
                  if (nodes.get(n.id()) != doc)
                    throw new IllegalArgumentException("waystones.changed");
                  Model chosen = chosenModel(v.get("style"), v.get("model"));
                  String style = chosen.style();
                  if (style.equals("itemsadder")) customItem(chosen.item());
                  save(
                      p,
                      new Waystone(
                          n.id(),
                          v.get("name"),
                          n.anchor(),
                          n.arrival(),
                          style,
                          chosen.item(),
                          n.routes()),
                      doc.revision(),
                      () -> editor(p, n.id()));
                }),
            ui.button("back", "common.back", v -> editor(p, n.id()))),
        this::admin);
  }

  private void routes(Player p, String source, int requested) {
    staff(p);
    var n = node(source);
    var targets = all().stream().filter(t -> !t.id().equals(source)).toList();
    int page = page(requested, targets.size());
    var m = menu(p, "routes", List.of(source, page));
    m.allowed = who -> enabled() && admin(who) && nodes.containsKey(source) && node(source) == n;
    m.reopen = () -> routes(p, source, page);
    for (int i = page * 21; i < Math.min(targets.size(), (page + 1) * 21); i++) {
      var target = targets.get(i);
      boolean linked = n.routes().stream().anyMatch(r -> r.target().equals(target.id()));
      card(
          m,
          MenuStyle.CONTENT[i % 21],
          setting("routes", "entry-title", "<gold><name>"),
          plugin
              .getConfig()
              .getStringList(
                  "waystones.gui.menus.waystones-routes."
                      + (linked ? "linked-lore" : "unlinked-lore")),
          Map.of("name", target.name()),
          () -> routeForm(p, n, target, page));
    }
    pages(m, page, targets.size(), a -> routes(p, source, a));
    back(m, () -> editor(p, source));
    MenuStyle.open(p, m.inventory);
  }

  private void routeForm(Player p, Waystone n, Waystone target, int page) {
    staff(p);
    var doc = nodes.get(n.id());
    var r =
        n.routes().stream()
            .filter(v -> v.target().equals(target.id()))
            .findFirst()
            .orElse(new Waystone.Route(target.id(), 0, 3, 30, ""));
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              staff(p);
              if (nodes.get(n.id()) != doc || !nodes.containsKey(target.id()))
                throw new IllegalArgumentException("waystones.changed");
              var next =
                  new Waystone.Route(
                      target.id(),
                      Long.parseLong(v.get("cost")),
                      Integer.parseInt(v.get("warmup")),
                      Integer.parseInt(v.get("cooldown")),
                      v.get("permission"));
              var list = new ArrayList<>(n.routes());
              list.removeIf(a -> a.target().equals(target.id()));
              list.add(next);
              save(
                  p,
                  new Waystone(
                      n.id(), n.name(), n.anchor(), n.arrival(), n.style(), n.model(), list),
                  doc.revision(),
                  () -> routes(p, n.id(), page));
            }));
    buttons.add(
        ui.button(
            "delete",
            "waystones.unlink",
            v -> {
              staff(p);
              if (nodes.get(n.id()) != doc) throw new IllegalArgumentException("waystones.changed");
              save(
                  p,
                  new Waystone(
                      n.id(),
                      n.name(),
                      n.anchor(),
                      n.arrival(),
                      n.style(),
                      n.model(),
                      n.routes().stream().filter(a -> !a.target().equals(target.id())).toList()),
                  doc.revision(),
                  () -> routes(p, n.id(), page));
            }));
    buttons.add(ui.button("back", "common.back", v -> routes(p, n.id(), page)));
    ui.show(
        p,
        lang.render("waystones.route-title", Map.of("from", n.name(), "to", target.name())),
        lang.render("waystones.route-help"),
        List.of(
            ui.text("cost", "waystones.cost", "" + r.cost(), 10),
                ui.text("warmup", "waystones.warmup", "" + r.warmup(), 3),
            ui.text("cooldown", "waystones.cooldown", "" + r.cooldown(), 5),
                ui.text("permission", "waystones.permission", r.permission(), 128)),
        buttons,
        this::admin);
  }

  private void save(Player p, Waystone n, long expected, Runnable after) {
    staff(p);
    if (!writing.add(n.id())) return;
    Object session = sessions.get(p.getUniqueId());
    store
        .save(KIND, n.id(), expected, n, p.getUniqueId().toString())
        .thenCompose(
            revision ->
                store
                    .waystoneNumbers(List.of(n.id()))
                    .thenApply(assigned -> new Saved(revision, assigned.get(n.id()))))
        .whenComplete(
            (revision, error) ->
                main.execute(
                    () -> {
                      writing.remove(n.id());
                      if (error != null) {
                        if (p.isOnline()) lang.send(p, "errors.storage");
                        return;
                      }
                      numbers.put(n.id(), revision.number());
                      nodes.put(n.id(), new SqliteStore.Document<>(n.id(), revision.revision(), n));
                      retries.remove(n.id());
                      removeModel(n.id());
                      if (p.isOnline() && session == sessions.get(p.getUniqueId()) && enabled())
                        after.run();
                    }));
  }

  private void remove(Player p, Waystone n) {
    staff(p);
    var doc = nodes.get(n.id());
    if (doc == null || doc.value() != n || !writing.add(n.id()))
      throw new IllegalArgumentException("waystones.changed");
    store
        .delete(KIND, n.id(), doc.revision(), p.getUniqueId().toString())
        .whenComplete(
            (v, e) ->
                main.execute(
                    () -> {
                      writing.remove(n.id());
                      if (e != null) {
                        if (p.isOnline()) lang.send(p, "errors.storage");
                        return;
                      }
                      nodes.remove(n.id());
                      numbers.remove(n.id());
                      retries.remove(n.id());
                      removeModel(n.id());
                      if (p.isOnline() && enabled()) list(p, 0);
                    }));
  }

  public void travelMenu(Player p, String source, int requested) {
    require(p);
    var n = node(source);
    if (!near(p, n)) throw new IllegalArgumentException("waystones.nearby");
    var routes = n.routes().stream().filter(r -> nodes.containsKey(r.target())).toList();
    int page = page(requested, routes.size());
    var m = menu(p, "travel", List.of(source, page));
    m.allowed = who -> enabled() && nodes.containsKey(source) && node(source) == n && near(who, n);
    m.reopen = () -> travelMenu(p, source, page);
    for (int i = page * 21; i < Math.min(routes.size(), (page + 1) * 21); i++) {
      var r = routes.get(i);
      var target = node(r.target());
      Map<String, String> vars =
          Map.of(
              "name",
              target.name(),
              "cost",
              r.cost() == 0
                  ? "Бесплатно"
                  : r.cost() + " " + love.economy().map(LoveEconomy::currencyName).orElse("монет"),
              "warmup",
              "" + r.warmup(),
              "cooldown",
              "" + r.cooldown(),
              "status",
              status(p, r, target));
      var lore =
          plugin.getConfig().getStringList("waystones.gui.menus.waystones-travel.route-lore");
      card(
          m,
          MenuStyle.CONTENT[i % 21],
          setting("travel", "route-title", "<gold><name>"),
          lore,
          vars,
          () -> begin(p, source, r.target()));
    }
    if (routes.isEmpty())
      card(
          m,
          22,
          setting("travel", "empty-title", "<gray>Направлений пока нет"),
          plugin.getConfig().getStringList("waystones.gui.menus.waystones-travel.empty-lore"),
          Map.of(),
          null);
    pages(m, page, routes.size(), a -> travelMenu(p, source, a));
    MenuStyle.open(p, m.inventory);
  }

  private String status(Player p, Waystone.Route route, Waystone destination) {
    String state = "available";
    try {
      if (!route.permission().isBlank() && !p.hasPermission(route.permission()))
        state = "permission";
      else if (cooldowns.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis())
        state = "cooldown";
      else if (route.cost() > 0 && love.economy().filter(e -> e.has(p, route.cost())).isEmpty())
        state = "funds";
      else check(p, route, destination);
    } catch (RuntimeException unavailable) {
      state = "unavailable";
    }
    return plugin.getConfig().getString("waystones.gui.status." + state, "Недоступно");
  }

  public void begin(Player p, String source, String target) {
    require(p);
    var n = node(source);
    var to = node(target);
    if (!near(p, n) || flights.containsKey(p.getUniqueId()) || loading.contains(p.getUniqueId()))
      throw new IllegalArgumentException("waystones.busy");
    var r =
        n.routes().stream()
            .filter(a -> a.target().equals(target))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("waystones.not-found"));
    check(p, r, to);
    if (cooldowns.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis())
      throw new IllegalArgumentException("waystones.cooling");
    if (r.cost() > 0 && love.economy().filter(e -> e.has(p, r.cost())).isEmpty())
      throw new IllegalArgumentException("waystones.no-funds");
    flights.put(p.getUniqueId(), new Flight(p, n, to, r, ticks + r.warmup() * 20L));
    p.closeInventory();
    love.notify(
        p,
        lang.render("waystones.preparing")
            .append(Component.space())
            .append(
                lang.render("waystones.cancel-button")
                    .clickEvent(
                        net.kyori.adventure.text.event.ClickEvent.runCommand("/waystone cancel"))));
  }

  private void check(Player p, Waystone.Route r, Waystone to) {
    require(p);
    if (!r.permission().isBlank() && !p.hasPermission(r.permission()))
      throw new IllegalArgumentException("errors.permission");
    World destinationWorld = Bukkit.getWorld(to.arrival().world());
    if (destinationWorld == null)
      throw new IllegalArgumentException("waystones.destination-unavailable");
    plugin.requireDestination("waystones", destinationWorld.getName());
    if (!love.territorialAccess(p, to.arrival().location()))
      throw new IllegalArgumentException("errors.territory");
  }

  private boolean valid(Flight f) {
    Player p = f.player;
    try {
      check(p, f.route, f.destination);
      return p.isOnline()
          && enabled()
          && nodes.get(f.source.id()) != null
          && node(f.source.id()) == f.source
          && nodes.get(f.destination.id()) != null
          && node(f.destination.id()) == f.destination
          && p.getWorld().equals(f.start.getWorld())
          && (f.moving || p.getLocation().distanceSquared(f.start) <= .25);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void tick() {
    ticks++;
    for (var f : List.copyOf(flights.values())) {
      if (f.moving) continue;
      if (ticks % 20 == 0 && f.due > ticks && f.player.isOnline())
        f.player.sendActionBar(
            lang.render(
                "waystones.countdown", Map.of("seconds", "" + ((f.due - ticks + 19) / 20))));
      if (!valid(f)) {
        finish(f, false);
        continue;
      }
      if (f.loading && ticks - f.due > 400) {
        finish(f, false);
        continue;
      }
      if (!f.loading
          && f.due <= ticks
          && flights.values().stream().filter(a -> a.loading).count()
              < plugin.getConfig().getInt("waystones.max-concurrent", 8)) {
        f.loading = true;
        move(f);
      }
    }
    if (ticks % 20 == 0) {
      reconcile();
      for (var p : Bukkit.getOnlinePlayers()) deliver(p);
    }
  }

  private void move(Flight f) {
    var at = f.destination.arrival().location();
    try {
      platform
          .loadChunk(at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4, false)
          .whenComplete(
              (chunk, error) ->
                  main.execute(
                      () -> {
                        if (f.settled) return;
                        try {
                          if (error != null
                              || chunk == null
                              || !valid(f)
                              || !at.getWorld()
                                  .isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)
                              || !at.getWorld().getWorldBorder().isInside(at)
                              || !platform.standable(at)) {
                            finish(f, false);
                            return;
                          }
                          if (f.route.cost() > 0) {
                            var economy = love.economy().orElse(null);
                            if (economy == null || !economy.charge(f.player, f.route.cost())) {
                              finish(f, false);
                              return;
                            }
                            f.chargedBy = economy;
                          }
                          f.moving = true;
                          try {
                            platform
                                .move(f.player, at)
                                .whenComplete(
                                    (ok, e) ->
                                        main.execute(
                                            () -> finish(f, e == null && Boolean.TRUE.equals(ok))));
                          } catch (RuntimeException e) {
                            finish(f, false);
                          }
                        } catch (RuntimeException e) {
                          finish(f, false);
                        }
                      }));
    } catch (RuntimeException e) {
      finish(f, false);
    }
  }

  private void finish(Flight f, boolean arrived) {
    if (f.settled) return;
    f.settled = true;
    flights.remove(f.player.getUniqueId(), f);
    if (!arrived && f.chargedBy != null) {
      if (f.player.isOnline()) {
        try {
          f.chargedBy.give(f.player, f.route.cost());
        } catch (RuntimeException failure) {
          plugin
              .getLogger()
              .log(
                  java.util.logging.Level.SEVERE,
                  "Возврат путевого столба не подтверждён: "
                      + f.player.getUniqueId()
                      + ", сумма "
                      + f.route.cost(),
                  failure);
        }
      } else refund(f.player.getUniqueId(), f.route.cost());
    }
    if (arrived) {
      long until = System.currentTimeMillis() + f.route.cooldown() * 1000L;
      cooldowns.put(f.player.getUniqueId(), until);
      store
          .profile(f.player.getUniqueId(), "waystones-cooldown", "" + until)
          .whenComplete(
              (v, error) -> {
                if (error != null)
                  plugin
                      .getLogger()
                      .warning(
                          "Не удалось сохранить задержку путевого столба: "
                              + f.player.getUniqueId());
              });
      if (f.player.isOnline()) {
        lang.send(f.player, "waystones.arrived");
        f.player.playSound(f.player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, .6f, 1);
      }
    } else if (f.player.isOnline()) lang.send(f.player, "waystones.cancelled");
  }

  private void refund(UUID player, long amount) {
    String id = UUID.randomUUID().toString();
    var value = new Refund(player, amount);
    store
        .save("waystone-refund", id, 0, value, "system")
        .whenComplete(
            (rev, error) ->
                main.execute(
                    () -> {
                      if (error == null)
                        refunds.put(id, new SqliteStore.Document<>(id, rev, value));
                      else
                        plugin
                            .getLogger()
                            .severe(
                                "Не удалось сохранить возврат путевого столба: "
                                    + player
                                    + ", сумма "
                                    + amount);
                    }));
  }

  private void deliver(Player p) {
    if (!love.authenticated(p)) return;
    var economy = love.economy();
    if (economy.isEmpty()) return;
    for (var doc : List.copyOf(refunds.values()))
      if (doc.value().player().equals(p.getUniqueId()) && refunding.add(doc.id())) {
        economy.get().give(p, doc.value().amount());
        store
            .delete("waystone-refund", doc.id(), doc.revision(), p.getUniqueId().toString())
            .whenComplete(
                (v, error) ->
                    main.execute(
                        () -> {
                          // Never retry a physical payout in the current process after a delete
                          // failure.
                          if (error == null) {
                            refunds.remove(doc.id());
                            refunding.remove(doc.id());
                          } else
                            plugin
                                .getLogger()
                                .severe(
                                    "Не удалось подтвердить возврат путевого столба: " + doc.id());
                        }));
      }
  }

  private void loadPlayer(Player p) {
    loading.add(p.getUniqueId());
    Object session = new Object();
    loads.put(p.getUniqueId(), session);
    store
        .profile(p.getUniqueId(), "waystones-cooldown")
        .whenComplete(
            (value, e) ->
                main.execute(
                    () -> {
                      if (closed || !p.isOnline() || loads.get(p.getUniqueId()) != session) return;
                      if (e == null) {
                        try {
                          cooldowns.put(p.getUniqueId(), value.map(Long::parseLong).orElse(0L));
                          loading.remove(p.getUniqueId());
                          deliver(p);
                        } catch (NumberFormatException invalid) {
                          lang.send(p, "errors.storage");
                        }
                      } else lang.send(p, "errors.storage");
                    }));
  }

  private ItemStack customItem(String id) {
    try {
      var items = Bukkit.getPluginManager().getPlugin("ItemsAdder");
      if (items == null || !items.isEnabled())
        throw new IllegalArgumentException("waystones.model-unavailable");
      var type =
          Class.forName(
              "dev.lone.itemsadder.api.CustomStack", true, items.getClass().getClassLoader());
      var stack = type.getMethod("getInstance", String.class).invoke(null, id);
      if (stack == null) throw new IllegalArgumentException("waystones.model-unavailable");
      return ((ItemStack) type.getMethod("getItemStack").invoke(stack)).clone();
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("waystones.model-unavailable", e);
    }
  }

  private void reconcile() {
    for (String id : List.copyOf(models.keySet())) {
      var d = nodes.get(id);
      if (!enabled()
          || !plugin.getConfig().getBoolean("waystones.render.enabled", true)
          || d == null
          || !loaded(d.value())
          || models.get(id).stream().anyMatch(e -> !e.isValid())) removeModel(id);
    }
    if (enabled() && plugin.getConfig().getBoolean("waystones.render.enabled", true))
      for (var d : nodes.values())
        if (loaded(d.value())
            && !models.containsKey(d.id())
            && retries.getOrDefault(d.id(), 0L) <= ticks) {
          try {
            spawn(d.value());
          } catch (RuntimeException e) {
            retries.put(d.id(), ticks + 1200);
            plugin
                .getLogger()
                .warning("Не удалось показать путевой столб №" + d.id() + ": " + e.getMessage());
          }
        }
  }

  private boolean loaded(Waystone n) {
    World w = Bukkit.getWorld(n.anchor().world());
    return w != null
        && w.isChunkLoaded(
            (int) Math.floor(n.anchor().x()) >> 4, (int) Math.floor(n.anchor().z()) >> 4);
  }

  private <T extends Entity> T entity(
      Location at, Class<T> type, List<Entity> entities, Consumer<T> setup, String id) {
    var e =
        at.getWorld()
            .spawn(
                at,
                type,
                value -> {
                  entities.add(value);
                  value.setPersistent(false);
                  value.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
                  setup.accept(value);
                });
    return e;
  }

  private void spawn(Waystone n) {
    var entities = new ArrayList<Entity>();
    var at = n.anchor().location();
    var profile = renderProfile(n.style(), n.model());
    var origin = profile.model(at);
    float scale = (float) profile.scale();
    try {
      if (n.style().equals("itemsadder"))
        entity(
            origin,
            ItemDisplay.class,
            entities,
            e -> {
              e.setItemStack(customItem(n.model()));
              e.setItemDisplayTransform(
                  ItemDisplay.ItemDisplayTransform.valueOf(profile.context()));
              var tr = e.getTransformation();
              tr.getScale().set(scale);
              e.setTransformation(tr);
            },
            n.id());
      else {
        Material pole =
            n.style().equals("wood")
                ? Material.STRIPPED_SPRUCE_LOG
                : n.style().equals("rune") ? Material.POLISHED_DEEPSLATE : Material.STONE_BRICKS;
        for (int i = 0; i < 3; i++) {
          int level = i;
          entity(
              origin.clone().add(0, i * .7 * scale, 0),
              BlockDisplay.class,
              entities,
              e -> {
                e.setBlock(
                    (level == 2 && n.style().equals("rune") ? Material.SEA_LANTERN : pole)
                        .createBlockData());
                var tr = e.getTransformation();
                float width = level == 0 ? .8f : .35f;
                tr.getScale().set(width * scale, .7f * scale, .35f * scale);
                tr.getTranslation().set(-width * scale / 2, 0, -.175f * scale);
                e.setTransformation(tr);
              },
              n.id());
        }
        if (n.style().equals("wood"))
          entity(
              origin.clone().add(0, 1.5 * scale, 0),
              BlockDisplay.class,
              entities,
              e -> {
                e.setBlock(Material.SPRUCE_PLANKS.createBlockData());
                var t = e.getTransformation();
                t.getScale().set(1.2f * scale, .3f * scale, .15f * scale);
                t.getTranslation().set(-.6f * scale, 0, -.075f * scale);
                e.setTransformation(t);
              },
              n.id());
      }
      if (profile.text())
        entity(
            profile.label(at, n.style()),
            TextDisplay.class,
            entities,
            e -> {
              e.text(
                  text(
                      plugin
                          .getConfig()
                          .getString("waystones.render.label-text", "<gold><name> <gray>· ПКМ"),
                      Map.of("name", n.name())));
              e.setBillboard(Display.Billboard.CENTER);
              e.setAlignment(TextDisplay.TextAlignment.CENTER);
              e.setShadowed(true);
              e.setDefaultBackground(false);
              e.setBackgroundColor(Color.fromARGB(0));
              e.setLineWidth(240);
              var tr = e.getTransformation();
              tr.getScale().set((float) profile.textScale());
              e.setTransformation(tr);
            },
            n.id());
      entity(
          profile.center(at, n.style()),
          Interaction.class,
          entities,
          e -> {
            e.setInteractionWidth((float) (profile.width() * profile.scale()));
            e.setInteractionHeight((float) (profile.height() * profile.scale()));
            e.setResponsive(true);
          },
          n.id());
      models.put(n.id(), entities);
    } catch (RuntimeException e) {
      entities.forEach(Entity::remove);
      throw e;
    }
  }

  private void removeModel(String id) {
    var list = models.remove(id);
    if (list != null) list.forEach(Entity::remove);
  }

  @EventHandler
  public void interact(PlayerInteractEntityEvent e) {
    String id =
        e.getRightClicked().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    if (id == null) return;
    e.setCancelled(true);
    if (e.getHand() != EquipmentSlot.HAND) return;
    try {
      travelMenu(e.getPlayer(), id, 0);
    } catch (IllegalArgumentException failure) {
      lang.send(e.getPlayer(), failure.getMessage());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void protectModel(EntityDamageEvent e) {
    if (e.getEntity().getPersistentDataContainer().has(key)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    if (e.getEntity() instanceof Player p) {
      var f = flights.get(p.getUniqueId());
      if (f != null && !f.moving) finish(f, false);
    }
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getInventory().getHolder() instanceof Menu m)) return;
    e.setCancelled(true);
    if (e.getWhoClicked() instanceof Player p
        && e.getRawSlot() >= 0
        && e.getRawSlot() < m.inventory.getSize()) {
      if (!m.guiAllowed(p)) {
        p.closeInventory();
        lang.send(p, "waystones.changed");
        return;
      }
      var action = m.actions.get(e.getRawSlot());
      if (action != null)
        try {
          MenuStyle.action(p, action);
        } catch (IllegalArgumentException failure) {
          lang.send(p, failure.getMessage());
        }
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    loadPlayer(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    Player p = e.getPlayer();
    var f = flights.get(p.getUniqueId());
    if (f != null && !f.moving) finish(f, false);
    loads.remove(p.getUniqueId());
    sessions.remove(p.getUniqueId());
    cooldowns.remove(p.getUniqueId());
    loading.remove(p.getUniqueId());
  }

  @Override
  public void close() {
    closed = true;
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    for (var f : List.copyOf(flights.values())) finish(f, false);
    for (String id : List.copyOf(models.keySet())) removeModel(id);
    retries.clear();
    sessions.clear();
    loads.clear();
    cooldowns.clear();
    loading.clear();
  }
}
