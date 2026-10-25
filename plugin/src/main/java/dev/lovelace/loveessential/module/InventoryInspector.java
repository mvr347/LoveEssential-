package dev.lovelace.loveessential.module;

import dev.lovelace.loveessential.LoveEssentialPlugin;
import dev.lovelace.loveessential.gui.MenuStyle;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.BukkitTask;

/** A live administrative view: never writes a stored inventory snapshot back on close. */
public final class InventoryInspector implements Listener, AutoCloseable {
  private final LoveEssentialPlugin plugin;
  private final Lang lang;
  private final LoveBridge love;
  private final Map<UUID, Session> sessions = new HashMap<>();
  private BukkitTask task;
  private int ticks;

  private static final class Session implements InventoryHolder {
    final Player viewer, target;
    Inventory inventory;
    boolean editing;

    /** A search of a downed player: items and a close button only. */
    boolean simple() {
      return robbery != null;
    }
    /** Set for robbing a downed player: replaces the invsee permission and module checks. */
    java.util.function.BiPredicate<Player, Player> robbery;

    Session(Player viewer, Player target, boolean editing) {
      this.viewer = viewer;
      this.target = target;
      this.editing = editing;
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  public InventoryInspector(LoveEssentialPlugin plugin, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.lang = lang;
    this.love = love;
  }

  public void start() {
    Bukkit.getPluginManager().registerEvents(this, plugin);
    task =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  ticks++;
                  for (var s : List.copyOf(sessions.values())) {
                    if (!allowed(s)) {
                      s.viewer.closeInventory();
                      continue;
                    }
                    if (ticks % Math.max(1, plugin.getConfig().getInt("inventory.refresh-ticks", 2))
                        == 0) refresh(s);
                  }
                },
                1,
                1);
  }

  private boolean allowed(Session s) {
    if (s.robbery != null)
      return plugin.ready()
          && s.viewer.isOnline()
          && s.target.isOnline()
          && !s.viewer.isDead()
          && !s.target.isDead()
          && love.authenticated(s.viewer)
          && s.robbery.test(s.viewer, s.target);
    return plugin.ready()
        && plugin.allows("inventory", s.viewer)
        && s.viewer.isOnline()
        && s.target.isOnline()
        && !s.viewer.isDead()
        && !s.target.isDead()
        && s.viewer.canSee(s.target)
        && love.authenticated(s.viewer)
        && love.authenticated(s.target)
        && s.viewer.hasPermission("loveessential.invsee")
        && (!s.editing || s.viewer.hasPermission("loveessential.invsee.edit"));
  }

  public void open(Player viewer, Player target, boolean editing) {
    plugin.requireAccess("inventory", viewer);
    open(viewer, target, editing, null);
  }

  /**
   * Opens the live inventory of a downed player for a robber. {@code valid} is asked every tick, so
   * the window closes the moment the victim is revived, dies or the robber loses the right.
   */
  public void rob(Player robber, Player victim, java.util.function.BiPredicate<Player, Player> valid) {
    open(robber, victim, true, java.util.Objects.requireNonNull(valid));
  }

  private void open(
      Player viewer,
      Player target,
      boolean editing,
      java.util.function.BiPredicate<Player, Player> robbery) {
    if (viewer.equals(target)) throw new IllegalArgumentException("inventory.self");
    var s = new Session(viewer, target, editing);
    s.robbery = robbery;
    if (!allowed(s)) throw new IllegalArgumentException("errors.permission");
    s.inventory =
        Bukkit.createInventory(
            s,
            54,
            lang.render(
                s.simple() ? "revive.rob-title" : "inventory.title", Map.of("player", target.getName())));
    for (int i = 41; i < 54; i++)
      s.inventory.setItem(
          i,
          MenuStyle.item(
              Material.GRAY_STAINED_GLASS_PANE,
              net.kyori.adventure.text.Component.empty(),
              List.of()));
    if (s.simple()) {
      // A search of a downed player is just the items and a close button: no mode switch, no manual.
      refresh(s);
      s.inventory.setItem(
          49, MenuStyle.button(lang, Material.BARRIER, "gui.close", "gui.click.close"));
      viewer.openInventory(s.inventory);
      sessions.put(viewer.getUniqueId(), s);
      return;
    }
    s.inventory.setItem(
        45,
        PlayerProfiles.head(
            target,
            lang.render("inventory.owner", Map.of("player", target.getName())),
            lang.lines("inventory.layout").stream()
                .map(
                    line ->
                        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize(line))
                .toList()));
    s.inventory.setItem(
        53, MenuStyle.button(lang, Material.BARRIER, "gui.close", "gui.click.close"));
    refresh(s);
    MenuStyle.special(viewer, s.inventory);
    if (MenuStyle.hasParent(viewer))
      s.inventory.setItem(
          52, MenuStyle.button(lang, Material.ARROW, "common.back", "gui.click.open"));
    if (viewer.openInventory(s.inventory) != null) sessions.put(viewer.getUniqueId(), s);
  }

  private void refresh(Session s) {
    for (int i = 0; i <= 40; i++)
      s.inventory.setItem(i, copy(s.target.getInventory().getItem(targetSlot(i))));
    if (s.simple()) return;
    s.inventory.setItem(
        49,
        MenuStyle.button(
            lang,
            Material.BOOK,
            "inventory.instructions",
            "inventory.controls-left",
            "inventory.controls-right",
            "inventory.controls-restricted"));
    s.inventory.setItem(
        51,
        MenuStyle.button(
            lang,
            s.editing ? Material.LIME_DYE : Material.GRAY_DYE,
            s.editing ? "inventory.editing" : "inventory.readonly",
            "inventory.toggle"));
  }

