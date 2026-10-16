package dev.lovelace.loveessential.api;

import java.util.Map;

public record Action(String type, Map<String, String> args) {
  public Action {
    args = Map.copyOf(args);
  }
}
