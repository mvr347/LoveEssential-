package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.api.PrivateMessageEvent;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

public final class PrivateMessages implements Listener, AutoCloseable {
  public record Preferences(boolean enabled, Map<String, String> ignored) {
    public Preferences {
      ignored = ignored == null ? Map.of() : Map.copyOf(ignored);
    }
  }

  private record State(long revision, Preferences preferences) {}

  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final Lang lang;
  private final SqliteStore store;
  private final LoveBridge love;
  private final Gameplay gameplay;
  private final Nicknames nicknames;
  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> replies = new ConcurrentHashMap<>();
  private final Map<UUID, Long> sent = new HashMap<>();
  private final Set<UUID> saving = new HashSet<>();

  public PrivateMessages(
      LoveEssentialPlugin plugin,
      MainThread main,
      Lang lang,
      SqliteStore store,
      LoveBridge love,
      Gameplay gameplay,
      Nicknames nicknames) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.store = store;
    this.love = love;
    this.gameplay = gameplay;
    this.nicknames = nicknames;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getOnlinePlayers().forEach(this::load);
  }

  private void load(Player p) {
    store
        .get("message-preferences", p.getUniqueId().toString(), Preferences.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      if (!p.isOnline() || Bukkit.getPlayer(p.getUniqueId()) != p) return;
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      var loaded =
                          saved
                              .map(d -> new State(d.revision(), d.value()))
                              .orElseGet(() -> new State(0, new Preferences(true, Map.of())));
                      states.compute(
                          p.getUniqueId(),
                          (id, previous) ->
                              previous != null && previous.revision() > loaded.revision()
                                  ? previous
                                  : loaded);
                    }));
  }

  public List<String> ignoredNames(Player p) {
    var state = states.get(p.getUniqueId());
    return state == null ? List.of() : List.copyOf(state.preferences().ignored().values());
  }

  /** Whether {@code who} ignores {@code target}; false while the preferences are still loading. */
  public boolean ignores(UUID who, UUID target) {
    var state = states.get(who);
    return state != null && state.preferences().ignored().containsKey(target.toString());
  }

  /**
   * Adds an entry carried over from another plugin's ignore list (LoveChat's old table).
   * Returns false while the preferences are still loading, so the caller retries later.
   */
  public boolean importIgnore(Player who, UUID target) {
    var state = states.get(who.getUniqueId());
    if (state == null || saving.contains(who.getUniqueId())) return false;
    if (state.preferences().ignored().containsKey(target.toString())) return true;
    if (state.preferences().ignored().size() >= plugin.getConfig().getInt("messages.max-ignored", 200))
      return true;
    String name = Bukkit.getOfflinePlayer(target).getName();
    change(
        who,
        old -> {
          var ignored = new HashMap<>(old.ignored());
          ignored.put(target.toString(), name == null ? target.toString() : name);
          return new Preferences(old.enabled(), ignored);
        },
        () -> {});
    return true;
  }

  public Preferences preferences(Player p) {
    var state = states.get(p.getUniqueId());
    if (state == null) throw new IllegalArgumentException("messages.loading");
    return state.preferences();
  }

  private void access(Player p) {
    plugin.requireAccess("messages", p);
    if (!plugin.ready() || !plugin.module("messages"))
      throw new IllegalArgumentException("messages.disabled");
    if (!love.authenticated(p)) throw new IllegalArgumentException("errors.access");
  }

  public Player target(Player viewer, String name) {
    Player p = Bukkit.getPlayerExact(name);
    if (p == null || !p.isOnline() || !viewer.canSee(p) || !love.authenticated(p))
      throw new IllegalArgumentException("players.unavailable");
    return p;
  }

  public void command(Player p, String command, String[] args) {
    access(p);
    switch (command) {
      case "msg" -> {
        if (args.length < 2) {
          lang.send(p, "messages.usage");
          return;
        }
        send(p, target(p, args[0]), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
      }
      case "reply" -> {
        if (args.length == 0) {
          lang.send(p, "messages.reply-usage");
          return;
        }
        var recipient = Bukkit.getPlayer(replies.getOrDefault(p.getUniqueId(), new UUID(0, 0)));
        if (recipient == null) throw new IllegalArgumentException("messages.no-reply");
        send(p, target(p, recipient.getName()), String.join(" ", args));
      }
      case "msgtoggle" -> toggle(p, () -> {});
      case "ignore" -> {
        if (args.length != 1) {
          lang.send(p, "messages.ignore-usage");
          return;
        }
        var existing =
            preferences(p).ignored().entrySet().stream()
                .filter(e -> e.getValue().equalsIgnoreCase(args[0]))
                .findFirst();
        if (existing.isPresent()) unignore(p, existing.get().getKey(), () -> {});
        else {
          var other = target(p, args[0]);
          if (other.equals(p)) throw new IllegalArgumentException("messages.self");
          if (preferences(p).ignored().size()
              >= plugin.getConfig().getInt("messages.max-ignored", 200))
            throw new IllegalArgumentException("messages.ignore-limit");
          change(
              p,
              old -> {
                var ignored = new HashMap<>(old.ignored());
                ignored.put(other.getUniqueId().toString(), other.getName());
                return new Preferences(old.enabled(), ignored);
              },
              () -> {});
        }
      }
      default -> throw new IllegalArgumentException("errors.input");
    }
  }

  public void toggle(Player p, Runnable done) {
    change(p, old -> new Preferences(!old.enabled(), old.ignored()), done);
  }

  public void unignore(Player p, String uuid, Runnable done) {
    change(
        p,
        old -> {
          var ignored = new HashMap<>(old.ignored());
          ignored.remove(uuid);
          return new Preferences(old.enabled(), ignored);
        },
        done);
  }

  private void change(Player p, UnaryOperator<Preferences> update, Runnable done) {
    access(p);
    preferences(p);
    if (!saving.add(p.getUniqueId())) throw new IllegalArgumentException("messages.loading");
    var old = states.get(p.getUniqueId());
    var next = update.apply(old.preferences());
    store
        .save(
            "message-preferences",
            p.getUniqueId().toString(),
            old.revision(),
            next,
            p.getUniqueId().toString())
        .whenComplete(
            (revision, error) ->
                main.execute(
                    () -> {
                      saving.remove(p.getUniqueId());
                      if (error == null && states.get(p.getUniqueId()) == old)
                        states.put(p.getUniqueId(), new State(revision, next));
                      Player current = Bukkit.getPlayer(p.getUniqueId());
                      if (current != p && current != null) load(current);
                      if (!p.isOnline()) return;
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        load(p);
                        return;
                      }
                      lang.send(p, "messages.saved");
                      if (plugin.module("messages") && love.authenticated(p)) done.run();
                    }));
  }

  public void send(Player sender, Player recipient, String text) {
    access(sender);
    if (sender.equals(recipient)) throw new IllegalArgumentException("messages.self");
    checkDelivery(sender, recipient);
    validate(text);
    long now = System.nanoTime();
    long delay = Math.max(0, plugin.getConfig().getLong("messages.cooldown-ms", 750)) * 1_000_000;
    if (now - sent.getOrDefault(sender.getUniqueId(), now - delay) < delay)
      throw new IllegalArgumentException("messages.cooldown");
    // Throttle cancelled attempts too; a filter can safely reject without generating replies.
    sent.put(sender.getUniqueId(), now);
    var event = new PrivateMessageEvent(sender, recipient, text);
    Bukkit.getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      lang.send(sender, "messages.cancelled");
      return;
    }
    checkDelivery(sender, recipient);
    validate(event.getMessage());
    boolean useNick =
        plugin.module("nickname")
            && plugin.getConfig().getBoolean("nickname.apply-to-messages", true);
    var values =
        Map.of(
            "sender",
                useNick
                    ? nicknames.display(sender)
                    : net.kyori.adventure.text.Component.text(sender.getName()),
            "recipient",
                useNick
                    ? nicknames.display(recipient)
                    : net.kyori.adventure.text.Component.text(recipient.getName()),
            "sender_name", net.kyori.adventure.text.Component.text(sender.getName()),
            "recipient_name", net.kyori.adventure.text.Component.text(recipient.getName()),
            "message", net.kyori.adventure.text.Component.text(event.getMessage()));
    sender.sendMessage(lang.rich("messages.outgoing", values));
    recipient.sendMessage(
        lang.rich("messages.incoming", values)
            .append(net.kyori.adventure.text.Component.space())
            .append(
                lang.render("chat.reply")
                    .clickEvent(ClickEvent.suggestCommand("/le msg " + sender.getName() + " ")))
            .clickEvent(ClickEvent.suggestCommand("/le msg " + sender.getName() + " "))
            .hoverEvent(lang.render("messages.reply-hint")));
    replies.put(sender.getUniqueId(), recipient.getUniqueId());
    replies.put(recipient.getUniqueId(), sender.getUniqueId());
    if (gameplay.afk(recipient))
      lang.send(sender, "messages.afk", Map.of("player", recipient.getName()));
  }

  private void checkDelivery(Player sender, Player recipient) {
    if (!sender.isOnline()
        || !recipient.isOnline()
        || !sender.canSee(recipient)
        || !recipient.canSee(sender)
        || !love.authenticated(sender)
        || !love.authenticated(recipient))
      throw new IllegalArgumentException("players.unavailable");
    var from = preferences(sender);
    var to = preferences(recipient);
    if (!from.enabled()) throw new IllegalArgumentException("messages.own-disabled");
    if (!to.enabled()
        || to.ignored().containsKey(sender.getUniqueId().toString())
        || from.ignored().containsKey(recipient.getUniqueId().toString()))
      throw new IllegalArgumentException("messages.unavailable");
  }

  private void validate(String text) {
    if (text.isBlank()
        || text.length() > plugin.getConfig().getInt("messages.max-length", 512)
        || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("messages.invalid");
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    load(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    states.remove(id);
    replies.remove(id);
    replies.values().removeIf(id::equals);
    sent.remove(id);
  }

  @Override
  public void close() {
    HandlerList.unregisterAll(this);
    states.clear();
    replies.clear();
    sent.clear();
    saving.clear();
  }
}
