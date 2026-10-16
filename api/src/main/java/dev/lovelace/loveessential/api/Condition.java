package dev.lovelace.loveessential.api;

import java.util.List;
import java.util.Map;

public record Condition(String type, Map<String, String> args, List<Condition> children) {
  public Condition {
    args = Map.copyOf(args);
    children = List.copyOf(children);
  }

  public static Condition always() {
    return new Condition("all", Map.of(), List.of());
  }
}
