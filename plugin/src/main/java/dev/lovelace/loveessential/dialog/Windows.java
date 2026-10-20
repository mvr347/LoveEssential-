package dev.lovelace.loveessential.dialog;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.condition.ActionEngine;
import dev.lovelace.loveessential.content.ContentLibrary;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import dev.lovelace.loveessential.tab.Placeholders;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Persisted native dialog definitions; editors never expose console execution or code evaluation.
 */
public final class Windows {
  public record Input(String id, String labelKey, String initial, int limit) {}

  public record Answer(String id, String labelKey, String permission, Action action) {}

  public record Definition(
      String id,
      String titleKey,
      String bodyKey,
      String permission,
      List<Input> inputs,
      List<Answer> answers) {
    public Definition {
      inputs = List.copyOf(inputs);
      answers = List.copyOf(answers);
    }
  }

  public record Motd(String firstKey, String secondKey) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final SqliteStore store;
  private final Lang lang;
  private final LoveBridge love;
  private final NativeDialogs ui;
  private final ActionEngine actions;
  private final ContentLibrary texts;
  private final Placeholders placeholders;
  private final Map<String, SqliteStore.Document<Definition>> definitions =
      new ConcurrentHashMap<>();
  private SqliteStore.Document<Motd> motd;
  private volatile Component renderedMotd = Component.empty();

  public Windows(
      LoveEssentialPlugin plugin,
      MainThread main,
      SqliteStore store,
      Lang lang,
      LoveBridge love,
      NativeDialogs ui,
      ActionEngine actions,
      ContentLibrary texts,
      Placeholders placeholders) {
    this.plugin = plugin;
    this.main = main;
    this.store = store;
    this.lang = lang;
    this.love = love;
    this.ui = ui;
    this.actions = actions;
    this.texts = texts;
    this.placeholders = placeholders;
  }

  public CompletableFuture<Void> load() {
    return store
        .list("window", Definition.class)
        .thenCompose(
            saved ->
                main.call(
                    () -> {
                      saved.forEach(d -> definitions.put(d.id(), d));
                      return null;
                    }))
        .thenCompose(v -> store.get("motd", "default", Motd.class))
        .thenCompose(
            saved -> {
              if (saved.isPresent())
                return main.<Void>call(
                    () -> {
                      motd = saved.get();
                      refreshMotd();
                      return null;
                    });
              String[] lines = lang.text("motd.text").split("\\n|<newline>|<br>", 2);
              return saveMotd(lines[0], lines.length > 1 ? lines[1] : "", 0, "migration");
            });
  }

  public List<String> ids() {
    return definitions.keySet().stream().sorted().toList();
  }

  public Optional<SqliteStore.Document<Definition>> definition(String id) {
    return Optional.ofNullable(definitions.get(id));
  }

  private boolean admin(Player p) {
    return plugin.module("dialogs") && p.hasPermission("loveessential.dialogs.admin");
  }

  private boolean permitted(Player p, Definition d) {
    return plugin.allows("dialogs", p)
        && love.authenticated(p)
        && (d.permission().isBlank() || p.hasPermission(d.permission()));
  }

  public void open(Player p, String id) {
    var d = definitions.get(id);
    if (d == null) throw new IllegalArgumentException("errors.not-found");
    var def = d.value();
    var buttons = new ArrayList<NativeDialogs.Button>();
    for (var a : def.answers())
      if (a.permission().isBlank() || p.hasPermission(a.permission()))
        buttons.add(
            new NativeDialogs.Button(
                a.id(),
                placeholders.render(a.labelKey(), p, p),
                values -> {
                  if (!permitted(p, def)
                      || definitions.get(id) != d
                      || !a.permission().isBlank() && !p.hasPermission(a.permission()))
                    throw new IllegalArgumentException("errors.access");
                  if (a.action().type().equals("close")) return;
                  var args = new HashMap<String, String>();
                  a.action()
                      .args()
                      .forEach(
                          (k, value) -> {
                            String v = value;
                            for (var e : values.entrySet())
                              v = v.replace("${" + e.getKey() + "}", e.getValue());
                            args.put(k, v);
                          });
                  actions.execute(p, List.of(new Action(a.action().type(), args)));
                }));
    ui.show(
        p,
        placeholders.render(def.titleKey(), p, p),
        placeholders.render(def.bodyKey(), p, p),
        def.inputs().stream()
            .map(
                i ->
                    NativeDialogs.Field.text(
                        i.id(), placeholders.render(i.labelKey(), p, p), i.initial(), i.limit()))
            .toList(),
        buttons,
        who -> definitions.get(id) == d && permitted(who, def));
  }

