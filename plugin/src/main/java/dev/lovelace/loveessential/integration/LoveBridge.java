package dev.lovelace.loveessential.integration;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.auth.AuthOracle;
import dev.lovelace.lovecore.api.combat.CombatState;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.lovecore.api.notify.LoveNotify;
import dev.lovelace.lovecore.api.social.*;
import dev.lovelace.lovecore.api.stats.StatBus;
import dev.lovelace.lovecore.api.territory.TerritoryOracle;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Resolves live services on each call, including providers registered after startup. */
public final class LoveBridge {
  public <T> Optional<T> service(Class<T> type) {
    return LoveCore.service(type);
  }

  public boolean installed(String plugin) {
    return Bukkit.getPluginManager().isPluginEnabled(plugin);
  }

  public boolean authenticated(Player p) {
    return service(AuthOracle.class)
        .map(s -> s.isAuthenticated(p.getUniqueId()))
        .orElse(!installed("LoveAuth"));
  }

  public boolean authAvailable() {
    return service(AuthOracle.class).isPresent() || !installed("LoveAuth");
  }

  public OptionalInt reputation(Player p) {
    var s = service(ReputationOracle.class);
    return s.isPresent()
        ? OptionalInt.of(s.get().reputation(p.getUniqueId()))
        : OptionalInt.empty();
  }

  public Optional<String> clan(Player p) {
    return service(ProfileOracle.class).flatMap(s -> s.clanTag(p.getUniqueId()));
  }

  public boolean territorialAccess(Player p, Location at) {
    return service(TerritoryOracle.class)
        .map(s -> !s.hostileFor(p.getUniqueId(), at))
        .orElse(!installed("LoveClaims"));
  }

  public boolean combat(Player p) {
    return service(CombatState.class)
        .map(s -> s.inCombat(p.getUniqueId()))
        .orElse(installed("LoveDuels") || installed("LoveHunt"));
  }

  public Optional<LoveEconomy> economy() {
    return service(LoveEconomy.class);
  }

  public String territory(Player p) {
    return service(TerritoryOracle.class)
        .flatMap(s -> s.ownerAt(p.getLocation()))
        .map(TerritoryOracle.Owner::displayName)
        .orElse("");
  }

  public void notify(Player p, Component text) {
    var s = service(LoveNotify.class);
    if (s.isPresent()) s.get().notifyInteractive(p, text);
    else p.sendMessage(text);
  }

  public void stat(Player p, String metric, double amount) {
    service(StatBus.class).ifPresent(s -> s.record(p.getUniqueId(), metric, amount));
  }

  public Map<String, String> status() {
    var out = new LinkedHashMap<String, String>();
    for (var t :
        List.of(
            AuthOracle.class,
            LoveEconomy.class,
            ReputationOracle.class,
            ProfileOracle.class,
            TerritoryOracle.class,
            CombatState.class,
            LoveNotify.class,
            StatBus.class))
      out.put(t.getSimpleName(), service(t).isPresent() ? "available" : "unavailable");
    return out;
  }
}
