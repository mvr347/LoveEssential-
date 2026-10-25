package dev.lovelace.loveessential.wizard;

import com.google.gson.Gson;
import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.gui.ChatPrompt;
import dev.lovelace.loveessential.gui.MenuStyle;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.npc.*;
import dev.lovelace.loveessential.storage.SqliteStore;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
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

  private abstract static class RoutedMenu implements dev.lovelace.loveessential.gui.GuiLayout {
    Inventory inventory;
    String menuId = "gui.wizard-title";
    final Map<Integer, Object> routes = new HashMap<>();

    RoutedMenu() {
      for (int slot = 0; slot < 54; slot++) routes.put(slot, slot);
    }

    public String guiId() {
      return menuId;
    }

    public Map<Integer, ?> guiActions() {
      return routes;
    }

    public void guiPut(int slot, Runnable action) {
      routes.put(slot, action);
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }

    int original(int slot) {
      Object route = routes.get(slot);
      if (route instanceof Runnable action) {
        action.run();
        return -1;
      }
      return route instanceof Integer value ? value : -1;
    }
  }

  private static final class ConditionMenu extends RoutedMenu {
    final String field;

    ConditionMenu(String field) {
      this.field = field;
      menuId = "wizard.condition-title";
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }
  }

  private static final class ActionMenu extends RoutedMenu {

    ActionMenu() {
      menuId = "wizard.action-title";
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }
  }

  private static final class ChoiceMenu extends RoutedMenu {
    final String field;
    final int page;
    final List<String> values;

    ChoiceMenu(String field, int page, List<String> values) {
      this.field = field;
      this.page = page;
      this.values = List.copyOf(values);
      menuId = field.equals("sections") ? "wizard.sections-title" : "wizard.fields." + field;
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }
  }

  private static final class EditorMenu implements dev.lovelace.loveessential.gui.GuiLayout {
    String menuId;

    public String guiId() {
      return menuId;
    }

    public Map<Integer, ?> guiActions() {
      return actions;
    }

    public void guiPut(int slot, Runnable action) {
      actions.put(slot, click -> action.run());
    }

    Inventory inventory;
    final NpcDraft expected;
    final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();

    EditorMenu(NpcDraft expected) {
      this.expected = expected;
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
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
    NpcDraft lastSaved;
    CompletableFuture<Long> autosave = CompletableFuture.completedFuture(0L), rawSave = autosave;
    final Deque<Route> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    Working(NpcDraft d, long r) {
      draft = d;
      revision = r;
      lastSaved = r == 0 ? null : d;
      autosave = CompletableFuture.completedFuture(r);
      rawSave = autosave;
    }
  }

  private record Prompt(
      long expiry,
      BiConsumer<Player, String> consumer,
      String token,
      Component context,
      Component instruction,
      String example,
      String current,
      Runnable back) {}

  private static final class Menu extends RoutedMenu {
    final int step;

    Menu(int step) {
      this.step = step;
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void guiInventory(Inventory value) {
      inventory = value;
    }
  }

  private static final String[][] FIELDS = {
    {"name", "type", "spawn"},
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
      "activation-range",
      "follow-player",
      "follow-distance",
      "stuck-ticks"
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

  private static final int LAST_STEP = FIELDS.length - 1;

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

  public void cancelInput(Player p) {
    prompts.remove(p.getUniqueId());
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

  public boolean editing(Player p) {
    return sessions.containsKey(p.getUniqueId());
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

  public boolean cancelPrompt(Player p, String token) {
    var prompt = prompts.get(p.getUniqueId());
    if (prompt == null || !prompt.token().equals(token)) return false;
    input(p, "cancel");
    return true;
  }

  public void input(Player p, String value) {
    var prompt = prompts.remove(p.getUniqueId());
    if (prompt == null || prompt.expiry() < System.currentTimeMillis()) {
      lang.send(p, "input.expired");
      return;
    }
    if (!permitted(p)) return;
    try {
      if (value.equalsIgnoreCase("cancel") || value.equalsIgnoreCase("отмена")) {
        lang.send(p, "input.cancelled");
        prompt.back().run();
        return;
      }
      prompt.consumer().accept(p, value);
      if (sessions.containsKey(p.getUniqueId()) && !prompts.containsKey(p.getUniqueId()))
        prompt.back().run();
    } catch (RuntimeException error) {
      lang.send(p, lang.contains(error.getMessage()) ? error.getMessage() : "errors.input");
      if (sessions.containsKey(p.getUniqueId()) && permitted(p)) {
        prompts.putIfAbsent(p.getUniqueId(), prompt);
        sendPrompt(p, prompts.get(p.getUniqueId()));
      }
    }
  }

  private void sendPrompt(Player p, Prompt prompt) {
    ChatPrompt.send(
        p,
        lang,
        prompt.token(),
        prompt.context(),
        prompt.instruction(),
        prompt.example(),
        prompt.current());
  }

  private void ask(Player p, String key, BiConsumer<Player, String> consumer) {
    var w = require(p);
    String exampleKey = "input.examples." + key.replace('.', '-');
    ask(
        p,
        lang.render(
            "input.npc",
            Map.of(
                "id",
                npcs.number(w.draft.npc().id()) == 0
                    ? lang.text("wizard.new-npc")
                    : "" + npcs.number(w.draft.npc().id()))),
        lang.render(key),
        lang.contains(exampleKey) ? lang.text(exampleKey) : "",
        "",
        consumer);
  }

  private void ask(
      Player p,
      Component context,
      Component instruction,
      String example,
      String current,
      BiConsumer<Player, String> consumer) {
    ask(p, context, instruction, example, current, consumer, () -> show(p));
  }

  private void ask(
      Player p,
      Component context,
      Component instruction,
      String example,
      String current,
      BiConsumer<Player, String> consumer,
      Runnable back) {
    require(p);
    p.closeInventory();
    var prompt =
        new Prompt(
            System.currentTimeMillis() + 180000,
            consumer,
            UUID.randomUUID().toString(),
            context,
            instruction,
            example,
            current,
            back);
    prompts.put(p.getUniqueId(), prompt);
    sendPrompt(p, prompt);
  }

  private String fieldValue(Working w, String key) {
    return w.draft
        .npc()
        .settings()
        .getOrDefault(
            key,
            switch (key) {
              case "id" -> w.draft.npc().id();
              case "name" ->
                  w.draft
                      .texts()
                      .getOrDefault(w.draft.npc().nameKey(), lang.text(w.draft.npc().nameKey()));
              case "type" -> w.draft.npc().entityType();
              case "spawn" ->
                  String.format(
                      java.util.Locale.ROOT,
                      "%.1f / %.1f / %.1f",
                      w.draft.npc().spawn().x(),
                      w.draft.npc().spawn().y(),
                      w.draft.npc().spawn().z());
              case "actions" ->
                  PlainTextComponentSerializer.plainText()
                      .serialize(
                          lang.render(
                              "npc-editor.actions-summary",
                              Map.of("count", "" + w.draft.npc().actions().size())));
              case "visibility", "access" ->
                  gson.toJson(
                      key.equals("visibility")
                          ? w.draft.npc().visibility()
                          : w.draft.npc().access());
              case "point-mode" -> w.draft.npc().route().mode().name();
              case "enabled" -> Boolean.toString(w.draft.npc().enabled());
              default ->
                  key.startsWith("point-")
                      ? PlainTextComponentSerializer.plainText()
                          .serialize(
                              lang.render(
                                  "npc-editor.route-summary",
                                  Map.of("count", "" + w.draft.npc().route().points().size())))
                      : key.startsWith("dialog-")
                          ? PlainTextComponentSerializer.plainText()
                              .serialize(
                                  lang.render(
                                      "npc-editor.dialog-summary",
                                      Map.of("count", "" + w.draft.npc().dialog().nodes().size())))
                          : NpcOptions.defaultValue(key);
            });
  }

  private void askField(Player p, String key) {
    var w = require(p);
    Component context =
        lang.rich(
            "input.npc-field",
            Map.of(
                "npc",
                Component.text(
                    npcs.number(w.draft.npc().id()) == 0
                        ? lang.text("wizard.new-npc")
                        : "№" + npcs.number(w.draft.npc().id())),
                "field",
                lang.render("wizard.fields." + key)));
    String example =
        lang.contains("npc-help.examples." + key)
            ? lang.text("npc-help.examples." + key)
            : fieldValue(w, key);
    ask(
        p,
        context,
        lang.render("npc-help.fields." + key),
        example,
        fieldValue(w, key),
        (who, v) -> set(who, key, v));
  }

  public void set(Player p, String key, String value) {
    if (Set.of("skin", "follow-player", "interaction-sound").contains(key)
        && (value.equals("-") || value.equals("NONE"))) value = "";
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
                .text(textKey, dev.lovelace.loveessential.tab.TabSyntax.legacy(value));
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

  private boolean waitAutosave(Player p, Working w, Runnable next) {
    if (w.autosave.isDone()) return false;
    w.autosave.whenComplete(
        (value, error) ->
            main.execute(
                () -> {
                  if (sessions.get(p.getUniqueId()) != w || !p.isOnline()) return;
                  if (error != null) lang.send(p, "errors.storage");
                  else next.run();
                }));
    return true;
  }

  private void autosave(Player p, Working w) {
    if (w.busy || !w.autosave.isDone() || Objects.equals(w.lastSaved, w.draft)) return;
    var snapshot = w.draft;
    var completion = new CompletableFuture<Long>();
    w.autosave = completion;
    w.rawSave =
        store.save(
            "draft", p.getUniqueId().toString(), w.revision, snapshot, p.getUniqueId().toString());
    w.rawSave.whenComplete(
        (revision, error) ->
            main.execute(
                () -> {
                  if (error == null) {
                    w.revision = revision;
                    w.lastSaved = snapshot;
                    completion.complete(revision);
                  } else {
                    completion.completeExceptionally(error);
                    plugin.getLogger().warning(lang.text("logs.draft-save-failed"));
                  }
                  if (error == null && sessions.get(p.getUniqueId()) == w && p.isOnline())
                    autosave(p, w);
                }));
  }

  private CompletableFuture<?> saveOnExit(UUID id, Working w) {
    if (w.busy) return CompletableFuture.completedFuture(null);
    var snapshot = w.draft;
    return w.rawSave
        .handle((revision, error) -> error == null ? revision : w.revision)
        .thenCompose(
            revision -> store.save("draft", id.toString(), revision, snapshot, id.toString()));
  }

  public void save(Player p) {
    var w = require(p);
    if (waitAutosave(p, w, () -> save(p))) return;
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
    if (waitAutosave(p, w, () -> discard(p))) return;
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
    if (waitAutosave(p, w, () -> publish(p))) return;
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
                      lang.send(p, "wizard.published", Map.of("id", "" + npcs.number(saved.id())));
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

  private EditorMenu editor(Player p, String title, Map<String, String> vars, Runnable back) {
    var m = new EditorMenu(require(p).draft);
    m.menuId = title;
    m.inventory = Bukkit.createInventory(m, 54, MenuStyle.title(lang, title, vars));
    MenuStyle.frame(m.inventory);
    editorButton(m, 51, Material.ARROW, "common.back", c -> back.run());
    editorButton(m, 52, Material.BARRIER, "gui.close", c -> p.closeInventory());
    return m;
  }

  private void editorButton(
      EditorMenu menu, int slot, Material icon, String key, Consumer<ClickType> action) {
    menu.inventory.setItem(slot, button(icon, key));
    menu.actions.put(slot, action);
  }

  private void confirm(Player p, String title, Runnable action, Runnable back) {
    var menu = editor(p, title, Map.of(), back);
    menu.inventory.setItem(
        22, MenuStyle.button(lang, Material.PAPER, title, "gui.confirm-warning"));
    editorButton(menu, 20, Material.LIME_CONCRETE, "gui.confirm", c -> action.run());
    editorButton(menu, 24, Material.RED_CONCRETE, "gui.cancel", c -> back.run());
    MenuStyle.open(p, menu.inventory);
  }

  private void routeList(Player p, int page) {
    var menu = editor(p, "npc-editor.route-title", Map.of(), () -> show(p));
    var points = require(p).draft.npc().route().points();
    int count = Math.max(0, Math.min(21, points.size() - page * 21));
    for (int i = 0; i < count; i++) {
      int index = page * 21 + i;
      var point = points.get(index);
      int slot = MenuStyle.slot(i, count);
      menu.inventory.setItem(
          slot,
          MenuStyle.item(
              Material.RAIL,
              lang.render("npc-editor.point", Map.of("number", "" + (index + 1))),
              List.of(
                  lang.render(
                      "npc-editor.position",
                      Map.of(
                          "x",
                          "" + (int) point.position().x(),
                          "y",
                          "" + (int) point.position().y(),
                          "z",
                          "" + (int) point.position().z())),
                  lang.render(
                      "npc-editor.point-details",
                      Map.of(
                          "wait",
                          "" + point.waitTicks(),
                          "speed",
                          "" + point.speed(),
                          "radius",
                          "" + point.radius())),
                  lang.render("gui.separator"),
                  lang.render("gui.click.open"))));
      menu.actions.put(slot, c -> routePoint(p, index));
    }
    if (points.isEmpty())
      menu.inventory.setItem(
          22,
          MenuStyle.button(
              lang, Material.BOOK, "npc-editor.empty-route", "npc-help.fields.point-add"));
    editorButton(
        menu,
        4,
        Material.RAIL,
        "wizard.fields.point-add",
        c -> {
          addPoint(p);
          routeList(p, page);
        });
    editorButton(
        menu,
        50,
        Material.BLAZE_ROD,
        "wizard.fields.point-tool",
        c -> {
          path(p, "tool", new String[0]);
        });
    if (page > 0)
      editorButton(menu, 36, Material.ARROW, "gui.previous", c -> routeList(p, page - 1));
    if ((page + 1) * 21 < points.size())
      editorButton(menu, 44, Material.ARROW, "common.next", c -> routeList(p, page + 1));
    MenuStyle.open(p, menu.inventory);
  }

  private void routePoint(Player p, int index) {
    var point = require(p).draft.npc().route().points().get(index);
    var menu =
        editor(
            p,
            "npc-editor.point-title",
            Map.of("number", "" + (index + 1)),
            () -> routeList(p, index / 21));
    editorButton(
        menu,
        19,
        Material.COMPASS,
        "npc-editor.move-point",
        c -> {
          path(p, "point", new String[] {"" + (index + 1), "position", "here"});
          routePoint(p, index);
        });
    String[] fields = {"wait", "speed", "radius"};
    String[] values = {"" + point.waitTicks(), "" + point.speed(), "" + point.radius()};
    for (int i = 0; i < fields.length; i++) {
      String field = fields[i], value = values[i];
      int slot = 21 + i * 2;
      menu.inventory.setItem(
          slot,
          MenuStyle.item(
              Material.COMPARATOR,
              lang.render("npc-editor.point-fields." + field),
              List.of(
                  lang.render("gui.value", Map.of("value", value)),
                  lang.render("npc-editor.point-help." + field),
                  lang.render("gui.click.edit"))));
      menu.actions.put(
          slot,
          c ->
              ask(
                  p,
                  lang.render("npc-editor.point-fields." + field),
                  lang.render("npc-editor.point-help." + field),
                  value,
                  value,
                  (who, input) -> {
                    double n = Double.parseDouble(input);
                    if (!Double.isFinite(n)
                        || n < 0
                        || field.equals("speed") && (n < .1 || n > 5)
                        || field.equals("radius") && (n < .1 || n > 8)
                        || field.equals("wait") && (n > 72000 || n != Math.floor(n)))
                      throw new IllegalArgumentException("errors.input");
                    path(
                        who,
                        "point",
                        new String[] {
                          "" + (index + 1), field, field.equals("wait") ? "" + (int) n : input
                        });
                  },
                  () -> routePoint(p, index)));
    }
    if (index > 0)
      editorButton(
          menu,
          29,
          Material.ARROW,
          "npc-editor.move-up",
          c -> {
            path(p, "move", new String[] {"" + (index + 1), "" + index});
            routePoint(p, index - 1);
          });
    if (index + 1 < require(p).draft.npc().route().points().size())
      editorButton(
          menu,
          31,
          Material.ARROW,
          "npc-editor.move-down",
          c -> {
            path(p, "move", new String[] {"" + (index + 1), "" + (index + 2)});
            routePoint(p, index + 1);
          });
    editorButton(
        menu,
        33,
        Material.BARRIER,
        "npc-editor.delete-point",
        c ->
            confirm(
                p,
                "npc-editor.delete-point",
                () -> {
                  path(p, "remove", new String[] {"" + (index + 1)});
                  routeList(p, 0);
                },
                () -> routePoint(p, index)));
    MenuStyle.open(p, menu.inventory);
  }

  private void actionList(Player p, int page) {
    var menu = editor(p, "npc-editor.actions-title", Map.of(), () -> showActions(p));
    var actions = require(p).draft.npc().actions();
    int count = Math.max(0, Math.min(21, actions.size() - page * 21));
    for (int i = 0; i < count; i++) {
      int index = page * 21 + i;
      var action = actions.get(index);
      int slot = MenuStyle.slot(i, count);
      String title = "wizard.action-types." + action.type().replace(':', '_');
      menu.inventory.setItem(
          slot,
          MenuStyle.item(
              Material.PAPER,
              lang.contains(title)
                  ? lang.render(title)
                  : lang.render("gui.label", Map.of("name", action.type())),
              List.of(
                  lang.render("npc-editor.action-order", Map.of("number", "" + (index + 1))),
                  lang.render("gui.value", Map.of("value", action.args().toString())),
                  lang.render("npc-editor.action-controls"))));
      menu.actions.put(
          slot,
          c -> {
            if (c.isRightClick())
              confirm(
                  p,
                  "gui.remove",
                  () -> {
                    var next = new ArrayList<>(require(p).draft.npc().actions());
                    next.remove(index);
                    set(p, "actions", gson.toJson(next));
                    actionList(p, 0);
                  },
                  () -> actionList(p, page));
            else if (index > 0) {
              var next = new ArrayList<>(require(p).draft.npc().actions());
              Collections.swap(next, index, index - 1);
              set(p, "actions", gson.toJson(next));
              actionList(p, page);
            }
          });
    }
    if (actions.isEmpty())
      menu.inventory.setItem(22, MenuStyle.button(lang, Material.BOOK, "npc-editor.empty-actions"));
    if (page > 0)
      editorButton(menu, 36, Material.ARROW, "gui.previous", c -> actionList(p, page - 1));
    if ((page + 1) * 21 < actions.size())
      editorButton(menu, 44, Material.ARROW, "common.next", c -> actionList(p, page + 1));
    MenuStyle.open(p, menu.inventory);
  }

  @EventHandler
  public void editorClick(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof EditorMenu menu)) return;
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player p)) return;
    Consumer<ClickType> action = menu.actions.get(event.getRawSlot());
    if (action == null) return;
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!p.isOnline()
                  || p.getOpenInventory().getTopInventory() == null
                  || p.getOpenInventory().getTopInventory().getHolder() != menu) return;
              try {
                if (require(p).draft != menu.expected) {
                  lang.send(p, "npc-editor.stale");
                  show(p);
                  return;
                }
                MenuStyle.action(p, () -> action.accept(event.getClick()));
              } catch (RuntimeException error) {
                lang.send(
                    p, lang.contains(error.getMessage()) ? error.getMessage() : "errors.input");
              }
            });
  }

  private void showConditions(Player p, String field) {
    require(p);
    var holder = new ConditionMenu(field);
    holder.inventory =
        Bukkit.createInventory(
            holder, 54, MenuStyle.title(lang, "wizard.condition-title", Map.of()));
    MenuStyle.frame(holder.inventory);
    holder.inventory.setItem(52, button(Material.BARRIER, "gui.close"));
    for (int i = 0; i < CONDITION_TYPES.size(); i++)
      holder.inventory.setItem(
          MenuStyle.slot(i, CONDITION_TYPES.size()),
          button(Material.TRIPWIRE_HOOK, "wizard.conditions." + CONDITION_TYPES.get(i)));
    holder.inventory.setItem(51, button(Material.ARROW, "common.back"));
    holder.inventory.setItem(50, button(Material.BARRIER, "wizard.clear-conditions"));
    MenuStyle.open(p, holder.inventory);
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
        "wizard.conditions." + type,
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
      String contextKey,
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
        lang.rich(
            "input.operation",
            Map.of(
                "operation",
                lang.render(contextKey),
                "parameter",
                lang.render("wizard.parameters." + key))),
        lang.render("wizard.parameters." + key),
        lang.contains("input.operation-examples." + contextKey + "." + key)
            ? lang.text("input.operation-examples." + contextKey + "." + key)
            : lang.text("input.parameter-examples." + key),
        "",
        (who, v) -> {
          values.put(key, v);
          collect(who, contextKey, keys, index + 1, values, done);
        });
  }

  private void showActions(Player p) {
    require(p);
    var holder = new ActionMenu();
    holder.inventory =
        Bukkit.createInventory(holder, 54, MenuStyle.title(lang, "wizard.action-title", Map.of()));
    MenuStyle.frame(holder.inventory);
    holder.inventory.setItem(52, button(Material.BARRIER, "gui.close"));
    for (int i = 0; i < ACTION_TYPES.size(); i++)
      holder.inventory.setItem(
          MenuStyle.slot(i, ACTION_TYPES.size()),
          button(Material.PAPER, "wizard.action-types." + ACTION_TYPES.get(i).replace(':', '_')));
    holder.inventory.setItem(51, button(Material.ARROW, "common.back"));
    holder.inventory.setItem(4, button(Material.WRITABLE_BOOK, "npc-editor.actions-title"));
    holder.inventory.setItem(50, button(Material.BARRIER, "wizard.clear-actions"));
    MenuStyle.open(p, holder.inventory);
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
        "wizard.action-types." + type.replace(':', '_'),
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
    if (!w.busy) autosave(p, w);
    int step = w.draft.step();
    var holder = new Menu(step);
    var inv =
        Bukkit.createInventory(
            holder,
            54,
            MenuStyle.title(
                lang,
                "gui.wizard-title",
                Map.of(
                    "step", Integer.toString(step + 1), "total", Integer.toString(LAST_STEP + 1))));
    holder.inventory = inv;
    MenuStyle.frame(inv);
    int discardSlot = MenuStyle.slot(FIELDS[step].length, FIELDS[step].length + 1);
    inv.setItem(discardSlot, button(Material.BARRIER, "common.discard"));
    holder.routes.put(discardSlot, 3);
    inv.setItem(6, button(Material.BOOK, "wizard.sections-title"));
    inv.setItem(0, button(Material.PLAYER_HEAD, "wizard.sections." + step));
    inv.setItem(50, button(Material.LIME_CONCRETE, "common.publish"));
    inv.setItem(51, button(Material.ARROW, "common.back"));
    inv.setItem(52, button(Material.BARRIER, "gui.close"));
    for (int i = 0; i < FIELDS[step].length; i++) {
      String key = FIELDS[step][i];
      var item = button(fieldIcon(key), "wizard.fields." + key);
      var meta = item.getItemMeta();
      String value = fieldValue(w, key);
      Component displayed =
          NpcValidator.BOOLEAN_OPTIONS.contains(key) || key.equals("enabled")
              ? lang.render(Boolean.parseBoolean(value) ? "gui.enabled" : "gui.disabled")
              : lang.contains("wizard.choices." + value)
                  ? lang.render("wizard.choices." + value)
                  : Component.text(value.isBlank() ? lang.text("input.empty") : value);
      var lore = new ArrayList<Component>();
      lore.add(lang.rich("wizard.value", Map.of("value", displayed)));
      if (lang.contains("npc-help.fields." + key))
        lore.addAll(MenuStyle.description(lang, "npc-help.fields." + key));
      lore.add(lang.render("gui.separator"));
      lore.add(
          lang.render(
              NpcValidator.BOOLEAN_OPTIONS.contains(key) || key.equals("enabled")
                  ? "gui.click.switch"
                  : "gui.click.edit"));
      if (NpcValidator.NUMERIC_OPTIONS.containsKey(key))
        lore.add(lang.render("settings-help.npc-number-shortcuts"));
      meta.lore(lore);
      meta.displayName(
          lang.render("wizard.fields." + key)
              .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
      meta.lore(
          meta.lore().stream()
              .map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
              .toList());
      item.setItemMeta(meta);
      int from = MenuStyle.slot(i, FIELDS[step].length);
      int to = MenuStyle.slot(i, FIELDS[step].length + 1);
      inv.setItem(to, item);
      holder.routes.put(to, from);
    }
    MenuStyle.open(p, inv);
  }

  private ItemStack button(Material material, String key) {
    return MenuStyle.button(lang, material, key, "gui.separator", "gui.click.open");
  }

  private Material fieldIcon(String key) {
    if (NpcValidator.BOOLEAN_OPTIONS.contains(key) || key.equals("enabled")) return Material.LEVER;
    if (NpcValidator.NUMERIC_OPTIONS.containsKey(key)) return Material.COMPARATOR;
    if (NpcOptions.EQUIPMENT.contains(key))
      return switch (key) {
        case "helmet" -> Material.IRON_HELMET;
        case "chestplate" -> Material.IRON_CHESTPLATE;
        case "leggings" -> Material.IRON_LEGGINGS;
        case "boots" -> Material.IRON_BOOTS;
        case "off-hand" -> Material.SHIELD;
        default -> Material.IRON_SWORD;
      };
    if (key.startsWith("point")) return Material.RAIL;
    if (key.startsWith("dialog") || key.equals("actions")) return Material.WRITABLE_BOOK;
    return switch (key) {
      case "skin", "type", "name" -> Material.PLAYER_HEAD;
      case "visibility", "access" -> Material.IRON_DOOR;
      case "interaction-sound" -> Material.NOTE_BLOCK;
      case "movement", "spawn", "follow-player" -> Material.COMPASS;
      default -> Material.NAME_TAG;
    };
  }

  private static int fieldIndex(int slot, int count) {
    for (int i = 0; i < count; i++) if (MenuStyle.slot(i, count) == slot) return i;
    return -1;
  }

  private void choices(Player p, String field, int page) {
    require(p);
    List<String> values =
        field.equals("point-mode")
            ? Arrays.stream(Route.Mode.values()).map(Enum::name).toList()
            : field.equals("sections")
                ? java.util.stream.IntStream.rangeClosed(0, LAST_STEP)
                    .mapToObj(Integer::toString)
                    .toList()
                : NpcOptions.choices(field, plugin.getConfig());
    var m = new ChoiceMenu(field, page, values);
    m.inventory =
        Bukkit.createInventory(
            m,
            54,
            MenuStyle.title(
                lang,
                field.equals("sections") ? "wizard.sections-title" : "wizard.fields." + field,
                Map.of()));
    MenuStyle.frame(m.inventory);
    int count = Math.min(21, values.size() - page * 21);
    for (int i = 0; i < count; i++) {
      String value = values.get(page * 21 + i);
      Material material =
          NpcOptions.EQUIPMENT.contains(field) ? Material.matchMaterial(value) : fieldIcon(field);
      if (material == null || material.isAir()) material = Material.BARRIER;
      String labelKey =
          field.equals("sections") ? "wizard.sections." + value : "wizard.choices." + value;
      var title =
          lang.contains(labelKey)
              ? lang.render(labelKey)
              : lang.render("gui.label", Map.of("name", value));
      if (NpcOptions.EQUIPMENT.contains(field) && !material.equals(Material.BARRIER))
        title = net.kyori.adventure.text.Component.translatable(material.translationKey());
      m.inventory.setItem(
          MenuStyle.slot(i, count),
          MenuStyle.item(
              material,
              title,
              List.of(lang.render("gui.separator"), lang.render("gui.click.select"))));
    }
    if (page > 0) m.inventory.setItem(36, button(Material.ARROW, "gui.previous"));
    if ((page + 1) * 21 < values.size())
      m.inventory.setItem(44, button(Material.ARROW, "common.next"));
    if (!field.equals("sections"))
      m.inventory.setItem(50, button(Material.WRITABLE_BOOK, "gui.manual-input"));
    m.inventory.setItem(51, button(Material.ARROW, "common.back"));
    m.inventory.setItem(52, button(Material.BARRIER, "gui.close"));
    MenuStyle.open(p, m.inventory);
  }

  @EventHandler
  public void choiceClick(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof ChoiceMenu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)) return;
    int clickedSlot = e.getRawSlot();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!p.isOnline()
                  || p.getOpenInventory().getTopInventory() == null
                  || p.getOpenInventory().getTopInventory().getHolder() != m) return;
              try {
                require(p);
                int slot = m.original(clickedSlot);
                if (slot == 52) {
                  p.closeInventory();
                  return;
                }
                if (slot == 51) {
                  show(p);
                  return;
                }
                if (slot == 36 && m.page > 0) {
                  choices(p, m.field, m.page - 1);
                  return;
                }
                if (slot == 44 && (m.page + 1) * 21 < m.values.size()) {
                  choices(p, m.field, m.page + 1);
                  return;
                }
                if (slot == 50 && !m.field.equals("sections")) {
                  if (m.field.equals("point-mode"))
                    ask(p, "wizard.prompt-mode", (who, v) -> path(who, "mode", new String[] {v}));
                  else askField(p, m.field);
                  return;
                }
                int index = fieldIndex(slot, Math.min(21, m.values.size() - m.page * 21));
                if (index < 0) return;
                String value = m.values.get(m.page * 21 + index);
                if (m.field.equals("sections")) {
                  page(p, Integer.parseInt(value));
                  return;
                }
                if (m.field.equals("point-mode")) path(p, "mode", new String[] {value});
                else set(p, m.field, value);
                show(p);
              } catch (RuntimeException ex) {
                lang.send(p, lang.contains(ex.getMessage()) ? ex.getMessage() : "errors.input");
              }
            });
  }

  @EventHandler
  public void conditionClick(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof ConditionMenu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)) return;
    int clickedSlot = e.getRawSlot();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!p.isOnline()
                  || p.getOpenInventory().getTopInventory() == null
                  || p.getOpenInventory().getTopInventory().getHolder() != m) return;
              try {
                require(p);
                int slot = m.original(clickedSlot);
                if (slot == 52) {
                  p.closeInventory();
                  return;
                }
                if (slot == 51) {
                  show(p);
                  return;
                }
                if (slot == 50) {
                  set(p, m.field, gson.toJson(Condition.always()));
                  show(p);
                  return;
                }
                int i = fieldIndex(slot, CONDITION_TYPES.size());
                if (i >= 0 && i < CONDITION_TYPES.size()) {
                  buildCondition(p, m.field, CONDITION_TYPES.get(i));
                  if (!prompts.containsKey(p.getUniqueId())) show(p);
                }
              } catch (RuntimeException ex) {
                lang.send(p, "errors.input");
              }
            });
  }

  @EventHandler
  public void actionClick(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof ActionMenu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)) return;
    int clickedSlot = e.getRawSlot();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!p.isOnline()
                  || p.getOpenInventory().getTopInventory() == null
                  || p.getOpenInventory().getTopInventory().getHolder() != m) return;
              try {
                require(p);
                int slot = m.original(clickedSlot);
                if (slot == 52) {
                  p.closeInventory();
                  return;
                }
                if (slot == 51) {
                  show(p);
                  return;
                }
                if (slot == 4) {
                  actionList(p, 0);
                  return;
                }
                if (slot == 50) {
                  confirm(
                      p,
                      "wizard.clear-actions",
                      () -> {
                        set(p, "actions", "[]");
                        show(p);
                      },
                      () -> showActions(p));
                  return;
                }
                int i = fieldIndex(slot, ACTION_TYPES.size());
                if (i >= 0 && i < ACTION_TYPES.size()) {
                  buildAction(p, ACTION_TYPES.get(i));
                  if (!prompts.containsKey(p.getUniqueId())) show(p);
                }
              } catch (RuntimeException ex) {
                lang.send(p, "errors.input");
              }
            });
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() < 0 || e.getRawSlot() >= 54)
      return;
    int clickedSlot = e.getRawSlot();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!p.isOnline()
                  || p.getOpenInventory().getTopInventory() == null
                  || p.getOpenInventory().getTopInventory().getHolder() != menu) return;
              try {
                require(p);
                int slot = menu.original(clickedSlot);
                if (slot == 52) {
                  p.closeInventory();
                  return;
                }
                if (slot == 51) {
                  Bukkit.dispatchCommand(p, "loveessential:loveessential npc");
                  return;
                }
                if (slot == 6) {
                  choices(p, "sections", 0);
                  return;
                }
                if (slot == 36) {
                  page(p, menu.step - 1);
                  return;
                }
                if (slot == 44) {
                  page(p, menu.step + 1);
                  return;
                }
                if (slot == 2) {
                  save(p);
                  return;
                }
                if (slot == 3) {
                  confirm(p, "common.discard", () -> discard(p), () -> show(p));
                  return;
                }
                if (slot == 5) {
                  preview(p);
                  return;
                }
                if (slot == 50) {
                  publish(p);
                  return;
                }

                int index = fieldIndex(slot, FIELDS[menu.step].length);
                if (index < 0 || index >= FIELDS[menu.step].length) return;
                String key = FIELDS[menu.step][index];
                if (NpcValidator.BOOLEAN_OPTIONS.contains(key) || key.equals("enabled")) {
                  var n = require(p).draft.npc();
                  boolean value =
                      key.equals("enabled")
                          ? n.enabled()
                          : n.flag(key, Boolean.parseBoolean(NpcOptions.defaultValue(key)));
                  set(p, key, Boolean.toString(!value));
                  show(p);
                  return;
                }
                if (NpcValidator.NUMERIC_OPTIONS.containsKey(key) && e.getClick().isRightClick()) {
                  double[] bounds = NpcValidator.NUMERIC_OPTIONS.get(key);
                  double step =
                      Set.of("speed", "scale", "interaction-volume", "interaction-pitch")
                              .contains(key)
                          ? .1
                          : key.equals("cooldown-ms") ? 100 : 1;
                  double current = Double.parseDouble(fieldValue(require(p), key));
                  double next =
                      Math.max(
                          bounds[0],
                          Math.min(
                              bounds[1], current + (e.getClick().isShiftClick() ? -step : step)));
                  set(
                      p,
                      key,
                      java.math.BigDecimal.valueOf(next)
                          .setScale(2, java.math.RoundingMode.HALF_UP)
                          .stripTrailingZeros()
                          .toPlainString());
                  show(p);
                  return;
                }
                if (!NpcOptions.choices(key, plugin.getConfig()).isEmpty()) {
                  choices(p, key, 0);
                  return;
                }
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
                  case "point-mode" -> choices(p, "point-mode", 0);
                  case "point-edit" -> routeList(p, 0);
                  case "dialog-node" ->
                      ask(
                          p,
                          "wizard.prompt-node",
                          (who, v) -> dialog(who, "node", v.split(" ", 2)));
                  case "dialog-answer" ->
                      ask(
                          p,
                          "wizard.prompt-answer",
                          (who, v) -> dialog(who, "answer", v.split(" ", 4)));
                  case "dialog-start" ->
                      ask(
                          p,
                          "wizard.prompt-start",
                          (who, v) -> dialog(who, "start", new String[] {v}));
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
                  default -> askField(p, key);
                }
              } catch (RuntimeException ex) {
                lang.send(p, lang.contains(ex.getMessage()) ? ex.getMessage() : "errors.input");
              }
            });
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu
        || e.getView().getTopInventory().getHolder() instanceof ConditionMenu
        || e.getView().getTopInventory().getHolder() instanceof ActionMenu
        || e.getView().getTopInventory().getHolder() instanceof ChoiceMenu
        || e.getView().getTopInventory().getHolder() instanceof EditorMenu) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void chat(AsyncChatEvent e) {
    var id = e.getPlayer().getUniqueId();
    var prompt = prompts.get(id);
    if (prompt == null) return;
    if (prompt.expiry() < System.currentTimeMillis()) {
      prompts.remove(id, prompt);
      e.setCancelled(true);
      main.execute(() -> lang.send(e.getPlayer(), "input.expired"));
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
    if (w != null) saveOnExit(id, w);
  }

  public void close() {
    HandlerList.unregisterAll(this);
    prompts.clear();
    var saves =
        sessions.entrySet().stream()
            .map(entry -> saveOnExit(entry.getKey(), entry.getValue()))
            .toArray(CompletableFuture[]::new);
    try {
      CompletableFuture.allOf(saves).get(5, TimeUnit.SECONDS);
    } catch (Exception error) {
      plugin.getLogger().warning(lang.text("logs.draft-save-failed"));
    }
    sessions.clear();
    createIds.clear();
    loading.clear();
  }
}
