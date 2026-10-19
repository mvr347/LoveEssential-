package dev.lovelace.loveessential.gui;

import dev.lovelace.loveessential.*;
import dev.lovelace.loveessential.config.ModuleConfigs;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.module.Gameplay;
import dev.lovelace.loveessential.npc.NpcService;
import dev.lovelace.loveessential.wizard.NpcWizard;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;

/** Native inventory controls; all mutations still pass through permission and service checks. */
public final class ControlMenus implements Listener, AutoCloseable {
  private final LoveEssentialPlugin plugin;
  private final MainThread main;
  private final Lang lang;
  private final LoveBridge love;
  private final NpcService npcs;
  private final NpcWizard wizard;
  private final Gameplay gameplay;
  private final dev.lovelace.loveessential.module.Nicknames nicknames;
  private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
  private final Map<UUID, String> roots = new HashMap<>();
  private final Set<UUID> direct = new HashSet<>(), dispatching = new HashSet<>();

  public void cancelInput(Player p) {
    prompts.remove(p.getUniqueId());
  }

  public void direct(Player p, Runnable open) {
    if (!dispatching.contains(p.getUniqueId())) direct.add(p.getUniqueId());
    try {
      open.run();
    } finally {
      direct.remove(p.getUniqueId());
    }
  }

  private record Prompt(
      long expires,
      Predicate<Player> allowed,
      Consumer<String> input,
      Runnable back,
      String token,
      Component context,
      String instructionKey,
      String example,
      String current) {}

  private static final class Menu implements InventoryHolder {
    Inventory inventory;
    final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
    final Predicate<Player> allowed;
    boolean root;

