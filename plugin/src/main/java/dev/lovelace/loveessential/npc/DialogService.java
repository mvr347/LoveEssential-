package dev.lovelace.loveessential.npc;

import dev.lovelace.loveessential.MainThread;
import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.api.LoveEssentialService.Verdict;
import dev.lovelace.loveessential.condition.*;
import dev.lovelace.loveessential.integration.LoveBridge;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.storage.SqliteStore;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class DialogService implements Listener, AutoCloseable {
  private final NpcService npcs;
  private final ConditionEngine conditions;
  private final ActionEngine actions;
  private final Lang lang;
  private final SqliteStore store;
  private final MainThread main;
  private final LoveBridge love;
  private final Map<UUID, Session> sessions = new HashMap<>();

  private static final class Session {
    final String npc, nonce = UUID.randomUUID().toString();
    final long revision;
    final long expiry = System.currentTimeMillis() + 120000;
    String node;
    int steps;
    boolean busy;

    Session(NpcDefinition n, String node) {
      npc = n.id();
      revision = n.revision();
      this.node = node;
    }
  }

  public DialogService(
      JavaPlugin plugin,
      NpcService npcs,
      ConditionEngine conditions,
      ActionEngine actions,
      Lang lang,
      SqliteStore store,
      MainThread main,
      LoveBridge love) {
    this.npcs = npcs;
    this.conditions = conditions;
    this.actions = actions;
    this.lang = lang;
    this.store = store;
    this.main = main;
    this.love = love;
    org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  public void open(Player p, NpcDefinition npc) {
    var s = new Session(npc, npc.dialog().start());
    sessions.put(p.getUniqueId(), s);
    render(p, npc, s);
  }

  public void answer(Player p, String nonce, String nodeId, String answerId) {
    var s = sessions.get(p.getUniqueId());
    if (s == null
        || !s.nonce.equals(nonce)
        || !s.node.equals(nodeId)
        || s.busy
        || System.currentTimeMillis() > s.expiry
        || ++s.steps > 64) {
      lang.send(p, "errors.dialog-expired");
      return;
    }
    var npc = npcs.get(s.npc).orElse(null);
    var entity = npcs.entity(s.npc, p).orElse(null);
    if (npc == null
        || npc.revision() != s.revision
        || !npcs.accessible(p, npc)
        || entity == null
        || !p.canSee(entity)
        || !entity.getWorld().equals(p.getWorld())
        || entity.getLocation().distanceSquared(p.getLocation())
            > Math.pow(npc.number("interaction-range", 4), 2)) {
      sessions.remove(p.getUniqueId());
      lang.send(p, "errors.dialog-expired");
      return;
    }
    var node = npc.dialog().nodes().get(s.node);
    if (node == null) return;
    var answer =
        node.answers().stream().filter(a -> a.id().equals(answerId)).findFirst().orElse(null);
    if (answer == null || conditions.test(p, answer.condition()) != Verdict.ALLOW) {
      lang.send(p, "errors.access");
      return;
    }
    s.busy = true;
    store
        .profile(
            p.getUniqueId(),
            "loveessential.dialog." + npc.id(),
            npc.revision() + ":" + s.node + ":" + answer.id())
        .whenComplete(
            (v, error) ->
                main.execute(
                    () -> {
                      if (sessions.get(p.getUniqueId()) != s || !p.isOnline()) return;
                      if (error != null) {
                        s.busy = false;
                        lang.send(p, "errors.storage");
                        return;
                      }
                      if (!npcs.accessible(p, npc)
                          || !entity.isValid()
                          || !p.getWorld().equals(entity.getWorld())
                          || p.getLocation().distanceSquared(entity.getLocation())
                              > Math.pow(npc.number("interaction-range", 4), 2)
                          || System.currentTimeMillis() > s.expiry
                          || npcs.get(npc.id())
                              .map(n -> n.revision() != npc.revision())
                              .orElse(true)) {
                        sessions.remove(p.getUniqueId());
                        return;
                      }
                      if (!actions.execute(p, answer.actions())) {
                        sessions.remove(p.getUniqueId());
                        return;
                      }
                      love.stat(p, "essential.dialog.answers", 1);
                      if (answer.next().isEmpty()) {
                        sessions.remove(p.getUniqueId());
                        lang.send(p, "dialog.finished");
                        return;
                      }
                      s.node = answer.next();
                      s.busy = false;
                      render(p, npc, s);
                    }));
  }

  private void render(Player p, NpcDefinition npc, Session s) {
    var node = npc.dialog().nodes().get(s.node);
    if (node == null) {
      sessions.remove(p.getUniqueId());
      return;
    }
    Component message = lang.render(node.textKey(), Map.of("player", p.getName()));
    for (var a : node.answers())
      if (conditions.test(p, a.condition()) == Verdict.ALLOW)
        message =
            message
                .append(Component.newline())
                .append(
                    lang.render(a.textKey())
                        .clickEvent(
                            ClickEvent.runCommand(
                                "/le answer " + s.nonce + " " + s.node + " " + a.id())));
    love.notify(p, message);
    if (node.answers().isEmpty()) sessions.remove(p.getUniqueId());
  }

  public void invalidate(String npc) {
    sessions.values().removeIf(s -> s.npc.equals(npc));
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    sessions.remove(e.getPlayer().getUniqueId());
  }

  public void close() {
    sessions.clear();
    HandlerList.unregisterAll(this);
  }
}
