package dev.lovelace.loveessential.tab;

import com.github.benmanes.caffeine.cache.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.*;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;

public final class Placeholders {
  private final Lang lang;
  private final LoveBridge love;
  private final Predicate<Player> afk;
  private final Cache<String, String> cache =
      Caffeine.newBuilder().maximumSize(4096).expireAfterWrite(Duration.ofMillis(200)).build();
  private java.util.function.Function<UUID, Map<String, String>> playerValues = id -> Map.of();

  public void playerValues(java.util.function.Function<UUID, Map<String, String>> provider) {
    playerValues = provider;
  }

  public Map<String, String> playerValues(UUID id) {
    return playerValues.apply(id);
  }

  public List<Component> renderLines(String key, Player viewer, Player target) {
    return lang.lines(key).stream().map(line -> renderText(line, viewer, target, 0)).toList();
  }

  private java.util.function.Function<Player, Component> nickname =
      p -> Component.text(p.getName());
  private java.util.function.BooleanSupplier nicknameTab = () -> false;

  public void nicknames(
      java.util.function.Function<Player, Component> provider,
      java.util.function.BooleanSupplier tab) {
    nickname = provider;
    nicknameTab = tab;
  }

  public Component renderName(String key, Player viewer, Player target) {
    String template = lang.text(key);
    if (nicknameTab.getAsBoolean()) template = template.replace("<player>", "<nickname>");
    return renderText(template, viewer, target, 0);
  }

  private final MiniMessage mm = MiniMessage.miniMessage();

  public Placeholders(Lang lang, LoveBridge love, Predicate<Player> afk) {
    this.lang = lang;
    this.love = love;
    this.afk = afk;
  }

  public String papi(Player viewer, Player target, String placeholder) {
    if (placeholder.startsWith("%loveessential_") && placeholder.endsWith("%"))
      return playerValues
          .apply(target.getUniqueId())
          .getOrDefault(placeholder.substring(15, placeholder.length() - 1), "");
    if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
        || placeholder.contains("%loveessential_")) return "";
    return cache.get(
        viewer.getUniqueId() + ":" + target.getUniqueId() + ":" + placeholder,
        k ->
            PlaceholderAPI.setRelationalPlaceholders(
                viewer, target, PlaceholderAPI.setPlaceholders(target, placeholder)));
  }

  public Component render(String key, Player viewer, Player target) {
    return renderText(lang.text(key), viewer, target, 0);
  }

  private Component renderText(String template, Player viewer, Player target, int depth) {
    if (depth > 4) return Component.empty();
    var b = TagResolver.builder();
    Map<String, String> variables =
        Map.ofEntries(
            Map.entry("player", target.getName()),
            Map.entry("viewer", viewer.getName()),
            Map.entry("world", target.getWorld().getName()),
            Map.entry(
                "online",
                Long.toString(Bukkit.getOnlinePlayers().stream().filter(viewer::canSee).count())),
            Map.entry(
                "visible_online",
                Long.toString(Bukkit.getOnlinePlayers().stream().filter(viewer::canSee).count())),
            Map.entry("max_players", Integer.toString(Bukkit.getMaxPlayers())),
            Map.entry("ping", Integer.toString(target.getPing())),
            Map.entry("clan", love.clan(target).orElse("")),
            Map.entry("territory", love.territory(target)),
            Map.entry(
                "reputation",
                love.reputation(target).isPresent()
                    ? Integer.toString(love.reputation(target).getAsInt())
                    : ""));
    playerValues
        .apply(target.getUniqueId())
        .forEach((k, v) -> b.resolver(Placeholder.unparsed(k, v)));
    variables.forEach((k, v) -> b.resolver(Placeholder.unparsed(k, v)));
    b.resolver(Placeholder.component("nickname", nickname.apply(target)));
    b.resolver(Placeholder.unparsed("real_name", target.getName()));
    b.resolver(
        Placeholder.component(
            "afk", afk.test(target) ? lang.render("tab.status.afk") : Component.empty()));
    b.resolver(
        Placeholder.component(
            "suffix", legacyOrMini(papi(viewer, target, "%lovesubnames_current%"))));
    b.resolver(
        TagResolver.resolver(
            "papi",
            (args, context) ->
                Tag.inserting(
                    legacyOrMini(
                        papi(viewer, target, "%" + args.popOr("identifier").value() + "%")))));
    b.resolver(
        TagResolver.resolver(
            "animation",
            (args, context) -> {
              String name = args.popOr("animation name").value();
              var frames = lang.lines("tab.animations." + name);
              int frame = (int) ((System.currentTimeMillis() / 500) % Math.max(1, frames.size()));
              return Tag.inserting(
                  frames.isEmpty()
                      ? Component.empty()
                      : renderText(frames.get(frame), viewer, target, depth + 1));
            }));
    return mm.deserialize(template, b.build());
  }

  private Component legacyOrMini(String value) {
    if (value == null || value.startsWith("%") && value.endsWith("%")) return Component.empty();
    return value.contains("§")
        ? LegacyComponentSerializer.legacySection().deserialize(value)
        : value.contains("&")
            ? LegacyComponentSerializer.legacyAmpersand().deserialize(value)
            : mm.deserialize(value);
  }

  public void clear() {
    cache.invalidateAll();
  }
}