    Menu(Predicate<Player> allowed) {
      this.allowed = allowed;
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  public ControlMenus(
      LoveEssentialPlugin plugin,
      MainThread main,
      Lang lang,
      LoveBridge love,
      NpcService npcs,
      NpcWizard wizard,
      Gameplay gameplay,
      dev.lovelace.loveessential.module.Nicknames nicknames) {
    this.plugin = plugin;
    this.main = main;
    this.lang = lang;
    this.love = love;
    this.npcs = npcs;
    this.wizard = wizard;
    this.gameplay = gameplay;
    this.nicknames = nicknames;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  private boolean admin(Player p) {
    return p.hasPermission("loveessential.admin");
  }

  private boolean npcView(Player p) {
    return plugin.module("npc")
        && (p.hasPermission("loveessential.npc.view")
            || p.hasPermission("loveessential.npc.create"));
  }

  private Menu menu(Player p, String title, Map<String, String> vars, Predicate<Player> allowed) {
    if (!plugin.ready() || !love.authenticated(p) || !allowed.test(p))
      throw new IllegalArgumentException("errors.permission");
    prompts.remove(p.getUniqueId());
    wizard.cancelInput(p);
    var m = new Menu(allowed);
    String route = title + new TreeMap<>(vars);
    if (direct.remove(p.getUniqueId())) roots.put(p.getUniqueId(), route);
    m.root = route.equals(roots.get(p.getUniqueId()));
    m.inventory = Bukkit.createInventory(m, 54, lang.render(title, vars));
    MenuStyle.frame(m.inventory);
    m.inventory.setItem(
        0,
        MenuStyle.head(
            "d0d5a4eba140cbb34d45e40d62dd3f7e6584b2ab0b4155a0bcc603d5d750f793",
            lang.render(title, vars),
            List.of()));
    return m;
  }

  private void put(
      Menu m, int slot, Material icon, String title, String action, Consumer<ClickType> click) {
    m.inventory.setItem(slot, MenuStyle.button(lang, icon, title, "gui.separator", action));
    m.actions.put(slot, click);
  }

  private void entry(
      Menu m,
      int slot,
      Material icon,
      Component title,
      List<Component> lore,
      Consumer<ClickType> click) {
    m.inventory.setItem(slot, MenuStyle.item(icon, title, lore));
    m.actions.put(slot, click);
  }

  private void navigation(Menu m, Player p, int page, int total, IntConsumer pages, Runnable back) {
    if (!m.root) put(m, 51, Material.ARROW, "common.back", "gui.click.open", c -> back.run());
    if (page > 0)
      put(m, 36, Material.ARROW, "gui.previous", "gui.click.open", c -> pages.accept(page - 1));
    if ((page + 1) * MenuStyle.CONTENT.length < total)
      put(m, 44, Material.ARROW, "common.next", "gui.click.open", c -> pages.accept(page + 1));
    var badge = m.inventory.getItem(0);
    var badgeMeta = badge.getItemMeta();
    badgeMeta.lore(
        List.of(
            lang.render(
                    "gui.page",
                    Map.of("page", "" + (page + 1), "pages", "" + Math.max(1, (total + 20) / 21)))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
    badge.setItemMeta(badgeMeta);
    put(m, 52, Material.BARRIER, "gui.close", "gui.click.close", c -> p.closeInventory());
  }

  private void command(Player p, String command) {
    p.closeInventory();
    dispatching.add(p.getUniqueId());
    try {
      Bukkit.dispatchCommand(
          p,
          "loveessential:loveessential "
              + (command.startsWith("le ") ? command.substring(3) : command));
    } finally {
      dispatching.remove(p.getUniqueId());
    }
  }

  public void home(Player p) {
    modules(p);
  }

  private void locked(Menu m, int slot) {
    var item = m.inventory.getItem(slot);
    var meta = item.getItemMeta();
    meta.displayName(
        meta.displayName()
            .append(lang.render("gui.locked-suffix"))
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
    meta.lore(
        List.of(
                lang.render("gui.separator"),
                lang.render("gui.locked"),
                lang.render("gui.permission"),
                lang.render("gui.click.blocked"))
            .stream()
            .map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
            .toList());
    item.setItemMeta(meta);
    m.actions.put(
        slot,
        c -> {
          throw new IllegalArgumentException("errors.permission");
        });
  }

  public void modules(Player p) {
    modules(p, 0);
  }

  private void modules(Player p, int page) {
    var m = menu(p, "module-menu.title", Map.of(), this::admin);
    m.root = true;
    var names = LoveEssentialPlugin.MODULES.stream().sorted().toList();
    int i = 0;
    for (String id : names.subList(page * 21, Math.min(names.size(), (page + 1) * 21))) {
      boolean enabled = plugin.module(id);
      entry(
          m,
          MenuStyle.slot(i++, Math.min(21, names.size() - page * 21)),
          switch (id) {
            case "npc", "profile" -> Material.PLAYER_HEAD;
            case "tab", "nickname" -> Material.NAME_TAG;
            case "home" -> Material.RED_BED;
            case "spawn" -> Material.RESPAWN_ANCHOR;
            case "teleport", "portal" -> Material.ENDER_PEARL;
            case "messages" -> Material.WRITABLE_BOOK;
            case "inventory" -> Material.CHEST;
            case "afk" -> Material.CLOCK;
            case "vanish" -> Material.ENDER_EYE;
            case "skin" -> Material.LEATHER_HELMET;
            case "sitride" -> Material.SADDLE;
            case "nightskip" -> Material.LANTERN;
            case "hologram", "motd", "joinquit" -> Material.OAK_SIGN;
            case "traces" -> Material.FEATHER;
            default -> Material.COMPARATOR;
          },
          lang.render("gui.module-names." + id),
          java.util.stream.Stream.concat(
                  lang.lines("module-menu.descriptions." + id).stream()
                      .map(
                          line ->
                              net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                  .deserialize(line)),
                  java.util.stream.Stream.of(
                      lang.render(enabled ? "gui.enabled" : "gui.disabled"),
                      lang.render("gui.file", Map.of("path", "modules/" + id + ".yml")),
                      lang.render("gui.separator"),
                      lang.render("gui.click.settings"),
                      lang.render("gui.click.toggle")))
              .toList(),
          c -> {
            if (c.isRightClick())
              complete(p, plugin.module(id, !plugin.module(id)), () -> modules(p, page), true);
            else settings(p, id, "", 0);
          });
    }
    navigation(m, p, page, names.size(), n -> modules(p, n), () -> home(p));
    p.openInventory(m.inventory);
  }

  public void settings(Player p, String id, String path, int page) {
    if (!LoveEssentialPlugin.MODULES.contains(id))
      throw new IllegalArgumentException("errors.module");
    var snapshot = ModuleConfigs.view(plugin.getConfig(), id);
    var section = path.isEmpty() ? snapshot : snapshot.getConfigurationSection(path);
    if (section == null) throw new IllegalArgumentException("errors.not-found");
    var keys = section.getKeys(false).stream().sorted().toList();
    var m =
        menu(
            p,
            "gui.settings-title",
            Map.of("module", lang.text("gui.module-labels." + id), "path", path),
            this::admin);
    for (int i = page * 21; i < Math.min(keys.size(), (page + 1) * 21); i++) {
      String key = keys.get(i), full = path.isEmpty() ? key : path + "." + key;
      Object value = section.get(key);
      boolean folder = section.isConfigurationSection(key);
      String labelKey = "gui.settings." + id + "." + full + "._label";
      var title =
          lang.contains(labelKey)
              ? lang.render(labelKey)
              : lang.render("gui.label", Map.of("name", key));
      var lore = new ArrayList<Component>();
      String helpKey = "module-help." + id + "." + full + "._description";
      if (lang.contains(helpKey)) lore.addAll(MenuStyle.description(lang, helpKey));
      else
        lore.add(
            lang.render(
                folder
                    ? "settings-help.section"
                    : value instanceof Boolean
                        ? "settings-help.boolean"
                        : value instanceof Number
                            ? "settings-help.number"
                            : value instanceof List<?>
                                ? "settings-help.list"
                                : "settings-help.text"));
      lore.add(lang.render("gui.path", Map.of("path", full)));
      if (!folder)
        lore.add(
            lang.render(
                "gui.value",
                Map.of(
                    "value",
                    (value instanceof List<?> values
                        ? lang.text("gui.list-count") + " " + values.size()
                        : String.valueOf(value)))));
      lore.add(lang.render("gui.separator"));
      lore.add(
          lang.render(
              folder || value instanceof List<?>
                  ? "gui.click.open"
                  : value instanceof Boolean ? "gui.click.switch" : "gui.click.edit"));
      if (value instanceof String text && full.endsWith("-key") && lang.contains(text))
        lore.add(lang.render("gui.click.text"));
      entry(
          m,
          MenuStyle.slot(i % 21, Math.min(21, keys.size() - page * 21)),
          folder
              ? Material.CHEST
              : value instanceof Boolean b
                  ? (b ? Material.LIME_DYE : Material.GRAY_DYE)
                  : value instanceof List<?> ? Material.WRITABLE_BOOK : Material.COMPARATOR,
          title,
          lore,
          c -> {
            if (folder) settings(p, id, full, 0);
            else if (value instanceof Boolean b)
              saveSetting(p, id, full, value, !b, () -> settings(p, id, path, page));
            else if (value instanceof List<?> list)
              list(p, id, full, list, 0, () -> settings(p, id, path, page));
            else if (!settingChoices(id, full).isEmpty())
              settingPicker(p, id, full, value, 0, () -> settings(p, id, path, page));
            else if (c.isRightClick()
                && value instanceof String text
                && full.endsWith("-key")
                && lang.contains(text))
              ask(
                  p,
                  "gui.prompt-text",
                  this::admin,
                  input ->
                      complete(
                          p,
                          CompletableFuture.runAsync(
                              () -> {
                                try {
                                  lang.putAll(Map.of(text, input));
                                } catch (Exception e) {
                                  throw new CompletionException(e);
                                }
                              },
                              plugin.io()),
                          () -> settings(p, id, path, page),
                          true),
                  () -> settings(p, id, path, page),
                  lang.render("gui.path", Map.of("path", text)),
                  lang.text(text),
                  lang.text(text));
            else if (value instanceof Number)
              number(p, id, full, value, () -> settings(p, id, path, page));
            else
              ask(
                  p,
                  "gui.prompt-value",
                  this::admin,
                  input ->
                      saveSetting(
                          p,
                          id,
                          full,
                          value,
                          parse(value, input),
                          () -> settings(p, id, path, page)),
                  () -> settings(p, id, path, page),
                  title,
                  String.valueOf(value),
                  String.valueOf(value));
          });
    }
    if (keys.isEmpty())
      m.inventory.setItem(22, MenuStyle.button(lang, Material.BOOK, "gui.empty-settings"));
    navigation(
        m,
        p,
        page,
        keys.size(),
        n -> settings(p, id, path, n),
        () -> {
          if (path.isEmpty()) modules(p);
          else
            settings(p, id, path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : "", 0);
        });
    put(
        m,
        4,
        plugin.module(id) ? Material.LIME_DYE : Material.GRAY_DYE,
        plugin.module(id) ? "gui.enabled" : "gui.disabled",
        "gui.click.switch",
        c ->
            complete(
                p, plugin.module(id, !plugin.module(id)), () -> settings(p, id, path, page), true));
    if (id.equals("tab") && path.isEmpty())
      put(
          m,
          50,
          Material.WRITABLE_BOOK,
          "gui.tab-export",
          "gui.click.run",
          c ->
              confirm(
                  p,
                  "gui.tab-export",
                  () -> command(p, "le tab export"),
                  () -> settings(p, id, path, page),
                  this::admin));
    p.openInventory(m.inventory);
  }

  private List<String> settingChoices(String module, String path) {
    if (module.equals("tab") && path.equals("engine")) return List.of("native", "tab");
    if (module.equals("tab") && path.startsWith("bossbars.")) {
      if (path.endsWith(".color"))
        return Arrays.stream(net.kyori.adventure.bossbar.BossBar.Color.values())
            .map(Enum::name)
            .toList();
      if (path.endsWith(".overlay"))
        return Arrays.stream(net.kyori.adventure.bossbar.BossBar.Overlay.values())
            .map(Enum::name)
            .toList();
    }
    if (module.equals("profile") && path.startsWith("cards.") && path.endsWith(".material"))
      return Arrays.stream(Material.values())
          .filter(m -> m.isItem() && !m.isAir())
          .map(Enum::name)
          .sorted()
          .toList();
    return List.of();
  }

  private void settingPicker(
      Player p, String module, String path, Object expected, int page, Runnable back) {
    var menu = menu(p, "settings-help.choice-title", Map.of("path", path), this::admin);
    var choices = settingChoices(module, path);
    int count = Math.max(0, Math.min(21, choices.size() - page * 21));
    for (int i = 0; i < count; i++) {
      String value = choices.get(page * 21 + i);
      Material icon = module.equals("profile") ? Material.matchMaterial(value) : Material.PAPER;
      Component title =
          lang.contains("settings-help.choices." + value)
              ? lang.render("settings-help.choices." + value)
              : module.equals("profile")
                  ? Component.translatable(icon.translationKey())
                  : lang.render("gui.label", Map.of("name", value));
      entry(
          menu,
          MenuStyle.slot(i, count),
          icon,
          title,
          List.of(lang.render("gui.click.select")),
          c -> saveSetting(p, module, path, expected, value, back));
    }
    navigation(
        menu,
        p,
        page,
        choices.size(),
        n -> settingPicker(p, module, path, expected, n, back),
        back);
    p.openInventory(menu.inventory);
  }

  private static Object parse(Object previous, String input) {
    if (previous instanceof Map<?, ?> || previous instanceof List<?>)
      throw new IllegalArgumentException("errors.complex-setting");
    if (previous instanceof Integer) return Integer.parseInt(input);
    if (previous instanceof Long) return Long.parseLong(input);
    if (previous instanceof Number) {
      double n = Double.parseDouble(input);
      if (!Double.isFinite(n)) throw new IllegalArgumentException("errors.input");
      return n;
    }
    return input.equals("-") ? "" : input;
  }

  private void list(Player p, String module, String path, List<?> values, int page, Runnable back) {
    var m = menu(p, "gui.list-title", Map.of("path", path), this::admin);
    for (int i = page * 21; i < Math.min(values.size(), (page + 1) * 21); i++) {
      int index = i;
      entry(
          m,
          MenuStyle.slot(i % 21, Math.min(21, values.size() - page * 21)),
          Material.PAPER,
          lang.render("gui.label", Map.of("name", String.valueOf(values.get(i)))),
          List.of(
              lang.render("gui.separator"),
              lang.render("gui.click.edit"),
              lang.render("gui.click.remove")),
          c -> {
            if (c.isRightClick())
              confirm(
                  p,
                  "gui.remove",
                  () -> {
                    var next = new ArrayList<>(values);
                    next.remove(index);
                    saveSetting(p, module, path, values, next, back);
                  },
                  () -> list(p, module, path, values, page, back),
                  this::admin);
            else
              ask(
                  p,
                  "gui.prompt-value",
                  this::admin,
                  input -> {
                    var next = new ArrayList<Object>(values);
                    next.set(index, parse(values.get(index), input));
                    saveSetting(p, module, path, values, next, back);
                  },
                  () -> list(p, module, path, values, page, back),
                  lang.render("gui.path", Map.of("path", path + "[" + (index + 1) + "]")),
                  String.valueOf(values.get(index)),
                  String.valueOf(values.get(index)));
          });
    }
    navigation(m, p, page, values.size(), n -> list(p, module, path, values, n, back), back);
    put(
        m,
        50,
        Material.LIME_DYE,
        "gui.add",
        "gui.click.edit",
        c ->
            ask(
                p,
                "gui.prompt-value",
                this::admin,
                input -> {
                  var next = new ArrayList<Object>(values);
                  next.add(values.isEmpty() ? input : parse(values.getFirst(), input));
                  saveSetting(p, module, path, values, next, back);
                },
                () -> list(p, module, path, values, page, back),
                lang.render("gui.path", Map.of("path", path)),
                values.isEmpty() ? "" : String.valueOf(values.getFirst()),
                ""));
    p.openInventory(m.inventory);
  }

  private void saveSetting(
      Player p, String module, String path, Object expected, Object value, Runnable back) {
    complete(p, plugin.editSetting(module, path, expected, value), back, true);
  }

  public void npcList(Player p, int page) {
    var m = menu(p, "gui.npcs", Map.of(), this::npcView);
    var list =
        npcs.all().stream()
            .sorted(Comparator.comparing(dev.lovelace.loveessential.api.NpcDefinition::id))
            .toList();
    for (int i = page * 21; i < Math.min(list.size(), (page + 1) * 21); i++) {
      var n = list.get(i);
      entry(
          m,
          MenuStyle.slot(i % 21, Math.min(21, list.size() - page * 21)),
          Material.PLAYER_HEAD,
          lang.render(n.nameKey()),
          List.of(
              lang.render("gui.npc-id", Map.of("id", n.id())),
                  lang.render(n.enabled() ? "gui.enabled" : "gui.disabled"),
              lang.render("gui.separator"), lang.render("gui.click.open")),
          c -> npc(p, n.id()));
    }
    navigation(m, p, page, list.size(), n -> npcList(p, n), () -> home(p));
    put(m, 4, Material.WRITABLE_BOOK, "gui.resume", "gui.click.open", c -> wizard.open(p));
    put(
        m,
        50,
        Material.LIME_DYE,
        "gui.create-npc",
        "gui.click.edit",
        c ->
            ask(
                p,
                "gui.prompt-id",
                who ->
                    who.hasPermission("loveessential.npc.create")
                        && who.hasPermission("loveessential.npc.edit")
                        && plugin.module("npc"),
                id -> wizard.create(p, id),
                () -> npcList(p, page),
                lang.render("gui.create-npc"),
                "npc_01",
                ""));
    if (!p.hasPermission("loveessential.npc.create") || !p.hasPermission("loveessential.npc.edit"))
      locked(m, 50);
    if (!p.hasPermission("loveessential.npc.edit")) locked(m, 4);
    p.openInventory(m.inventory);
  }

  private void npc(Player p, String id) {
    var n = npcs.get(id).orElseThrow(() -> new IllegalArgumentException("errors.not-found"));
    var m = menu(p, "gui.npc-title", Map.of("id", id), this::npcView);
    put(m, 19, Material.WRITABLE_BOOK, "gui.edit-npc", "gui.click.open", c -> wizard.edit(p, id));
    put(m, 21, Material.RAIL, "gui.path-npc", "gui.click.open", c -> wizard.edit(p, id, 4));
    put(m, 23, Material.BOOK, "gui.dialog-npc", "gui.click.open", c -> wizard.edit(p, id, 5));
    put(m, 25, Material.CLOCK, "gui.schedule-npc", "gui.click.open", c -> wizard.edit(p, id, 6));
    put(
        m,
        29,
        Material.LEVER,
        n.enabled() ? "gui.enabled" : "gui.disabled",
        "gui.click.switch",
        c -> command(p, "npc " + (n.enabled() ? "disable " : "enable ") + id));
    put(
        m,
        31,
        Material.COMPASS,
        "gui.debug-npc",
        "gui.click.run",
        c -> command(p, "npc debug " + id));
    put(
        m,
        33,
        Material.BARRIER,
        "gui.delete-npc",
        "gui.click.delete",
        c -> {
          confirm(
              p,
              "gui.delete-npc",
              () ->
                  complete(
                      p,
                      npcs.delete(id, n.revision(), p.getUniqueId().toString()),
                      () -> npcList(p, 0),
                      false),
              () -> npc(p, id),
              who -> plugin.module("npc") && who.hasPermission("loveessential.npc.delete"),
              id);
        });
    if (!p.hasPermission("loveessential.npc.edit"))
      for (int slot : new int[] {19, 21, 23, 25}) locked(m, slot);
    if (!p.hasPermission("loveessential.npc.publish")) locked(m, 29);
    if (!p.hasPermission("loveessential.npc.debug")) locked(m, 31);
    if (!p.hasPermission("loveessential.npc.delete")) locked(m, 33);
    navigation(m, p, 0, 0, k -> {}, () -> npcList(p, 0));
    p.openInventory(m.inventory);
  }

  public void places(Player p, String kind, int page) {
    Predicate<Player> allowed =
        who -> plugin.module(kind) && (!Set.of("portal", "hologram").contains(kind) || admin(who));
    var m =
        menu(
            p, "gui.places-title", Map.of("kind", lang.text("gui.module-labels." + kind)), allowed);
    var names = gameplay.names(p, kind);
    for (int i = page * 21; i < Math.min(names.size(), (page + 1) * 21); i++) {
      String id = names.get(i);
      entry(
          m,
          MenuStyle.slot(i % 21, Math.min(21, names.size() - page * 21)),
          Material.ENDER_PEARL,
          lang.render("gui.label", Map.of("name", id)),
          List.of(lang.render("gui.separator"), lang.render("gui.click.open")),
          c -> {
            if (kind.equals("home") || kind.equals("spawn")) command(p, kind + " " + id);
            else place(p, kind, id);
          });
    }
    navigation(m, p, page, names.size(), n -> places(p, kind, n), () -> home(p));
    if (!kind.equals("spawn") || admin(p))
      put(
          m,
          50,
          Material.LIME_DYE,
          "gui.add",
          "gui.click.edit",
          c ->
              ask(
                  p,
                  "gui.prompt-id",
                  allowed,
                  id -> {
                    if (!id.matches("[a-z0-9_-]{1,48}"))
                      throw new IllegalArgumentException("validation.id");
                    if (kind.equals("hologram"))
                      ask(
                          p,
                          "gui.prompt-text",
                          allowed,
                          text -> command(p, "hologram create " + id + " " + text),
                          () -> places(p, kind, page));
                    else
                      command(
                          p,
                          switch (kind) {
                            case "home" -> "sethome " + id;
                            case "spawn" -> "setspawn " + id;
                            default -> "portal create " + id;
                          });
                  },
                  () -> places(p, kind, page)));
    if (kind.equals("portal"))
      put(m, 4, Material.WOODEN_AXE, "gui.pos1", "gui.click.run", c -> command(p, "portal pos1"));
    p.openInventory(m.inventory);
  }

  private void place(Player p, String kind, String id) {
    var m = menu(p, "gui.place-title", Map.of("id", id), who -> admin(who) && plugin.module(kind));
    put(
        m,
        20,
        Material.COMPASS,
        kind.equals("portal") ? "gui.destination" : "gui.move",
        "gui.click.run",
        c -> command(p, kind + (kind.equals("portal") ? " destination " : " move ") + id));
    if (kind.equals("hologram"))
      put(
          m,
          22,
          Material.WRITABLE_BOOK,
          "gui.add-line",
          "gui.click.edit",
          c ->
              ask(
                  p,
                  "gui.prompt-text",
                  this::admin,
                  text -> command(p, "hologram addline " + id + " " + text),
                  () -> place(p, kind, id)));
    put(
        m,
        24,
        Material.BARRIER,
        "gui.remove",
        "gui.click.delete",
        c ->
            confirm(
                p,
                "gui.remove",
                () -> command(p, kind + " delete " + id),
                () -> place(p, kind, id),
                this::admin,
                id));
    navigation(m, p, 0, 0, n -> {}, () -> places(p, kind, 0));
    p.openInventory(m.inventory);
  }

  public void skin(Player p) {
    Predicate<Player> allowed = who -> plugin.module("skin");
    var m = menu(p, "gui.skin-title", Map.of(), allowed);
    put(
        m,
        20,
        Material.PLAYER_HEAD,
        "gui.skin-name",
        "gui.click.edit",
        c -> ask(p, "gui.prompt-skin", allowed, name -> command(p, "skin " + name), () -> skin(p)));
    put(
        m,
        24,
        Material.SUNFLOWER,
        "gui.skin-reset",
        "gui.click.run",
        c -> command(p, "skin " + p.getName()));
    navigation(m, p, 0, 0, n -> {}, () -> commands(p, 0));
    p.openInventory(m.inventory);
  }

  public void nickname(Player p) {
    nicknames.settings(p);
    var m = menu(p, "nick.title", Map.of(), nicknames::canUse);
    m.inventory.setItem(
        4,
        dev.lovelace.loveessential.module.PlayerProfiles.head(
            p,
            nicknames.display(p),
            List.of(
                lang.render("nick.real-name", Map.of("name", p.getName())),
                lang.render("nick.preview"))));
    put(
        m,
        19,
        Material.NAME_TAG,
        "nick.set",
        "gui.click.edit",
        c ->
            ask(
                p,
                "nick.prompt-name",
                nicknames::canUse,
                value -> nicknameChange(p, "set", value),
                () -> nickname(p)));
    put(m, 21, Material.YELLOW_DYE, "nick.color", "gui.click.open", c -> nicknameColors(p));
    put(
        m,
        23,
        Material.FIREWORK_STAR,
        "nick.gradient",
        "gui.click.open",
        c -> nicknameGradients(p, 0));
    put(
        m,
        25,
        Material.MILK_BUCKET,
        "nick.clear-style",
        "gui.click.run",
        c -> nicknameChange(p, "color", "reset"));
    put(
        m,
        31,
        Material.BARRIER,
        "nick.reset",
        "gui.click.run",
        c ->
            confirm(
                p,
                "nick.reset",
                () -> nicknameChange(p, "reset"),
                () -> nickname(p),
                nicknames::canUse));
    if (!p.hasPermission("loveessential.nick.color")) locked(m, 21);
    if (!p.hasPermission("loveessential.nick.gradient")) locked(m, 23);
    navigation(m, p, 0, 0, n -> {}, () -> home(p));
    p.openInventory(m.inventory);
  }

  private void nicknameChange(Player p, String... args) {
    complete(p, nicknames.change(p, p, args), () -> nickname(p), false);
  }

  private void nicknameColors(Player p) {
    Predicate<Player> allowed =
        who -> nicknames.canUse(who) && who.hasPermission("loveessential.nick.color");
    var m = menu(p, "nick.color", Map.of(), allowed);
    var colors =
        net.kyori.adventure.text.format.NamedTextColor.NAMES.keys().stream().sorted().toList();
    for (int i = 0; i < colors.size(); i++) {
      String color = colors.get(i);
      entry(
          m,
          MenuStyle.slot(i, colors.size()),
          Material.WHITE_DYE,
          lang.render("nick.colors." + color)
              .color(dev.lovelace.loveessential.module.Nicknames.color(color)),
          List.of(
              dev.lovelace.loveessential.module.Nicknames.render(
                  nicknames.values(p.getUniqueId()).getOrDefault("nickname_plain", p.getName()),
                  List.of(color)),
              lang.render("gui.separator"),
              lang.render("gui.click.run")),
          c -> nicknameChange(p, "color", color));
    }
    put(
        m,
        50,
        Material.WRITABLE_BOOK,
        "nick.custom-color",
        "gui.click.edit",
        c ->
            ask(
                p,
                "nick.prompt-color",
                allowed,
                value -> nicknameChange(p, "color", value),
                () -> nicknameColors(p)));
    navigation(m, p, 0, 0, n -> {}, () -> nickname(p));
    p.openInventory(m.inventory);
  }

  private void nicknameGradients(Player p, int page) {
    Predicate<Player> allowed =
        who -> nicknames.canUse(who) && who.hasPermission("loveessential.nick.gradient");
    var m = menu(p, "nick.gradient", Map.of(), allowed);
    var section = plugin.getConfig().getConfigurationSection("nickname.gradients");
    var keys =
        section == null ? List.<String>of() : section.getKeys(false).stream().sorted().toList();
    int count = Math.max(0, Math.min(21, keys.size() - page * 21));
    for (int i = 0; i < count; i++) {
      String key = keys.get(page * 21 + i);
      var colors = section.getStringList(key);
      String plain = nicknames.values(p.getUniqueId()).getOrDefault("nickname_plain", p.getName());
      var preview = dev.lovelace.loveessential.module.Nicknames.render(plain, colors);
      entry(
          m,
          MenuStyle.slot(i, count),
          Material.FIREWORK_STAR,
          lang.contains("nick.gradients." + key)
              ? lang.render("nick.gradients." + key)
              : lang.render("gui.label", Map.of("name", key)),
          List.of(preview, lang.render("gui.separator"), lang.render("gui.click.run")),
          c -> {
            var args = new ArrayList<String>();
            args.add("gradient");
            args.addAll(colors);
            nicknameChange(p, args.toArray(String[]::new));
          });
    }
    put(
        m,
        50,
        Material.WRITABLE_BOOK,
        "nick.custom-gradient",
        "gui.click.edit",
        c ->
            ask(
                p,
                "nick.prompt-gradient",
                allowed,
                value -> {
                  var args = new ArrayList<>(List.of("gradient"));
                  args.addAll(Arrays.asList(value.trim().split("\\s+")));
                  nicknameChange(p, args.toArray(String[]::new));
                },
                () -> nicknameGradients(p, page)));
    navigation(m, p, page, keys.size(), n -> nicknameGradients(p, n), () -> nickname(p));
    p.openInventory(m.inventory);
  }

  public void commands(Player p, int page) {
    var names = plugin.getDescription().getCommands().keySet().stream().sorted().toList();
    var m = menu(p, "gui.commands", Map.of(), who -> true);
    for (int i = page * 21; i < Math.min(names.size(), (page + 1) * 21); i++) {
      String name = names.get(i);
      entry(
          m,
          MenuStyle.slot(i % 21, Math.min(21, names.size() - page * 21)),
          Material.BOOK,
          lang.render(
              "gui.command-name",
              Map.of("command", name.equals("loveessential") ? "le" : "le " + name)),
          List.of(
              lang.render("gui.command-help." + name),
              lang.render("gui.separator"),
              lang.render("gui.click.run")),
          c -> {
            switch (name) {
              case "loveessential" -> home(p);
              case "npc" -> npcList(p, 0);
              case "homes", "home", "delhome" -> {
                if (name.equals("delhome"))
                  ask(
                      p,
                      "gui.prompt-id",
                      who -> true,
                      id ->
                          confirm(
                              p,
                              "gui.remove",
                              () -> command(p, "delhome " + id),
                              () -> commands(p, page),
                              who -> true,
                              id),
                      () -> commands(p, page));
                else places(p, "home", 0);
              }
              case "portal", "hologram" -> places(p, name, 0);
              case "skin" -> skin(p);
              case "nick" -> nickname(p);
              case "tpa", "tpahere", "sethome", "setspawn", "reply" ->
                  ask(
                      p,
                      "gui.prompt-value",
                      who -> true,
                      input -> command(p, name + " " + input),
                      () -> commands(p, page));
              default -> command(p, name);
            }
          });
    }
    navigation(m, p, page, names.size(), n -> commands(p, n), () -> home(p));
    p.openInventory(m.inventory);
  }

  private void confirm(
      Player p, String title, Runnable action, Runnable back, Predicate<Player> allowed) {
    confirm(p, title, action, back, allowed, "");
  }

  private void confirm(
      Player p,
      String title,
      Runnable action,
      Runnable back,
      Predicate<Player> allowed,
      String object) {
    var m = menu(p, title, Map.of(), allowed);
    entry(
        m,
        22,
        Material.PAPER,
        lang.render("gui.label", Map.of("name", object)),
        List.of(lang.render("gui.confirm-warning")),
        c -> {});
    put(
        m,
        20,
        Material.LIME_CONCRETE,
        "gui.confirm",
        "gui.click.run",
        c -> {
          p.closeInventory();
          action.run();
        });
    put(m, 24, Material.RED_CONCRETE, "gui.cancel", "gui.click.open", c -> back.run());
    put(m, 52, Material.BARRIER, "gui.close", "gui.click.close", c -> p.closeInventory());
    p.openInventory(m.inventory);
  }

  public void clearInput(Player p) {
    cancelInput(p);
    wizard.cancelInput(p);
  }

  public void request(
      Player p,
      String key,
      String example,
      String current,
      Predicate<Player> allowed,
      Consumer<String> input,
      Runnable back) {
    ask(p, key, allowed, input, back, lang.render("content.recipe-input-context"), example, current);
  }

  private void ask(
      Player p, String key, Predicate<Player> allowed, Consumer<String> input, Runnable back) {
    ask(
        p,
        key,
        allowed,
        input,
        back,
        lang.render("input.contexts." + key.replace('.', '-')),
        lang.contains("input.examples." + key.replace('.', '-'))
            ? lang.text("input.examples." + key.replace('.', '-'))
            : "",
        "");
  }

  private void ask(
      Player p,
      String key,
      Predicate<Player> allowed,
      Consumer<String> input,
      Runnable back,
      Component context,
      String example,
      String current) {
    if (!allowed.test(p) || !love.authenticated(p))
      throw new IllegalArgumentException("errors.permission");
    wizard.cancelInput(p);
    p.closeInventory();
    var prompt =
        new Prompt(
            System.currentTimeMillis() + 180000,
            allowed,
            input,
            back,
            UUID.randomUUID().toString(),
            context,
            key,
            example,
            current);
    prompts.put(p.getUniqueId(), prompt);
    sendPrompt(p, prompt);
  }

  private void sendPrompt(Player p, Prompt prompt) {
    ChatPrompt.send(
        p,
        lang,
        prompt.token,
        prompt.context,
        lang.render(prompt.instructionKey),
        prompt.example,
        prompt.current);
  }

  public boolean cancelPrompt(Player p, String token) {
    var prompt = prompts.get(p.getUniqueId());
    if (prompt == null || !prompt.token.equals(token)) return false;
    return input(p, "cancel");
  }

  public boolean input(Player p, String value) {
    var prompt = prompts.remove(p.getUniqueId());
    if (prompt == null) return false;
    if (prompt.expires < System.currentTimeMillis()) {
      lang.send(p, "input.expired");
      return true;
    }
    try {
      if (!plugin.ready() || !love.authenticated(p) || !prompt.allowed.test(p))
        throw new IllegalArgumentException("errors.permission");
      if (value.equalsIgnoreCase("cancel") || value.equalsIgnoreCase("отмена")) {
        lang.send(p, "input.cancelled");
        prompt.back.run();
      } else prompt.input.accept(value);
    } catch (RuntimeException failure) {
      error(p, failure);
      if (plugin.ready() && love.authenticated(p) && prompt.allowed.test(p)) {
        prompts.putIfAbsent(p.getUniqueId(), prompt);
        sendPrompt(p, prompts.get(p.getUniqueId()));
      }
    }
    return true;
  }

  private void number(Player p, String module, String path, Object value, Runnable back) {
    var m = menu(p, "settings-help.number-title", Map.of("path", path), this::admin);
    m.inventory.setItem(
        4,
        MenuStyle.item(
            Material.COMPARATOR,
            lang.render("gui.label", Map.of("name", path)),
            List.of(
                lang.render("gui.value", Map.of("value", value.toString())),
                lang.render("settings-help.number"))));
    double step = value instanceof Double || value instanceof Float ? .1 : 1;
    int[] changes = {-10, -5, -1, 1, 5, 10};
    for (int i = 0; i < changes.length; i++) {
      int delta = changes[i];
      entry(
          m,
          MenuStyle.slot(i, changes.length),
          delta < 0 ? Material.RED_DYE : Material.LIME_DYE,
          lang.render(
              "settings-help.change",
              Map.of(
                  "amount",
                  (delta > 0 ? "+" : "")
                      + java.math.BigDecimal.valueOf(delta * step)
                          .stripTrailingZeros()
                          .toPlainString())),
          List.of(lang.render("gui.click.run")),
          c -> {
            String next =
                java.math.BigDecimal.valueOf(((Number) value).doubleValue())
                    .add(java.math.BigDecimal.valueOf(delta * step))
                    .stripTrailingZeros()
                    .toPlainString();
            saveSetting(
                p,
                module,
                path,
                value,
                parse(value, next),
                () ->
                    number(
                        p,
                        module,
                        path,
                        dev.lovelace.loveessential.config.ModuleConfigs.view(
                                plugin.getConfig(), module)
                            .get(path),
                        back));
          });
    }
    put(
        m,
        50,
        Material.WRITABLE_BOOK,
        "gui.manual-input",
        "gui.click.edit",
        c ->
            ask(
                p,
                "gui.prompt-value",
                this::admin,
                input -> saveSetting(p, module, path, value, parse(value, input), back),
                () -> number(p, module, path, value, back),
                lang.render("gui.path", Map.of("path", path)),
                value.toString(),
                value.toString()));
    navigation(m, p, 0, 0, n -> {}, back);
    p.openInventory(m.inventory);
  }

  private void complete(Player p, CompletableFuture<?> future, Runnable back, boolean adminOnly) {
    if (adminOnly && !admin(p)) throw new IllegalArgumentException("errors.permission");
    p.closeInventory();
    future.whenComplete(
        (v, e) ->
            main.execute(
                () -> {
                  if (!p.isOnline()) return;
                  if (e != null) error(p, e);
                  else lang.send(p, "gui.saved");
                  if (love.authenticated(p) && (!adminOnly || admin(p))) {
                    try {
                      back.run();
                    } catch (RuntimeException failure) {
                      error(p, failure);
                    }
                  }
                }));
  }

  private void error(Player p, Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    lang.send(p, lang.contains(e.getMessage()) ? e.getMessage() : "errors.input");
    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, .4f, 1);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)) return;
    if (e.getRawSlot() < 0 || e.getRawSlot() >= 54) return;
    var action = m.actions.get(e.getRawSlot());
    if (action == null) return;
    ClickType click = e.getClick();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!p.isOnline()
                  || p.getOpenInventory().getTopInventory() == null
                  || p.getOpenInventory().getTopInventory().getHolder() != m) return;
              try {
                if (!plugin.ready() || !love.authenticated(p) || !m.allowed.test(p))
                  throw new IllegalArgumentException("errors.permission");
                action.accept(click);
              } catch (RuntimeException ex) {
                error(p, ex);
              }
            });
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void chat(AsyncChatEvent e) {
    var prompt = prompts.get(e.getPlayer().getUniqueId());
    if (prompt == null) return;
    if (prompt.expires < System.currentTimeMillis()) {
      prompts.remove(e.getPlayer().getUniqueId(), prompt);
      e.setCancelled(true);
      main.execute(() -> lang.send(e.getPlayer(), "input.expired"));
      return;
    }
    e.setCancelled(true);
    String value = PlainTextComponentSerializer.plainText().serialize(e.message());
    main.execute(() -> input(e.getPlayer(), value));
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    prompts.remove(e.getPlayer().getUniqueId());
    roots.remove(e.getPlayer().getUniqueId());
  }

  public void close() {
    HandlerList.unregisterAll(this);
    prompts.clear();
    roots.clear();
    direct.clear();
    dispatching.clear();
  }
}
