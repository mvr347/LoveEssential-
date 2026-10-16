package dev.lovelace.loveessential.npc;

import dev.lovelace.loveessential.api.*;
import dev.lovelace.loveessential.lang.Lang;
import dev.lovelace.loveessential.wizard.NpcDraft;
import java.util.*;
import java.util.function.Function;

/** Portable export; rekeys localized text so a clone never changes the source NPC's text. */
public record NpcBundle(int format, NpcDefinition npc, Map<String, String> texts) {
  public NpcBundle {
    texts = Map.copyOf(texts);
  }

  public static NpcBundle export(NpcDefinition npc, Lang lang) {
    var keys = new HashSet<String>();
    keys.add(npc.nameKey());
    if (npc.settings().containsKey("description-key"))
      keys.add(npc.settings().get("description-key"));
    for (var node : npc.dialog().nodes().values()) {
      keys.add(node.textKey());
      for (var a : node.answers()) {
        keys.add(a.textKey());
        collect(a.actions(), keys);
      }
    }
    collect(npc.actions(), keys);
    for (var point : npc.route().points()) collect(point.actions(), keys);
    var texts = new LinkedHashMap<String, String>();
    for (String key : keys) if (lang.contains(key)) texts.put(key, lang.text(key));
    return new NpcBundle(1, npc, texts);
  }

  private static void collect(List<Action> actions, Set<String> keys) {
    for (var a : actions)
      for (var e : a.args().entrySet())
        if (e.getKey().equals("key") || e.getKey().endsWith("-key")) keys.add(e.getValue());
  }

  public NpcDraft draft(String id, UUID owner) {
    if (format != 1
        || !id.matches("[a-z0-9_-]{1,48}")
        || texts.size() > 2000
        || texts.values().stream().anyMatch(t -> t.length() > 8192))
      throw new IllegalArgumentException("errors.input");
    var names = new HashMap<String, String>();
    var rewritten = new LinkedHashMap<String, String>();
    String token = UUID.randomUUID().toString().replace("-", "");
    int index = 0;
    for (var text : texts.entrySet()) {
      String key = "npc.text." + id + "." + token + ".line" + index++;
      names.put(text.getKey(), key);
      rewritten.put(key, text.getValue());
    }
    Function<String, String> text = k -> names.getOrDefault(k, k);
    Function<List<Action>, List<Action>> actions =
        list ->
            list.stream()
                .map(
                    a -> {
                      var args = new HashMap<>(a.args());
                      args.replaceAll(
                          (k, v) -> k.equals("key") || k.endsWith("-key") ? text.apply(v) : v);
                      return new Action(a.type(), args);
                    })
                .toList();
    var nodes = new LinkedHashMap<String, Dialog.Node>();
    npc.dialog()
        .nodes()
        .forEach(
            (key, node) ->
                nodes.put(
                    key,
                    new Dialog.Node(
                        text.apply(node.textKey()),
                        node.answers().stream()
                            .map(
                                a ->
                                    new Dialog.Answer(
                                        a.id(),
                                        text.apply(a.textKey()),
                                        a.next(),
                                        a.condition(),
                                        actions.apply(a.actions())))
                            .toList())));
    var route =
        new Route(
            npc.route().mode(),
            npc.route().points().stream()
                .map(
                    p ->
                        new Route.Point(
                            p.position(),
                            p.waitTicks(),
                            p.speed(),
                            p.radius(),
                            p.condition(),
                            actions.apply(p.actions())))
                .toList());
    var options = new HashMap<>(npc.settings());
    if (options.containsKey("description-key"))
      options.put("description-key", text.apply(options.get("description-key")));
    var clone =
        new NpcDefinition(
            id,
            0,
            owner,
            text.apply(npc.nameKey()),
            npc.entityType(),
            npc.spawn(),
            options,
            npc.visibility(),
            npc.access(),
            route,
            new Dialog(npc.dialog().start(), nodes),
            actions.apply(npc.actions()),
            npc.enabled());
    return new NpcDraft(clone, 0, rewritten);
  }
}
