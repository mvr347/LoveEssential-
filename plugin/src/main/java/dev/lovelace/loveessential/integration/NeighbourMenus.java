package dev.lovelace.loveessential.integration;

import dev.lovelace.loveessential.condition.ActionEngine;
import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Narrow compatibility adapters for public neighbour entry points, checked on invocation. */
public final class NeighbourMenus {
  private final JavaPlugin owner;
  private final Lang lang;
  private final LoveBridge love;
  private final Map<String, String> failures = new ConcurrentHashMap<>();

  public NeighbourMenus(JavaPlugin owner, Lang lang, LoveBridge love, ActionEngine actions) {
    this.owner = owner;
    this.lang = lang;
    this.love = love;
    getter(actions, "lovecontracts:board", "LoveContracts", "getContractGUI");
    getter(actions, "lovecontracts:player-board", "LoveContracts", "getPlayerContractBoardGUI");
    getter(actions, "lovecontracts:my", "LoveContracts", "getPlayerContractMyGUI");
    actions.register(
        "loveshops:banker",
        (p, a) -> {
          Plugin plugin = Bukkit.getPluginManager().getPlugin("LoveShops");
          if (plugin == null || !plugin.isEnabled() || love.economy().isEmpty()) return false;
          try {
            Class<?> type =
                Class.forName(
                    "dev.lovelace.loveshops.gui.BankerGui",
                    true,
                    plugin.getClass().getClassLoader());
            Object gui =
                type.getConstructor(plugin.getClass(), Player.class).newInstance(plugin, p);
            type.getMethod("open").invoke(gui);
            failures.remove("loveshops:banker");
            return true;
          } catch (ReflectiveOperationException | LinkageError e) {
            failed("loveshops:banker", e);
            return false;
          }
        });
  }

  private void getter(ActionEngine actions, String id, String pluginName, String getter) {
    actions.register(
        id,
        (player, args) -> {
          Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
          if (plugin == null || !plugin.isEnabled()) return false;
          try {
            Object menu = plugin.getClass().getMethod(getter).invoke(plugin);
            if (menu == null) return false;
            menu.getClass().getMethod("open", Player.class).invoke(menu, player);
            failures.remove(id);
            return true;
          } catch (ReflectiveOperationException | LinkageError e) {
            failed(id, e);
            return false;
          }
        });
  }

  private void failed(String id, Throwable error) {
    String signature = error.getClass().getSimpleName();
    if (!signature.equals(failures.put(id, signature)))
      owner
          .getLogger()
          .warning(lang.text("logs.integration-failed") + " [" + id + "] " + signature);
  }

  public Map<String, String> failures() {
    return Map.copyOf(failures);
  }
}
