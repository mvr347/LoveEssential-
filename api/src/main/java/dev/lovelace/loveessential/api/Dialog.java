package dev.lovelace.loveessential.api;

import java.util.List;
import java.util.Map;

public record Dialog(String start, Map<String, Node> nodes) {
  public record Node(String textKey, List<Answer> answers) {
    public Node {
      answers = List.copyOf(answers);
    }
  }

  public record Answer(
      String id, String textKey, String next, Condition condition, List<Action> actions) {
    public Answer {
      actions = List.copyOf(actions);
    }
  }

  public Dialog {
    nodes = Map.copyOf(nodes);
  }

  public static Dialog empty() {
    return new Dialog("", Map.of());
  }
}
