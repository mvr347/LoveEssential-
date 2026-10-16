package dev.lovelace.loveessential.condition;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.api.LoveEssentialService.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class ConditionEngine {
  private final Map<String, ConditionProvider> providers = new ConcurrentHashMap<>();

  public ConditionEngine(LoveBridge love) {
    register(
        "balance",
        (p, a) ->
            love.economy()
                .map(e -> verdict(e.has(p, Long.parseLong(a.get("amount")))))
                .orElse(Verdict.UNAVAILABLE));
    register("permission", (p, a) -> verdict(p.hasPermission(required(a, "value"))));
    register("world", (p, a) -> verdict(p.getWorld().getName().equals(required(a, "value"))));
    register(
        "authenticated",
        (p, a) -> !love.authAvailable() ? Verdict.UNAVAILABLE : verdict(love.authenticated(p)));
    register(
        "reputation",
        (p, a) -> {
          var n = love.reputation(p);
          return n.isEmpty()
              ? Verdict.UNAVAILABLE
              : verdict(n.getAsInt() >= Integer.parseInt(required(a, "min")));
        });
    register(
        "clan",
        (p, a) ->
            love.service(dev.lovelace.lovecore.api.social.ProfileOracle.class).isEmpty()
                ? Verdict.UNAVAILABLE
                : verdict(love.clan(p).map(s -> s.equals(a.get("value"))).orElse(false)));
    register(
        "territory",
        (p, a) ->
            love.service(dev.lovelace.lovecore.api.territory.TerritoryOracle.class).isEmpty()
                    && love.installed("LoveClaims")
                ? Verdict.UNAVAILABLE
                : verdict(love.territorialAccess(p, p.getLocation())));
    register("not-in-combat", (p, a) -> verdict(!love.combat(p)));
    register(
        "item",
        (p, a) ->
            verdict(
                p.getInventory()
                    .containsAtLeast(
                        new org.bukkit.inventory.ItemStack(
                            Material.valueOf(required(a, "material").toUpperCase(Locale.ROOT))),
                        Integer.parseInt(a.getOrDefault("amount", "1")))));
    register(
        "time",
        (p, a) -> {
          long now = p.getWorld().getTime(),
              from = Long.parseLong(required(a, "from")),
              to = Long.parseLong(required(a, "to"));
          return verdict(from <= to ? now >= from && now <= to : now >= from || now <= to);
        });
    register(
        "weather",
        (p, a) ->
            verdict(
                switch (required(a, "value")) {
                  case "rain" -> p.getWorld().hasStorm();
                  case "thunder" -> p.getWorld().isThundering();
                  case "clear" -> !p.getWorld().hasStorm();
                  default -> false;
                }));
  }

  public Registration register(String id, ConditionProvider provider) {
    Objects.requireNonNull(provider);
    if (providers.putIfAbsent(id, provider) != null)
      throw new IllegalArgumentException("duplicate-condition");
    return () -> providers.remove(id, provider);
  }

  public boolean known(String id) {
    return Set.of("all", "any", "not").contains(id) || providers.containsKey(id);
  }

  public Verdict test(Player p, Condition c) {
    MainThread.require();
    return test(p, c, 0);
  }

  private Verdict test(Player p, Condition c, int depth) {
    if (c == null || depth > 24) return Verdict.UNAVAILABLE;
    try {
      return switch (c.type()) {
        case "all" -> {
          boolean unavailable = false, denied = false;
          for (var child : c.children()) {
            var v = test(p, child, depth + 1);
            unavailable |= v == Verdict.UNAVAILABLE;
            denied |= v == Verdict.DENY;
          }
          yield denied ? Verdict.DENY : unavailable ? Verdict.UNAVAILABLE : Verdict.ALLOW;
        }
        case "any" -> {
          boolean unavailable = false, allowed = false;
          for (var child : c.children()) {
            var v = test(p, child, depth + 1);
            unavailable |= v == Verdict.UNAVAILABLE;
            allowed |= v == Verdict.ALLOW;
          }
          yield allowed ? Verdict.ALLOW : unavailable ? Verdict.UNAVAILABLE : Verdict.DENY;
        }
        case "not" -> {
          if (c.children().size() != 1) yield Verdict.UNAVAILABLE;
          yield switch (test(p, c.children().getFirst(), depth + 1)) {
            case ALLOW -> Verdict.DENY;
            case DENY -> Verdict.ALLOW;
            case UNAVAILABLE -> Verdict.UNAVAILABLE;
          };
        }
        default -> {
          var provider = providers.get(c.type());
          yield provider == null ? Verdict.UNAVAILABLE : provider.test(p, c.args());
        }
      };
    } catch (RuntimeException e) {
      return Verdict.UNAVAILABLE;
    }
  }

  private static Verdict verdict(boolean b) {
    return b ? Verdict.ALLOW : Verdict.DENY;
  }

  private static String required(Map<String, String> a, String k) {
    return Objects.requireNonNull(a.get(k));
  }

  public List<String> types() {
    return providers.keySet().stream().sorted().toList();
  }
}
