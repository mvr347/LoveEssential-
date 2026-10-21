package dev.lovelace.loveessential.dialog;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.*;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;

/** Server-bound, expiring, single-use native Paper dialogs. Transport is replaceable in tests. */
public final class NativeDialogs implements Listener, AutoCloseable {
  public record Field(String id, Component label, String initial, int limit, List<String> choices) {
    public Field {
      choices = List.copyOf(choices);
    }

    public static Field text(String id, Component label, String initial, int limit) {
      return new Field(id, label, initial, limit, List.of());
    }

    public static Field select(String id, Component label, String initial, List<String> choices) {
      return new Field(id, label, initial, 128, choices);
    }
  }

  public record Button(String id, Component label, Consumer<Map<String, String>> action) {}

  public record Form(
      UUID token, Component title, Component body, List<Field> fields, List<Button> buttons) {
    public Form {
      fields = List.copyOf(fields);
      buttons = List.copyOf(buttons);
    }
  }

  private record Session(Form form, Predicate<Player> allowed, long expires) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final Lang lang;
  private final LoveBridge love;
  private final Map<UUID, Session> sessions =
      com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
          .maximumSize(1024)
          .expireAfterWrite(Duration.ofMinutes(3))
          .scheduler(com.github.benmanes.caffeine.cache.Scheduler.systemScheduler())
          .<UUID, Session>build()
          .asMap();
  private BiConsumer<Player, Form> presenter = this::paper;
  private Consumer<Player> beforeShow = p -> {};

  public void beforeShow(Consumer<Player> hook) {
    beforeShow = hook;
  }

  public void cancel(Player p) {
    sessions.remove(p.getUniqueId());
  }