  public CompletableFuture<Void> save(
      Definition d, long revision, String actor, Map<String, String> values) {
    ContentLibrary.id(d.id());
    if (d.answers().size() > 12
        || d.inputs().size() > 8
        || revision == 0
            && definitions.size() >= plugin.getConfig().getInt("dialogs.max-definitions", 256))
      throw new IllegalArgumentException("windows.limit");
    var ids = new HashSet<String>();
    for (var i : d.inputs())
      if (!i.id().matches("[a-z0-9_]{1,32}")
          || !ids.add(i.id())
          || i.limit() < 1
          || i.limit() > 2048
          || i.initial().length() > i.limit()) throw new IllegalArgumentException("errors.input");
    ids.clear();
    for (var a : d.answers())
      if (!ids.add(a.id())
          || !a.action().type().equals("close") && !actions.known(a.action().type()))
        throw new IllegalArgumentException("errors.input");
    return texts
        .texts(values)
        .thenCompose(v -> store.save("window", d.id(), revision, d, actor))
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      definitions.put(d.id(), new SqliteStore.Document<>(d.id(), rev, d));
                      return null;
                    }));
  }

  public CompletableFuture<Void> saveMotd(
      String first, String second, long revision, String actor) {
    if (first.length() > 2048
        || second.length() > 2048
        || first.contains("\n")
        || second.contains("\n")) throw new IllegalArgumentException("errors.input");
    String prefix = "motd-text." + UUID.randomUUID();
    var value = new Motd(prefix + ".first", prefix + ".second");
    return texts
        .texts(Map.of(value.firstKey(), first, value.secondKey(), second))
        .thenCompose(v -> store.save("motd", "default", revision, value, actor))
        .thenCompose(
            rev ->
                main.call(
                    () -> {
                      motd = new SqliteStore.Document<>("default", rev, value);
                      refreshMotd();
                      return null;
                    }));
  }

  public void refreshMotd() {
    if (motd != null)
      renderedMotd =
          lang.render(motd.value().firstKey())
              .append(Component.newline())
              .append(lang.render(motd.value().secondKey()));
  }

  public Component motd() {
    return renderedMotd;
  }

  public SqliteStore.Document<Motd> motdDefinition() {
    return motd;
  }

  public void motdEditor(Player p) {
    if (!p.hasPermission("loveessential.admin"))
      throw new IllegalArgumentException("errors.permission");
    var d = motd;
    motdEditor(p, d, lang.text(d.value().firstKey()), lang.text(d.value().secondKey()));
  }

  private void motdEditor(Player p, SqliteStore.Document<Motd> d, String first, String second) {
    ui.show(
        p,
        lang.render("windows.motd-title"),
        lang.render("windows.motd-help").append(Component.newline()).append(motd()),
        List.of(
            ui.text("first", "windows.motd-first", first, 2048),
            ui.text("second", "windows.motd-second", second, 2048)),
        List.of(
            ui.button(
                "save",
                "content.save",
                v ->
                    done(
                        p,
                        saveMotd(
                            v.get("first"),
                            v.get("second"),
                            d.revision(),
                            p.getUniqueId().toString()),
                        () -> motdEditor(p))),
            ui.button(
                "preview",
                "windows.preview",
                v ->
                    ui.show(
                        p,
                        lang.render("windows.preview"),
                        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize(v.get("first"))
                            .append(Component.newline())
                            .append(
                                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                    .deserialize(v.get("second"))),
                        List.of(),
                        List.of(
                            ui.button(
                                "back",
                                "common.back",
                                ignored -> motdEditor(p, d, v.get("first"), v.get("second")))),
                        who -> who.hasPermission("loveessential.admin")))),
        who -> who.hasPermission("loveessential.admin"));
  }

  private void done(Player p, CompletableFuture<?> f, Runnable next) {
    f.whenComplete(
        (v, error) ->
            main.execute(
                () -> {
                  if (!p.isOnline()) return;
                  if (error != null) ui.error(p, error);
                  else {
                    lang.send(p, "chat.saved");
                    if (plugin.ready()) next.run();
                  }
                }));
  }

  public void command(Player p, String[] args) {
    if (!plugin.module("dialogs")) throw new IllegalArgumentException("errors.module-disabled");
    if (args.length == 1 && args[0].equals("create"))
      args =
          new String[] {
            "create", "dialog_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
          };
    if (args.length == 0 || args[0].equals("list")) {
      menu(p, 0);
      return;
    }
    if (args.length == 1) {
      open(p, args[0]);
      return;
    }
    if (args.length != 2) throw new IllegalArgumentException("windows.usage");
    String id = args[1];
    ContentLibrary.id(id);
    if (args[0].equals("open")) {
      open(p, id);
      return;
    }
    if (!admin(p)) throw new IllegalArgumentException("errors.permission");
    switch (args[0]) {
      case "create" -> {
        if (definitions.containsKey(id)) throw new IllegalArgumentException("errors.conflict");
        String prefix = "window-text." + id + "." + UUID.randomUUID();
        done(
            p,
            save(
                new Definition(
                    id,
                    prefix + ".title",
                    prefix + ".body",
                    "",
                    List.of(),
                    List.of(new Answer("close", "ui.close", "", new Action("close", Map.of())))),
                0,
                p.getUniqueId().toString(),
                Map.of(
                    prefix + ".title",
                    lang.text("windows.default-title"),
                    prefix + ".body",
                    lang.text("windows.default-body"))),
            () -> edit(p, id));
      }
      case "edit" -> edit(p, id);
      case "delete" -> {
        var d = definitions.get(id);
        if (d == null) throw new IllegalArgumentException("errors.not-found");
        ui.confirm(
            p,
            "windows.delete-confirm",
            () ->
                done(
                    p,
                    store
                        .delete("window", id, d.revision(), p.getUniqueId().toString())
                        .thenCompose(
                            v ->
                                main.call(
                                    () -> {
                                      definitions.remove(id);
                                      return null;
                                    })),
                    () -> menu(p, 0)),
            () -> edit(p, id),
            this::admin);
      }
      default -> throw new IllegalArgumentException("windows.usage");
    }
  }

  public List<String> suggestions(Player p, String[] args) {
    if (!plugin.module("dialogs")) return List.of();
    if (args.length == 1) {
      var out = new ArrayList<>(List.of("list", "open"));
      if (admin(p)) out.addAll(List.of("create", "edit", "delete"));
      out.addAll(ids());
      return out;
    }
    if (args.length == 2 && !args[0].equals("create")) return ids();
    return List.of();
  }

  public void menu(Player p, int page) {
    var ids =
        ids().stream().filter(id -> admin(p) || permitted(p, definitions.get(id).value())).toList();
    int start = page * 16;
    var buttons = new ArrayList<NativeDialogs.Button>();
    for (String id : ids.subList(Math.min(start, ids.size()), Math.min(start + 16, ids.size())))
      buttons.add(
          new NativeDialogs.Button(
              id,
              lang.render("windows.entry", Map.of("id", id)),
              v -> {
                if (admin(p)) edit(p, id);
                else open(p, id);
              }));
    if (admin(p))
      buttons.add(ui.button("create", "content.create", v -> command(p, new String[] {"create"})));
    if (page > 0) buttons.add(ui.button("prev", "gui.previous", v -> menu(p, page - 1)));
    if (start + 16 < ids.size())
      buttons.add(ui.button("next", "common.next", v -> menu(p, page + 1)));
    ui.show(
        p, "windows.title", "windows.help", List.of(), buttons, who -> plugin.module("dialogs"));
  }

  public void edit(Player p, String id) {
    var d = definitions.get(id);
    if (d == null) throw new IllegalArgumentException("errors.not-found");
    var def = d.value();
    ui.show(
        p,
        "windows.edit",
        "windows.edit-help",
        List.of(
            ui.text("title", "windows.heading", lang.text(def.titleKey()), 256),
            ui.text("body", "windows.body", lang.text(def.bodyKey()), 2048),
            ui.text("permission", "worlds.permission", def.permission(), 128)),
        List.of(
            ui.button(
                "save",
                "content.save",
                v -> {
                  String prefix = "window-text." + id + "." + UUID.randomUUID();
                  done(
                      p,
                      save(
                          new Definition(
                              id,
                              prefix + ".title",
                              prefix + ".body",
                              v.get("permission"),
                              def.inputs(),
                              def.answers()),
                          d.revision(),
                          p.getUniqueId().toString(),
                          Map.of(
                              prefix + ".title", v.get("title"), prefix + ".body", v.get("body"))),
                      () -> edit(p, id));
                }),
            ui.button("answers", "windows.answers", v -> answers(p, d)),
            ui.button("inputs", "windows.inputs", v -> input(p, d)),
            ui.button("preview", "windows.preview", v -> open(p, id)),
            ui.button("delete", "ui.delete", v -> command(p, new String[] {"delete", id})),
            ui.button("back", "common.back", v -> menu(p, 0))),
        this::admin);
  }

  private void answers(Player p, SqliteStore.Document<Definition> d) {
    var buttons = new ArrayList<NativeDialogs.Button>();
    for (int i = 0; i < d.value().answers().size(); i++) {
      int index = i;
      var a = d.value().answers().get(i);
      buttons.add(
          new NativeDialogs.Button(a.id(), lang.render(a.labelKey()), v -> answer(p, d, index)));
    }
    buttons.add(ui.button("add", "windows.add-answer", v -> answer(p, d, -1)));
    buttons.add(ui.button("back", "common.back", v -> edit(p, d.id())));
    ui.show(p, "windows.answers", "windows.answers-help", List.of(), buttons, this::admin);
  }

  private void answer(Player p, SqliteStore.Document<Definition> d, int index) {
    var def = d.value();
    var old =
        index < 0
            ? new Answer(
                UUID.randomUUID().toString(), "ui.close", "", new Action("close", Map.of()))
            : def.answers().get(index);
    String type = old.action().type();
    String key = argumentKey(type);
    String arg = old.action().args().getOrDefault(key, "");
    if (type.equals("message") && lang.contains(arg)) arg = lang.text(arg);
    var buttons = new ArrayList<NativeDialogs.Button>();
    buttons.add(
        ui.button(
            "save",
            "content.save",
            v -> {
              String prefix = "window-text." + d.id() + "." + UUID.randomUUID();
              var values = new HashMap<String, String>();
              values.put(prefix + ".answer", v.get("label"));
              String t = v.get("action"), argument = v.get("argument");
              if (t.equals("message")) {
                values.put(prefix + ".message", argument);
                argument = prefix + ".message";
              }
              var a =
                  new Answer(
                      old.id(),
                      prefix + ".answer",
                      v.get("permission"),
                      new Action(
                          t,
                          Set.of("close", "starter", "recipes").contains(t)
                              ? Map.of()
                              : Map.of(argumentKey(t), argument)));
              var list = new ArrayList<>(def.answers());
              if (index < 0) list.add(a);
              else list.set(index, a);
              done(
                  p,
                  save(
                      new Definition(
                          def.id(),
                          def.titleKey(),
                          def.bodyKey(),
                          def.permission(),
                          def.inputs(),
                          list),
                      d.revision(),
                      p.getUniqueId().toString(),
                      values),
                  () -> edit(p, d.id()));
            }));
    if (index >= 0)
      buttons.add(
          ui.button(
              "delete",
              "ui.delete",
              v -> {
                var list = new ArrayList<>(def.answers());
                list.remove(index);
                done(
                    p,
                    save(
                        new Definition(
                            def.id(),
                            def.titleKey(),
                            def.bodyKey(),
                            def.permission(),
                            def.inputs(),
                            list),
                        d.revision(),
                        p.getUniqueId().toString(),
                        Map.of()),
                    () -> edit(p, d.id()));
              }));
    buttons.add(ui.button("back", "common.back", v -> answers(p, d)));
    ui.show(
        p,
        "windows.answer",
        "windows.answer-help",
        List.of(
            ui.text("label", "windows.label", lang.text(old.labelKey()), 256),
            ui.select(
                "action",
                "scenarios.action",
                type,
                List.of(
                    "close", "message", "command", "dialog", "world", "sound", "starter",
                    "recipes")),
            ui.text("argument", "scenarios.argument", arg, 2048),
            ui.text("permission", "worlds.permission", old.permission(), 128)),
        buttons,
        this::admin);
  }

  private String argumentKey(String type) {
    return switch (type) {
      case "message" -> "key";
      case "world", "dialog" -> "id";
      case "sound" -> "sound";
      default -> "value";
    };
  }

  private void input(Player p, SqliteStore.Document<Definition> d) {
    var def = d.value();
    ui.show(
        p,
        "windows.inputs",
        "windows.inputs-help",
        List.of(
            ui.text("id", "windows.input-id", "", 32),
            ui.text("label", "windows.label", "", 128),
            ui.text("initial", "windows.initial", "", 256),
            ui.text("limit", "windows.input-limit", "256", 4)),
        List.of(
            ui.button(
                "save",
                "windows.add-input",
                v -> {
                  String prefix = "window-text." + d.id() + "." + UUID.randomUUID();
                  var list = new ArrayList<>(def.inputs());
                  list.removeIf(i -> i.id().equals(v.get("id")));
                  list.add(
                      new Input(
                          v.get("id"),
                          prefix + ".label",
                          v.get("initial"),
                          Integer.parseInt(v.get("limit"))));
                  done(
                      p,
                      save(
                          new Definition(
                              def.id(),
                              def.titleKey(),
                              def.bodyKey(),
                              def.permission(),
                              list,
                              def.answers()),
                          d.revision(),
                          p.getUniqueId().toString(),
                          Map.of(prefix + ".label", v.get("label"))),
                      () -> edit(p, d.id()));
                }),
            ui.button(
                "remove",
                "windows.remove-input",
                v -> {
                  var list = new ArrayList<>(def.inputs());
                  list.removeIf(i -> i.id().equals(v.get("id")));
                  done(
                      p,
                      save(
                          new Definition(
                              def.id(),
                              def.titleKey(),
                              def.bodyKey(),
                              def.permission(),
                              list,
                              def.answers()),
                          d.revision(),
                          p.getUniqueId().toString(),
                          Map.of()),
                      () -> edit(p, d.id()));
                }),
            ui.button("back", "common.back", v -> edit(p, d.id()))),
        this::admin);
  }
}
