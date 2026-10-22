package dev.lovelace.loveessential.condition;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.api.LoveEssentialService.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ActionEngine {
  private final Map<String, ActionProvider> providers = new ConcurrentHashMap<>();
  private final JavaPlugin plugin;
  private final Lang lang;
  private final LoveBridge love;

  public ActionEngine(JavaPlugin plugin, Lang lang, LoveBridge love) {
    this.plugin = plugin;
    this.lang = lang;
    this.love = love;
    register(
        "charge",
        (p, a) -> {
          var e = love.economy();
          long amount = Long.parseLong(a.get("amount"));
          return love.authenticated(p)
              && amount > 0
              && amount <= plugin.getConfig().getLong("actions.max-economy-amount", 100000)
              && e.isPresent()
              && e.get().charge(p, amount);
        });
    register(
        "give",
        (p, a) -> {
          var e = love.economy();
          long amount = Long.parseLong(a.get("amount"));
          if (!love.authenticated(p)
              || amount <= 0
              || amount > plugin.getConfig().getLong("actions.max-economy-amount", 100000)
              || e.isEmpty()
              || !e.get().canFit(p, amount)) return false;
          e.get().give(p, amount);
          return true;
        });
    register(
        "message",
        (p, a) -> {
          love.notify(p, lang.render(a.get("key"), Map.of("player", p.getName())));
          return true;
        });
    register(
        "sound",
        (p, a) -> {
          p.playSound(
              p.getLocation(),
              Sound.valueOf(a.get("sound").toUpperCase(Locale.ROOT)),
              Float.parseFloat(a.getOrDefault("volume", "1")),
              Float.parseFloat(a.getOrDefault("pitch", "1")));
          return true;
        });
    register(
        "actionbar",
        (p, a) -> {
          p.sendActionBar(lang.render(a.get("key")));
          return true;
        });
    register(
        "reputation",
        (p, a) -> {
          var s = love.service(dev.lovelace.lovecore.api.social.ReputationOracle.class);
          if (s.isEmpty()) return false;
          s.get().modify(p.getUniqueId(), Integer.parseInt(a.get("delta")));
          return true;
        });
    register(
        "stat",
        (p, a) -> {
          var s = love.service(dev.lovelace.lovecore.api.stats.StatBus.class);
          if (s.isEmpty()) return false;
          s.get()
              .record(
                  p.getUniqueId(),
                  a.get("metric"),
                  Double.parseDouble(a.getOrDefault("amount", "1")));
          return true;
        });
    register(
        "potion",
        (p, a) -> {
          var type =
              org.bukkit.Registry.POTION_EFFECT_TYPE.get(
                  org.bukkit.NamespacedKey.minecraft(
                      a.getOrDefault("effect", "").toLowerCase(Locale.ROOT)));
          double seconds = Double.parseDouble(a.getOrDefault("seconds", "10"));
          int amplifier = Integer.parseInt(a.getOrDefault("amplifier", "0"));
          if (type == null || seconds <= 0 || seconds > 3600 || amplifier < 0 || amplifier > 9)
            return false;
          return p.addPotionEffect(
              new org.bukkit.potion.PotionEffect(type, (int) Math.round(seconds * 20), amplifier));
        });
    register(
        "command",
        (p, a) -> {
          String command = a.getOrDefault("value", "");
          String root = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
          if (!plugin.getConfig().getStringList("actions.allowed-player-commands").contains(root)
              || command.contains("\n")
              || command.contains("\r")) return false;
          if (a.containsKey("plugin") && !love.installed(a.get("plugin"))) return false;
          return p.performCommand(command.replace("{player}", p.getName()));
        });
  }

  public Registration register(String id, ActionProvider provider) {
    Objects.requireNonNull(provider);
    if (providers.putIfAbsent(id, provider) != null)
      throw new IllegalArgumentException("duplicate-action");
    return () -> providers.remove(id, provider);
  }

  public boolean known(String id) {
    return providers.containsKey(id);
  }

  public boolean execute(Player p, List<Action> actions) {
    MainThread.require();
    // Reject the whole list before anything is taken from the player.
    for (var a : actions)
      if (!providers.containsKey(a.type())) {
        lang.send(p, "errors.action-unavailable", Map.of("type", a.type()));
        return false;
      }
    long charged = 0;
    for (var a : actions) {
      var provider = providers.get(a.type());
      boolean done;
      try {
        done = provider != null && provider.execute(p, a.args());
      } catch (RuntimeException e) {
        plugin
            .getLogger()
            .warning(
                lang.text("logs.action-failed")
                    + " ["
                    + a.type()
                    + "] "
                    + e.getClass().getSimpleName());
        done = false;
      }
      if (!done) {
        lang.send(p, "errors.action-unavailable", Map.of("type", a.type()));
        refund(p, charged);
        return false;
      }
      if (a.type().equals("charge")) charged += chargedAmount(a);
    }
    return true;
  }

  private static long chargedAmount(Action a) {
    try {
      return Long.parseLong(a.args().get("amount"));
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /** Gives back what earlier charge actions took when a later action of the list failed. */
  private void refund(Player p, long amount) {
    if (amount <= 0) return;
    var economy = love.economy();
    if (economy.isPresent()) economy.get().give(p, amount);
    else
      plugin
          .getLogger()
          .warning("Could not refund " + amount + " to " + p.getName() + ": economy unavailable");
  }

  public List<String> types() {
    return providers.keySet().stream().sorted().toList();
  }
}
