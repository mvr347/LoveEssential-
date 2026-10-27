package dev.lovelace.loveessential.api.mechanics;

import java.util.Set;

/** Immutable server-owned state, safe to retain on packet/worker threads. */
public record PlayerMechanics(Set<State> states) {
  public enum State {
    SITTING,
    LYING,
    RIDING,
    CARRYING,
    DOWNED,
    REVIVING,
    GOD
  }

  public static final PlayerMechanics NONE = new PlayerMechanics(Set.of());

  public PlayerMechanics {
    states = Set.copyOf(states);
  }

  public boolean movementControlled() {
    return states.size() > (states.contains(State.CARRYING) ? 1 : 0);
  }

  public boolean combatControlled() {
    return states.size()
        > (states.contains(State.CARRYING) ? 1 : 0) + (states.contains(State.REVIVING) ? 1 : 0);
  }

  public boolean hitboxModified() {
    return states.contains(State.SITTING)
        || states.contains(State.LYING)
        || states.contains(State.RIDING)
        || states.contains(State.DOWNED);
  }
}
