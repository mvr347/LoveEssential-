package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/** Stores identity-independent display names; never changes the account name or skin. */
public final class Nicknames implements Listener, AutoCloseable {
  public record Settings(String name, List<String> colors) {
    public Settings {
      name = name == null ? "" : name;
      colors = colors == null ? List.of() : List.copyOf(colors);
    }
  }

  private record State(long revision, Settings settings) {}

  private record Display(Component component, Map<String, String> values) {}

  public static final LegacyComponentSerializer LEGACY =
      LegacyComponentSerializer.builder()
          .character('§')
          .hexColors()
          .useUnusualXRepeatedCharacterHexFormat()
          .build();
  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final Lang lang;
  private final SqliteStore store;
  private final LoveBridge love;
  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private final Map<UUID, Display> displays = new ConcurrentHashMap<>();
  private final Map<UUID, Component> originals = new HashMap<>(), applied = new HashMap<>();
  private final Set<UUID> loading = new HashSet<>(), saving = new HashSet<>();

  public Nicknames(
      LoveEssentialPlugin plugin, MainThread main, Lang lang, SqliteStore store, LoveBridge love) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.store = store;
    this.love = love;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getOnlinePlayers().forEach(this::load);
  }

  private void load(Player p) {
    UUID id = p.getUniqueId();
    if (!loading.add(id)) return;
    store
        .get("nickname", id.toString(), Settings.class)
        .whenComplete(
            (saved, error) ->
                main.execute(
                    () -> {
                      loading.remove(id);
                      Player current = Bukkit.getPlayer(id);
                      if (current != p || !p.isOnline()) {
                        if (current != null && current.isOnline()) load(current);
                        return;
                      }
                      if (error != null) {
                        lang.send(p, "errors.storage");
                        return;
                      }
                      var value =
                          saved
                              .map(d -> new State(d.revision(), d.value()))
                              .orElseGet(() -> new State(0, new Settings("", List.of())));
                      states.compute(
                          id,
                          (key, old) ->
                              old != null && old.revision() > value.revision() ? old : value);
                      apply(p);
                    }));
  }

  public Settings settings(Player p) {
    var state = states.get(p.getUniqueId());
    if (state == null) {
      load(p);
      throw new IllegalArgumentException("nick.loading");
    }
    return state.settings();
  }

  public Component display(Player p) {
    var d = displays.get(p.getUniqueId());
    return d == null ? Component.text(p.getName()) : d.component();
  }

  public Map<String, String> values(UUID id) {
    var d = displays.get(id);
    return d == null ? Map.of() : d.values();
  }

  public boolean tabNames() {
    return plugin.module("nickname")
        && plugin.getConfig().getBoolean("nickname.apply-to-tab", true);
  }

  public boolean canUse(Player p) {
    return plugin.module("nickname")
        && love.authenticated(p)
        && p.hasPermission("loveessential.nick");
  }

  public CompletableFuture<Void> change(Player actor, Player target, String... args) {
    MainThread.require();
    if (!plugin.ready()
        || !canUse(actor)
        || !target.isOnline()
        || !love.authenticated(target)
        || !actor.canSee(target)
        || !actor.equals(target) && !actor.hasPermission("loveessential.nick.others"))
      throw new IllegalArgumentException("errors.permission");
    var previous = settings(target);
    if (args.length == 0) throw new IllegalArgumentException("nick.usage");
    String op = args[0].toLowerCase(Locale.ROOT);
    Settings next;
    switch (op) {
      case "reset" -> {
        if (args.length != 1) throw new IllegalArgumentException("nick.usage");
        next = new Settings("", List.of());
      }
      case "set" -> {
        if (args.length != 2) throw new IllegalArgumentException("nick.usage");
        validateName(target, args[1]);
        next = new Settings(args[1], previous.colors());
      }
      case "color", "gradient" -> {
        if (args.length == 2 && args[1].equalsIgnoreCase("reset"))
          next = new Settings(previous.name(), List.of());
        else {
          if (!actor.hasPermission("loveessential.nick." + op))
            throw new IllegalArgumentException("errors.permission");
          if (op.equals("color") && args.length != 2
              || op.equals("gradient") && (args.length < 3 || args.length > 6))
            throw new IllegalArgumentException("nick.usage");
          var colors =
              Arrays.stream(args)
                  .skip(1)
                  .map(Nicknames::color)
                  .map(c -> c.asHexString().toLowerCase(Locale.ROOT))
                  .toList();
          next = new Settings(previous.name(), colors);
        }
      }
      default -> {
        if (args.length != 1) throw new IllegalArgumentException("nick.usage");
        validateName(target, args[0]);
        next = new Settings(args[0], previous.colors());
      }
    }
    UUID id = target.getUniqueId();
    if (!saving.add(id)) throw new IllegalArgumentException("nick.loading");
    reserved.put(id, next.name().isEmpty() ? target.getName() : next.name());
    var old = states.get(id);
    var result = new CompletableFuture<Void>();
    store
        .save("nickname", id.toString(), old.revision(), next, actor.getUniqueId().toString())
        .whenComplete(
            (revision, error) ->
                main.execute(
                    () -> {
                      saving.remove(id);
                      reserved.remove(id);
                      if (error != null) {
                        load(target);
                        result.completeExceptionally(error);
                        return;
                      }
                      Player current = Bukkit.getPlayer(id);
                      if (current != null && current.isOnline()) {
                        states.put(id, new State(revision, next));
                        apply(current);
                      }
                      result.complete(null);
                    }));
    return result;
  }

  private void validateName(Player p, String name) {
    int length = name.codePointCount(0, name.length());
    if (length < plugin.getConfig().getInt("nickname.min-length", 3)
        || length > plugin.getConfig().getInt("nickname.max-length", 24)
        || !name.matches("[\\p{L}\\p{N}_]+"))
      throw new IllegalArgumentException("nick.invalid-name");
    if (plugin.getConfig().getBoolean("nickname.prevent-impersonation", true)) {
      var known = Bukkit.getOfflinePlayerIfCached(name);
      if (known != null && !known.getUniqueId().equals(p.getUniqueId()))
        throw new IllegalArgumentException("nick.taken");
      for (Player other : Bukkit.getOnlinePlayers())
        if (!other.equals(p)
            && (other.getName().equalsIgnoreCase(name)
                || values(other.getUniqueId())
                    .getOrDefault("nickname_plain", "")
                    .equalsIgnoreCase(name))) throw new IllegalArgumentException("nick.taken");
      // Include pending reservations: two asynchronous saves cannot claim the same nickname.
      if (reserved.entrySet().stream()
          .anyMatch(
              e -> !e.getKey().equals(p.getUniqueId()) && e.getValue().equalsIgnoreCase(name)))
        throw new IllegalArgumentException("nick.taken");
    }
  }

  private final Map<UUID, String> reserved = new HashMap<>();

  public static TextColor color(String value) {
    String text = value.toLowerCase(Locale.ROOT);
    TextColor color =
        text.matches("#[0-9a-f]{6}")
            ? TextColor.fromHexString(text)
            : NamedTextColor.NAMES.value(text);
    if (color == null) throw new IllegalArgumentException("nick.invalid-color");
    return color;
  }

  public static Component render(String name, List<String> colors) {
    if (colors.isEmpty()) return Component.text(name);
    var palette = colors.stream().map(Nicknames::color).toList();
    if (palette.size() == 1) return Component.text(name, palette.getFirst());
    int[] points = name.codePoints().toArray();
    Component out = Component.empty();
    for (int i = 0; i < points.length; i++) {
      double at = points.length < 2 ? 0 : (double) i / (points.length - 1) * (palette.size() - 1);
      int segment = Math.min((int) at, palette.size() - 2);
      out =
          out.append(
              Component.text(
                  new String(Character.toChars(points[i])),
                  TextColor.lerp(
                      (float) (at - segment), palette.get(segment), palette.get(segment + 1))));
    }
    return out;
  }

  private void apply(Player p) {
    var state = states.get(p.getUniqueId());
    if (state == null) return;
    Settings setting = plugin.module("nickname") ? state.settings() : new Settings("", List.of());
    String plain = setting.name().isEmpty() ? p.getName() : setting.name();
    List<String> colors = setting.colors();
    String defaultColor = plugin.getConfig().getString("nickname.default-color", "");
    if (colors.isEmpty() && !defaultColor.isBlank() && plugin.module("nickname"))
      colors = List.of(defaultColor);
    Component rendered = render(plain, colors);
    boolean active = !setting.name().isEmpty() || !setting.colors().isEmpty();
    displays.put(
        p.getUniqueId(),
        new Display(
            rendered,
            Map.ofEntries(
                Map.entry("real_name", p.getName()),
                Map.entry("nickname", LEGACY.serialize(rendered)),
                Map.entry("nickname_plain", plain),
                Map.entry("nickname_mini", MiniMessage.miniMessage().serialize(rendered)),
                Map.entry("nickname_active", "" + active),
                Map.entry(
                    "nickname_style",
                    setting.colors().isEmpty()
                        ? "default"
                        : setting.colors().size() == 1 ? "color" : "gradient"),
                Map.entry("nickname_colors", String.join(",", setting.colors())))));
    if (plugin.module("nickname")
        && plugin.getConfig().getBoolean("nickname.apply-display-name", true)) {
      originals.putIfAbsent(p.getUniqueId(), p.displayName());
      p.displayName(rendered);
      applied.put(p.getUniqueId(), rendered);
    } else restore(p);
  }

  private void restore(Player p) {
    Component last = applied.remove(p.getUniqueId()), original = originals.remove(p.getUniqueId());
    if (last != null && last.equals(p.displayName()))
      p.displayName(original == null ? Component.text(p.getName()) : original);
  }

  public void refresh() {
    Bukkit.getOnlinePlayers().forEach(this::apply);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    load(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    states.remove(id);
    displays.remove(id);
    originals.remove(id);
    applied.remove(id);
  }

  @Override
  public void close() {
    HandlerList.unregisterAll(this);
    Bukkit.getOnlinePlayers().forEach(this::restore);
    states.clear();
    displays.clear();
    originals.clear();
    applied.clear();
    loading.clear();
    saving.clear();
    reserved.clear();
  }
}
