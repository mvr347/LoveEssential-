package dev.lovelace.loveessential.tab;

import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import java.util.function.Predicate;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class LoveExpansion extends PlaceholderExpansion {
  private final Placeholders placeholders;
  private final Lang lang;
  private final LoveBridge love;
  private final Predicate<Player> afk, vanish;

  public LoveExpansion(
      Placeholders p, Lang l, LoveBridge love, Predicate<Player> afk, Predicate<Player> vanish) {
    placeholders = p;
    lang = l;
    this.love = love;
    this.afk = afk;
    this.vanish = vanish;
  }

  public @NotNull String getIdentifier() {
    return "loveessential";
  }

  public @NotNull String getAuthor() {
    return "Lovelace";
  }

  public @NotNull String getVersion() {
    return "2.0.0";
  }

  public boolean persist() {
    return true;
  }

  @Override
  public String onRequest(OfflinePlayer offline, @NotNull String params) {
    if (offline == null) return "";
    var values = placeholders.playerValues(offline.getUniqueId());
    if (values.containsKey(params)) return values.get(params);
    if (!(offline instanceof Player p) || !p.isOnline()) return "";
    return switch (params) {
      case "afk" -> Boolean.toString(afk.test(p));
      case "vanished" -> Boolean.toString(vanish.test(p));
      case "authenticated" -> Boolean.toString(love.authenticated(p));
      case "clan" -> love.clan(p).orElse("");
      case "reputation" ->
          love.reputation(p).isPresent() ? Integer.toString(love.reputation(p).getAsInt()) : "";
      default -> {
        if (params.startsWith("text_") && lang.contains(params.substring(5)))
          yield dev.lovelace.loveessential.module.Nicknames.LEGACY.serialize(
              params.equals("text_tab.name")
                      || params.startsWith("text_tab.groups.") && params.endsWith(".name")
                  ? placeholders.renderName(params.substring(5), p, p)
                  : placeholders.render(params.substring(5), p, p));
        yield null;
      }
    };
  }
}
