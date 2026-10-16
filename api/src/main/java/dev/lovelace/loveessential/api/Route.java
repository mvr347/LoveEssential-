package dev.lovelace.loveessential.api;

import java.util.List;

public record Route(Mode mode, List<Point> points) {
  public enum Mode {
    ONCE,
    LOOP,
    PING_PONG,
    RANDOM
  }

  public record Point(
      Position position,
      int waitTicks,
      double speed,
      double radius,
      Condition condition,
      List<Action> actions) {
    public Point {
      actions = List.copyOf(actions);
      if (waitTicks < 0
          || waitTicks > 72000
          || speed < .1
          || speed > 5
          || !Double.isFinite(speed)
          || radius < .1
          || radius > 8
          || !Double.isFinite(radius)) throw new IllegalArgumentException("invalid-point");
    }
  }

  public Route {
    points = List.copyOf(points);
  }

  public static Route empty() {
    return new Route(Mode.LOOP, List.of());
  }
}
