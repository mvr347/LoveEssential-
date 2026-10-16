package dev.lovelace.loveessential.wizard;

import dev.lovelace.loveessential.api.*;
import java.util.*;

public record NpcDraft(NpcDefinition npc, int step, Map<String, String> texts) {
  public NpcDraft {
    texts = Map.copyOf(texts);
  }

  public static NpcDraft create(UUID owner, Position at) {
    var n =
        new NpcDefinition(
            "",
            0,
            owner,
            "",
            "MANNEQUIN",
            at,
            Map.of(),
            Condition.always(),
            Condition.always(),
            Route.empty(),
            Dialog.empty(),
            List.of(),
            true);
    return new NpcDraft(n, 0, Map.of());
  }

  public NpcDraft npc(NpcDefinition n) {
    return new NpcDraft(n, step, texts);
  }

  public NpcDraft step(int s) {
    return new NpcDraft(npc, s, texts);
  }

  public NpcDraft text(String key, String value) {
    var map = new HashMap<>(texts);
    map.put(key, value);
    return new NpcDraft(npc, step, map);
  }

  public NpcDraft change(
      String id,
      String name,
      String type,
      Position spawn,
      Map<String, String> options,
      Condition visibility,
      Condition access,
      Route route,
      Dialog dialog,
      List<Action> actions,
      boolean enabled) {
    var n = npc;
    return npc(
        new NpcDefinition(
            id,
            n.revision(),
            n.owner(),
            name,
            type,
            spawn,
            options,
            visibility,
            access,
            route,
            dialog,
            actions,
            enabled));
  }

  public NpcDraft option(String key, String value) {
    var opts = new HashMap<>(npc.settings());
    opts.put(key, value);
    return change(
        npc.id(),
        npc.nameKey(),
        npc.entityType(),
        npc.spawn(),
        opts,
        npc.visibility(),
        npc.access(),
        npc.route(),
        npc.dialog(),
        npc.actions(),
        npc.enabled());
  }

  public NpcDraft route(Route route) {
    return change(
        npc.id(),
        npc.nameKey(),
        npc.entityType(),
        npc.spawn(),
        npc.settings(),
        npc.visibility(),
        npc.access(),
        route,
        npc.dialog(),
        npc.actions(),
        npc.enabled());
  }

  public NpcDraft dialog(Dialog dialog) {
    return change(
        npc.id(),
        npc.nameKey(),
        npc.entityType(),
        npc.spawn(),
        npc.settings(),
        npc.visibility(),
        npc.access(),
        npc.route(),
        dialog,
        npc.actions(),
        npc.enabled());
  }
}