  // Main storage, then hotbar, boots/leggings/chestplate/helmet and offhand.
  static int targetSlot(int raw) {
    return raw < 27 ? raw + 9 : raw < 36 ? raw - 27 : raw;
  }

  public record Transfer(ItemStack slot, ItemStack cursor) {}

  /** Pure left/right transaction. Inputs are never mutated. */
  public static Transfer transfer(ItemStack slot, ItemStack cursor, boolean right, int limit) {
    ItemStack a = copy(slot), b = copy(cursor);
    if (b == null) {
      if (a == null) return new Transfer(null, null);
      int take = right ? (a.getAmount() + 1) / 2 : a.getAmount();
      b = a.clone();
      b.setAmount(take);
      a.setAmount(a.getAmount() - take);
    } else if (a == null || a.isSimilar(b)) {
      int capacity = Math.min(limit, b.getMaxStackSize());
      int amount = Math.min(right ? 1 : b.getAmount(), capacity - (a == null ? 0 : a.getAmount()));
      if (amount > 0) {
        if (a == null) {
          a = b.clone();
          a.setAmount(amount);
        } else a.setAmount(a.getAmount() + amount);
        b.setAmount(b.getAmount() - amount);
      }
    } else if (b.getAmount() <= Math.min(limit, b.getMaxStackSize())) {
      ItemStack tmp = a;
      a = b;
      b = tmp;
    }
    return new Transfer(copy(a), copy(b));
  }

  private static ItemStack copy(ItemStack item) {
    return item == null || item.getType().isAir() || item.getAmount() <= 0 ? null : item.clone();
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Session s)) return;
    boolean previouslyCancelled = e.isCancelled();
    e.setCancelled(true);
    if (previouslyCancelled
        || !e.getWhoClicked().getUniqueId().equals(s.viewer.getUniqueId())
        || !allowed(s)) return;
    int raw = e.getRawSlot();
    if (raw < 0 || (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT)) return;
    if (raw >= 54) {
      // Vanilla handles only ordinary cursor transfers within the viewer's own inventory.
      // Shift, hotkeys, collect-to-cursor, creative cloning, drop and drag remain blocked.
      if (Set.of(
              InventoryAction.PICKUP_ALL,
              InventoryAction.PICKUP_HALF,
              InventoryAction.PICKUP_ONE,
              InventoryAction.PICKUP_SOME,
              InventoryAction.PLACE_ALL,
              InventoryAction.PLACE_ONE,
              InventoryAction.PLACE_SOME,
              InventoryAction.SWAP_WITH_CURSOR,
              InventoryAction.NOTHING)
          .contains(e.getAction())) e.setCancelled(false);
      return;
    }
    if (s.simple()) {
      if (raw == 49)
        Bukkit.getScheduler()
            .runTask(
                plugin,
                () -> {
                  if (sessions.get(s.viewer.getUniqueId()) == s) s.viewer.closeInventory();
                });
      if (raw > 40) return;
    } else if (raw == 53 || raw == 51 || raw == 52) {
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (sessions.get(s.viewer.getUniqueId()) != s || !allowed(s)) return;
                if (raw == 53) s.viewer.closeInventory();
                else if (raw == 52) MenuStyle.back(s.viewer);
                else if (s.viewer.hasPermission("loveessential.invsee.edit")) {
                  s.editing = !s.editing;
                  refresh(s);
                } else lang.send(s.viewer, "errors.permission");
              });
      return;
    }
    if (raw > 40 || !s.editing) return;
    int slot = targetSlot(raw);
    ItemStack current = copy(s.target.getInventory().getItem(slot));
    if (!Objects.equals(current, copy(s.inventory.getItem(raw)))) {
      refresh(s);
      lang.send(s.viewer, "inventory.changed");
      return;
    }
    var result =
        transfer(
            current,
            s.viewer.getItemOnCursor(),
            e.getClick() == ClickType.RIGHT,
            slot >= 36 && slot <= 39 ? 1 : s.target.getInventory().getMaxStackSize());
    // Both sides change synchronously on the server thread; no async read/modify/write window.
    s.target.getInventory().setItem(slot, result.slot());
    s.viewer.setItemOnCursor(result.cursor());
    refresh(s);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Session) e.setCancelled(true);
  }

  @EventHandler
  public void closed(InventoryCloseEvent e) {
    if (e.getInventory().getHolder() instanceof Session s)
      sessions.remove(s.viewer.getUniqueId(), s);
  }

  private void invalidate(Player player) {
    for (var s : List.copyOf(sessions.values()))
      if (s.viewer.equals(player) || s.target.equals(player)) s.viewer.closeInventory();
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    invalidate(e.getPlayer());
  }

  @EventHandler
  public void death(PlayerDeathEvent e) {
    invalidate(e.getEntity());
  }

  @Override
  public void close() {
    if (task != null) task.cancel();
    for (var s : List.copyOf(sessions.values())) s.viewer.closeInventory();
    sessions.clear();
    HandlerList.unregisterAll(this);
  }
}
