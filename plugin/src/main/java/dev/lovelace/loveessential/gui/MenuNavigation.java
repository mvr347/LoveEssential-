package dev.lovelace.loveessential.gui;

import com.github.benmanes.caffeine.cache.*;
import dev.lovelace.loveessential.LoveEssentialPlugin;
import java.time.Duration;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/** Session-local ancestry, independent of a player's permissions or guessed command roots. */
public final class MenuNavigation implements Listener, AutoCloseable {
  private static final class Route {
    Inventory current;
    final ArrayDeque<Inventory> parents = new ArrayDeque<>();
    boolean returning, suspended, external;
  }

  private final LoveEssentialPlugin plugin;
  private final Cache<UUID, Route> routes =
      Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(Duration.ofMinutes(3)).build();
  private final Set<UUID> acting = new HashSet<>();

  public MenuNavigation(LoveEssentialPlugin plugin) {
    this.plugin = plugin;
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
  }

  public void forget(Player player) {
    routes.invalidate(player.getUniqueId());
  }

  public void command(Player player) {
    if (!acting.contains(player.getUniqueId())) routes.invalidate(player.getUniqueId());
  }

  public void action(Player player, Runnable callback) {
    boolean added = acting.add(player.getUniqueId());
    try {
      callback.run();
    } finally {
      if (added) acting.remove(player.getUniqueId());
    }
  }

  public void suspend(Player player) {
    var route = routes.getIfPresent(player.getUniqueId());
    if (route != null) route.suspended = true;
  }

  public void external(Player player) {
    var route = routes.getIfPresent(player.getUniqueId());
    if (route != null) {
      route.external = true;
      route.suspended = true;
    }
  }

  /**
   * A known foreign menu's Back command returns to the actual saved parent, including its context.
   */
  public boolean resumeExternal(Player player, String screen) {
    var route = routes.getIfPresent(player.getUniqueId());
    if (route == null || !route.external || screen.isBlank()) return false;
    Inventory target = null;
    if (route.current != null
        && route.current.getHolder() instanceof GuiLayout current
        && current.guiId().equals(screen)) target = route.current;
    if (target == null)
      for (var candidate : route.parents)
        if (candidate.getHolder() instanceof GuiLayout layout && layout.guiId().equals(screen))
          target = candidate;
    if (target == null || !((GuiLayout) target.getHolder()).guiAllowed(player)) return false;
    if (target != route.current) while (route.parents.removeLast() != target) {}
    restore(player, route, target);
    return true;
  }

  public void attach(Player player, Inventory inventory) {
    var route = routes.get(player.getUniqueId(), id -> new Route());
    if (route.current == inventory) return;
    var holder = (GuiLayout) inventory.getHolder();
    // Returning by an action (for example, choosing a filter or saving a row) must
    // consume the old ancestor too. Otherwise A -> B -> A grows a false B parent.
    if (!route.returning
        && route.current != null
        && (!(route.current.getHolder() instanceof GuiLayout current)
            || !current.guiRoute().equals(holder.guiRoute()))) {
      Inventory ancestor = null;
      for (var candidate : route.parents)
        if (candidate.getHolder() instanceof GuiLayout previous
            && previous.guiRoute().equals(holder.guiRoute())) ancestor = candidate;
      if (ancestor != null) {
        while (route.parents.removeLast() != ancestor) {}
        route.returning = true;
      }
    }
    if (!route.returning
        && route.current != null
        && route.current.getHolder() instanceof GuiLayout previous
        && !previous.guiRoute().equals(holder.guiRoute())) {
      if (route.parents.size() == 16) route.parents.removeFirst();
      route.parents.addLast(route.current);
    }
    route.current = inventory;
    route.returning = false;
    route.suspended = false;
    route.external = false;
  }

  public void special(Player player, Inventory inventory) {
    var route = routes.get(player.getUniqueId(), id -> new Route());
    if (route.current != null && route.current != inventory) {
      if (route.parents.size() == 16) route.parents.removeFirst();
      route.parents.addLast(route.current);
    }
    route.current = inventory;
    route.suspended = false;
  }

  public boolean hasParent(Player player) {
    var route = routes.getIfPresent(player.getUniqueId());
    return route != null && !route.parents.isEmpty();
  }

  public void back(Player player) {
    var route = routes.getIfPresent(player.getUniqueId());
    if (route == null || route.parents.isEmpty()) return;
    var inventory = route.parents.removeLast();
    var layout = (GuiLayout) inventory.getHolder();
    if (!layout.guiAllowed(player)) {
      var reopen = layout.guiReopen();
      if (reopen == null) {
        player.closeInventory();
        return;
      }
      // The stale parent is replaced by a fresh copy; the child must not be stacked under it.
      route.returning = true;
      try {
        reopen.run();
      } finally {
        route.returning = false;
      }
      return;
    }
    restore(player, route, inventory);
  }

  private void restore(Player player, Route route, Inventory inventory) {
    var layout = (GuiLayout) inventory.getHolder();
    route.returning = true;
    route.current = inventory;
    route.external = false;
    route.suspended = false;
    try {
      layout.guiResume();
      // Resume may rebuild/resize the parent's inventory. Never reopen its stale copy.
      MenuStyle.open(player, layout.getInventory());
    } finally {
      route.returning = false;
    }
  }

  @EventHandler
  public void closed(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) return;
    if (!plugin.isEnabled() || !plugin.ready()) {
      routes.invalidate(player.getUniqueId());
      return;
    }
    var route = routes.getIfPresent(player.getUniqueId());
    if (route == null) return;
    if (route.external && !(event.getInventory().getHolder() instanceof GuiLayout)) {
      plugin
          .getServer()
          .getScheduler()
          .runTask(
              plugin,
              () -> {
                if (routes.getIfPresent(player.getUniqueId()) == route
                    && route.external
                    && player.getOpenInventory().getTopInventory().getType()
                        == org.bukkit.event.inventory.InventoryType.CRAFTING)
                  routes.invalidate(player.getUniqueId());
              });
      return;
    }
    if (route.current != event.getInventory() || route.suspended) return;
    plugin
        .getServer()
        .getScheduler()
        .runTask(
            plugin,
            () -> {
              if (routes.getIfPresent(player.getUniqueId()) == route
                  && !route.suspended
                  && (player.getOpenInventory().getTopInventory() == null
                      || !(player.getOpenInventory().getTopInventory().getHolder()
                          instanceof GuiLayout))) routes.invalidate(player.getUniqueId());
            });
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    routes.invalidate(event.getPlayer().getUniqueId());
    acting.remove(event.getPlayer().getUniqueId());
  }

  public void close() {
    routes.invalidateAll();
    acting.clear();
    HandlerList.unregisterAll(this);
  }
}