  public NativeDialogs(LoveEssentialPlugin plugin, MainThread main, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.love = love;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public void presenter(BiConsumer<Player, Form> value) {
    MainThread.require();
    presenter = Objects.requireNonNull(value);
  }

  public void show(
      Player p,
      String title,
      String body,
      List<Field> fields,
      List<Button> buttons,
      Predicate<Player> allowed) {
    show(p, lang.render(title), lang.render(body), fields, buttons, allowed);
  }

  public void show(
      Player p,
      Component title,
      Component body,
      List<Field> fields,
      List<Button> buttons,
      Predicate<Player> allowed) {
    MainThread.require();
    if (!plugin.ready() || !love.authenticated(p) || !allowed.test(p))
      throw new IllegalArgumentException("errors.permission");
    if (fields.size() > 12 || buttons.size() > 24)
      throw new IllegalArgumentException("errors.input");
    beforeShow.accept(p);
    var form = new Form(UUID.randomUUID(), title, body, fields, buttons);
    sessions.put(p.getUniqueId(), new Session(form, allowed, System.currentTimeMillis() + 180000));
    p.closeInventory();
    try {
      presenter.accept(p, form);
    } catch (RuntimeException e) {
      sessions.remove(p.getUniqueId());
      throw e;
    }
  }

  public boolean respond(Player p, UUID token, String button, Map<String, String> values) {
    MainThread.require();
    var s = sessions.get(p.getUniqueId());
    if (s == null || !s.form().token().equals(token)) return false;
    if (!plugin.ready()
        || !p.isOnline()
        || !love.authenticated(p)
        || !s.allowed().test(p)
        || s.expires() < System.currentTimeMillis()) {
      sessions.remove(p.getUniqueId());
      lang.send(p, "ui.expired");
      return false;
    }
    var b = s.form().buttons().stream().filter(v -> v.id().equals(button)).findFirst().orElse(null);
    if (b == null) return false;
    var clean = new HashMap<String, String>();
    for (var f : s.form().fields()) {
      String value = values.getOrDefault(f.id(), f.initial());
      if (value == null
          || value.length() > f.limit()
          || value.indexOf('\0') >= 0
          || !f.choices().isEmpty() && !f.choices().contains(value)) {
        lang.send(p, "errors.input");
        return false;
      }
      clean.put(f.id(), value);
    }
    sessions.remove(p.getUniqueId());
    try {
      dev.lovelace.loveessential.gui.MenuStyle.action(
          p, () -> b.action().accept(Map.copyOf(clean)));
    } catch (RuntimeException e) {
      error(p, e);
      if (plugin.ready()
          && p.isOnline()
          && love.authenticated(p)
          && s.allowed().test(p)
          && !sessions.containsKey(p.getUniqueId())) {
        var retry =
            s.form().fields().stream()
                .map(
                    f ->
                        new Field(
                            f.id(),
                            f.label(),
                            clean.getOrDefault(f.id(), f.initial()),
                            f.limit(),
                            f.choices()))
                .toList();
        show(p, s.form().title(), s.form().body(), retry, s.form().buttons(), s.allowed());
      }
    }
    return true;
  }

  public void error(Player p, Throwable e) {
    lang.error(p, e);
  }

  private void paper(Player p, Form form) {
    UUID owner = p.getUniqueId();
    var inputs = new ArrayList<DialogInput>();
    for (var f : form.fields()) {
      if (f.choices().isEmpty())
        inputs.add(DialogInput.text(f.id(), 300, f.label(), true, f.initial(), f.limit(), null));
      else
        inputs.add(
            DialogInput.singleOption(
                f.id(),
                300,
                f.choices().stream()
                    .map(
                        v ->
                            SingleOptionDialogInput.OptionEntry.create(
                                v,
                                lang.contains("ui.values." + v.toLowerCase(Locale.ROOT))
                                    ? lang.render("ui.values." + v.toLowerCase(Locale.ROOT))
                                    : Component.text(v),
                                v.equals(f.initial())))
                    .toList(),
                f.label(),
                true));
    }
    var buttons =
        form.buttons().stream()
            .map(
                b ->
                    ActionButton.create(
                        b.label(),
                        null,
                        150,
                        DialogAction.customClick(
                            (response, audience) -> {
                              if (!(audience instanceof Player actor)
                                  || !actor.getUniqueId().equals(owner)) return;
                              var values = new HashMap<String, String>();
                              for (var f : form.fields())
                                values.put(
                                    f.id(),
                                    Objects.requireNonNullElse(
                                        response.getText(f.id()), f.initial()));
                              main.execute(() -> respond(actor, form.token(), b.id(), values));
                            },
                            ClickCallback.Options.builder()
                                .uses(1)
                                .lifetime(Duration.ofMinutes(3))
                                .build())))
            .toList();
    var dialog =
        io.papermc.paper.dialog.Dialog.create(
            factory ->
                factory
                    .empty()
                    .base(
                        DialogBase.builder(form.title())
                            .canCloseWithEscape(true)
                            .pause(false)
                            .body(List.of(DialogBody.plainMessage(form.body())))
                            .inputs(inputs)
                            .build())
                    .type(DialogType.multiAction(buttons, null, 2)));
    p.showDialog(dialog);
  }

  public Field text(String id, String label, String initial, int limit) {
    return Field.text(id, lang.render(label), initial, limit);
  }

  public Field select(String id, String label, String initial, List<String> choices) {
    return Field.select(id, lang.render(label), initial, choices);
  }

  public Button button(String id, String label, Consumer<Map<String, String>> action) {
    return new Button(id, lang.render(label), action);
  }

  public void confirm(Player p, String body, Runnable yes, Runnable no, Predicate<Player> allowed) {
    show(
        p,
        "ui.confirm",
        body,
        List.of(),
        List.of(
            button("yes", "gui.confirm", v -> yes.run()),
            button("no", "gui.cancel", v -> no.run())),
        allowed);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    sessions.remove(e.getPlayer().getUniqueId());
  }

  @Override
  public void close() {
    sessions.clear();
    HandlerList.unregisterAll(this);
  }
}
