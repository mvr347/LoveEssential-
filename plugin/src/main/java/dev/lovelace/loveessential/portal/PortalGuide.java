package dev.lovelace.loveessential.portal;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.Position;
import dev.lovelace.loveessential.gui.*;
import dev.lovelace.loveessential.lang.Lang;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/** Token-checked chat controls; every step is saved with the draft before advancing. */
final class PortalGuide implements Listener, AutoCloseable {
  private static final List<String> STEPS =
      List.of("name", "first", "second", "action", "effects", "access", "review");
  private static final String COMMAND = "/loveessential:loveessential portal guide ";

  private record View(String token, long revision, boolean confirm, long expires) {}

  private record Input(String kind, String token, long revision, long expires) {}

  private final Map<UUID, View> views = new HashMap<>();
  private final ConcurrentHashMap<UUID, Input> inputs = new ConcurrentHashMap<>();
  private final Portals host;
  private final MainThread main;
  private final Lang lang;

  PortalGuide(Portals host, LoveEssentialPlugin plugin, MainThread main, Lang lang) {
    this.host = host;
    this.main = main;
    this.lang = lang;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  boolean active(Player p) {
    return views.containsKey(p.getUniqueId());
  }

  void open(Player p) {
    host.requireAdmin(p);
    leave(p);
    var d = host.draft(p);
    views.put(
        p.getUniqueId(), new View("", d.revision(), false, System.currentTimeMillis() + 600000));
    if (d.revision() == 0)
      host.adjust(
          p, edit -> {}, Math.clamp(d.value().step(), 0, 6), d.value().corners(), () -> render(p));
    else render(p);
  }

  private void cancelInput(Player p) {
    var old = inputs.remove(p.getUniqueId());
    if (old != null) ChatPrompt.release(p.getUniqueId(), old.token());
  }

  void leave(Player p) {
    views.remove(p.getUniqueId());
    cancelInput(p);
    host.cancelSelection(p);
  }

  private View view(Player p, boolean confirm) {
    cancelInput(p);
    host.cancelDialog(p);
    MenuStyle.forget(p);
    p.closeInventory();
    var v =
        new View(
            UUID.randomUUID().toString(),
            host.draft(p).revision(),
            confirm,
            System.currentTimeMillis() + 600000);
    views.put(p.getUniqueId(), v);
    ChatPrompt.claim(p.getUniqueId(), v.token());
    return v;
  }

  void render(Player p) {
    host.requireAdmin(p);
    var draft = host.draft(p).value();
    var d = draft.value();
    int step = Math.clamp(draft.step(), 0, 6);
    View v = view(p, false);
    p.sendMessage(Component.empty());
    p.sendMessage(
        lang.render(
            "portal-guide.heading",
            Map.of(
                "step",
                "" + (step + 1),
                "total",
                "" + STEPS.size(),
                "name",
                lang.text("portal-guide.steps." + STEPS.get(step)))));
    lang.send(p, "portal-guide.help." + STEPS.get(step));
    switch (step) {
      case 0 -> {
        p.sendMessage(
            lang.rich("portal-guide.current-name", Map.of("value", lang.render(d.nameKey()))));
        buttons(p, v, "name", "next");
      }
      case 1, 2 -> {
        Position pos = step == 1 ? d.first() : d.second();
        if ((draft.corners() & (step == 1 ? 1 : 2)) != 0)
          lang.send(
              p,
              "portal-ui.coordinates",
              Map.of("x", "" + (int) pos.x(), "y", "" + (int) pos.y(), "z", "" + (int) pos.z()));
        buttons(p, v, "here", "select");
        if ((draft.corners() & (step == 1 ? 1 : 2)) != 0) buttons(p, v, "next");
      }
      case 3 -> {
        lang.send(p, d.destination() == null ? "portal-ui.not-set" : "portal-ui.destination-set");
        for (var a : d.actions())
          lang.send(
              p,
              "portal-ui.command-value",
              Map.of("value", a.args().getOrDefault("value", a.type())));
        buttons(p, v, "destination", "command-player", "command-console");
        if (d.destination() != null) buttons(p, v, "clear-destination");
        if (!d.actions().isEmpty()) buttons(p, v, "clear-actions");
        if (d.destination() != null || !d.actions().isEmpty()) buttons(p, v, "next");
      }
      case 4 -> {
        lang.send(
            p,
            "portal-guide.appearance",
            Map.of(
                "particle",
                host.particleName(PortalParticles.parse(d.particle())),
                "color",
                "#" + d.color(),
                "spacing",
                "" + d.spacing(),
                "fill",
                lang.text(d.fill() ? "portal-guide.filled" : "portal-guide.outline")));
        buttons(p, v, "particles", "color", "fill", "spacing", "preview", "next");
      }
      case 5 -> {
        lang.send(
            p,
            "portal-guide.timing",
            Map.of(
                "cooldown",
                "" + d.cooldownSeconds(),
                "warmup",
                String.format(Locale.ROOT, "%.1f", d.warmupTicks() / 20.0)));
        buttons(p, v, "access", "next");
      }
      case 6 -> {
        lang.send(
            p,
            "portal-guide.summary",
            Map.of(
                "name",
                lang.text(d.nameKey()),
                "actions",
                "" + d.actions().size(),
                "volume",
                "" + (long) d.volume(),
                "particle",
                host.particleName(PortalParticles.parse(d.particle()))));
        buttons(p, v, "preview", "publish", "editor");
      }
      default -> throw new IllegalStateException();
    }
    if (step > 0) buttons(p, v, "back", "pause", "discard");
    else buttons(p, v, "pause", "discard");
  }

  private void buttons(Player p, View view, String... actions) {
    Component line = Component.empty();
    int count = 0;
    for (String action : actions) {
      line =
          line.append(Component.space())
              .append(
                  lang.render("portal-guide.buttons." + action)
                      .clickEvent(ClickEvent.runCommand(COMMAND + view.token() + " " + action))
                      .hoverEvent(lang.render("portal-guide.hovers." + action)));
      if (++count % 3 == 0) {
        p.sendMessage(line);
        line = Component.empty();
      }
    }
    if (count % 3 != 0) p.sendMessage(line);
  }

  void command(Player p, String[] args) {
    var view = views.get(p.getUniqueId());
    if (args.length != 3
        || view == null
        || !view.token().equals(args[1])
        || !ChatPrompt.owns(p.getUniqueId(), view.token())
        || view.expires() < System.currentTimeMillis()
        || view.revision() != host.draft(p).revision()) {
      p.sendMessage(
          lang.render("portal-guide.stale")
              .clickEvent(ClickEvent.runCommand("/loveessential:loveessential portal wizard")));
      return;
    }
    if (host.busy(p)) {
      lang.send(p, "wizard.busy");
      return;
    }
    String action = args[2];
    if (view.confirm()) {
      if (action.equals("confirm-discard")) {
        host.discard(p);
        return;
      }
      if (action.equals("input-back")) {
        render(p);
        return;
      }
      throw new IllegalArgumentException("portal-guide.stale");
    }
    var d = host.draft(p).value();
    int step = d.step();
    // Buttons from a previous step or a forged command cannot perform unrelated actions.
    Set<String> expected =
        switch (step) {
          case 0 -> Set.of("name", "next");
          case 1, 2 -> Set.of("here", "select", "next");
          case 3 ->
              Set.of(
                  "destination",
                  "command-player",
                  "command-console",
                  "clear-destination",
                  "clear-actions",
                  "next");
          case 4 -> Set.of("particles", "color", "spacing", "fill", "preview", "next", "search");
          case 5 -> Set.of("access", "next");
          case 6 -> Set.of("preview", "publish", "editor");
          default -> Set.of();
        };
    if (!expected.contains(action)
        && !Set.of("input-back", "back", "pause", "discard").contains(action))
      throw new IllegalArgumentException("portal-guide.stale");
    cancelInput(p);
    switch (action) {
      case "name", "color", "spacing", "command-player", "command-console", "search" ->
          ask(p, action);
      case "input-back" -> render(p);
      case "here" -> capture(p, step, Position.of(p.getLocation().getBlock().getLocation()));
      case "select" -> {
        host.select(p, step);
        buttons(p, views.get(p.getUniqueId()), "input-back", "pause");
      }
      case "back" -> advance(p, Math.max(0, step - 1));
      case "next" -> {
        if ((step == 1 || step == 2) && (d.corners() & (step == 1 ? 1 : 2)) == 0)
          throw new IllegalArgumentException("portal-guide.need-corner");
        if (step == 3 && d.value().destination() == null && d.value().actions().isEmpty())
          throw new IllegalArgumentException("portal-ui.no-action");
        advance(p, Math.min(6, step + 1));
      }
      case "destination" -> update(p, e -> e.destination = Position.of(p.getLocation()));
      case "clear-destination" -> update(p, e -> e.destination = null);
      case "clear-actions" -> update(p, e -> e.actions = List.of());
      case "particles" -> host.particleMenu(p, "all", "", 0);
      case "fill" -> update(p, e -> e.fill = !e.fill);
      case "access" -> host.accessForm(p);
      case "preview" -> host.preview(p);
      case "editor" -> host.editor(p);
      case "publish" -> host.publish(p);
      case "pause" -> {
        leave(p);
        lang.send(p, "portal-guide.paused");
      }
      case "discard" -> confirmDiscard(p);
      default -> throw new IllegalArgumentException("portal-guide.stale");
    }
  }

  private void advance(Player p, int step) {
    var d = host.draft(p).value();
    host.adjust(p, e -> {}, step, d.corners(), () -> render(p));
  }

  private void update(Player p, java.util.function.Consumer<PortalEdit> edit) {
    var d = host.draft(p).value();
    host.adjust(p, edit, d.step(), d.corners(), () -> render(p));
  }

  void capture(Player p, int corner, Position position) {
    if (corner != 1 && corner != 2) throw new IllegalArgumentException("portal-guide.stale");
    var d = host.draft(p).value();
    if (corner != d.step()) throw new IllegalArgumentException("portal-guide.stale");
    if (corner == 2
        && ((d.corners() & 1) == 0 || !d.value().first().world().equals(position.world())))
      throw new IllegalArgumentException("portal-ui.same-world");
    host.adjust(
        p,
        edit -> {
          if (corner == 1) {
            edit.first = position;
            edit.second = position;
          } else edit.second = position;
        },
        corner + 1,
        corner == 1 ? 1 : 3,
        () -> {
          host.preview(p);
          render(p);
        });
  }

  void confirmDiscard(Player p) {
    host.requireAdmin(p);
    if (!active(p))
      views.put(
          p.getUniqueId(),
          new View("", host.draft(p).revision(), false, System.currentTimeMillis() + 600000));
    View v = view(p, true);
    lang.send(p, "portal-ui.discard-confirm");
    buttons(p, v, "confirm-discard", "input-back");
  }

  void search(Player p) {
    ask(p, "search");
  }

  private void ask(Player p, String kind) {
    View v = view(p, false);
    var input = new Input(kind, v.token(), v.revision(), System.currentTimeMillis() + 180000);
    inputs.put(p.getUniqueId(), input);
    String current =
        switch (kind) {
          case "name" -> lang.text(host.draft(p).value().value().nameKey());
          case "color" -> host.draft(p).value().value().color();
          case "spacing" -> "" + host.draft(p).value().value().spacing();
          default -> "";
        };
    ChatPrompt.send(
        p,
        lang,
        v.token(),
        lang.render("portal-guide.context"),
        lang.render("portal-guide.inputs." + kind),
        lang.text("portal-guide.examples." + kind),
        current,
        COMMAND + v.token() + " input-back");
  }

  private void consume(Player p, Input input, String text) {
    if (!ChatPrompt.owns(p.getUniqueId(), input.token())) return;
    ChatPrompt.release(p.getUniqueId(), input.token());
    var view = views.get(p.getUniqueId());
    if (!host.admin(p)
        || view == null
        || !view.token().equals(input.token())
        || host.draft(p).revision() != input.revision()) return;
    if (input.expires() < System.currentTimeMillis()) {
      lang.send(p, "input.expired");
      render(p);
      return;
    }
    if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("отмена")) {
      render(p);
      return;
    }
    try {
      switch (input.kind()) {
        case "name" -> host.guideName(p, text.strip(), () -> render(p));
        case "color" -> {
          String color = text.strip().replace("#", "");
          if (!color.matches("[a-fA-F0-9]{6}"))
            throw new IllegalArgumentException("portal-guide.bad-color");
          update(p, e -> e.color = color.toUpperCase(Locale.ROOT));
        }
        case "spacing" ->
            update(p, e -> e.spacing = Double.parseDouble(text.strip().replace(',', '.')));
        case "command-player", "command-console" ->
            host.guideCommand(
                p,
                text,
                input.kind().equals("command-console") ? "console" : "player",
                () -> render(p));
        case "search" -> {
          if (text.length() > 64) throw new IllegalArgumentException("errors.input");
          host.particleMenu(p, "all", text.strip(), 0);
        }
        default -> throw new IllegalArgumentException("errors.input");
      }
    } catch (RuntimeException error) {
      lang.error(p, error);
      ask(p, input.kind());
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void commandInput(PlayerCommandPreprocessEvent event) {
    var input = inputs.get(event.getPlayer().getUniqueId());
    if (input == null
        || !input.kind().startsWith("command-")
        || !ChatPrompt.owns(event.getPlayer().getUniqueId(), input.token())) return;
    if (event.getMessage().startsWith(COMMAND)) return;
    event.setCancelled(true);
    if (inputs.remove(event.getPlayer().getUniqueId(), input))
      consume(
          event.getPlayer(),
          input,
          event.getMessage().equalsIgnoreCase("/cancel") ? "cancel" : event.getMessage());
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void chat(AsyncChatEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    var input = inputs.get(id);
    if (input == null) return;
    if (!ChatPrompt.owns(id, input.token())) {
      inputs.remove(id, input);
      return;
    }
    event.setCancelled(true);
    String text = PlainTextComponentSerializer.plainText().serialize(event.message());
    if (inputs.remove(id, input)) main.execute(() -> consume(event.getPlayer(), input, text));
  }

  void tick() {
    long now = System.currentTimeMillis();
    for (var entry : List.copyOf(views.entrySet()))
      if (entry.getValue().expires() < now) {
        views.remove(entry.getKey());
        var input = inputs.remove(entry.getKey());
        if (input != null) ChatPrompt.release(entry.getKey(), input.token());
      }
  }

  public void close() {
    HandlerList.unregisterAll(this);
    inputs.forEach((id, input) -> ChatPrompt.release(id, input.token()));
    inputs.clear();
    views.clear();
  }
}
