package dev.lovelace.loveessential.tab;

import java.util.*;

/**
 * Property layers for TAB. A player's values come from the highest layer that defines a key:
 * runtime override (TabService) &gt; user per-world &gt; user &gt; group per-world &gt; group &gt;
 * {@code _DEFAULT_}. A layer whose display condition is false is skipped by the caller before it
 * gets here, so this class only merges.
 */
public final class TabProperties {
  private TabProperties() {}

  public static final String TAB_PREFIX = "tabprefix",
      TAB_SUFFIX = "tabsuffix",
      CUSTOM_TAB_NAME = "customtabname",
      TAG_PREFIX = "tagprefix",
      TAG_SUFFIX = "tagsuffix",
      HEADER = "header",
      FOOTER = "footer",
      COLLISION = "collision",
      VISIBILITY = "visibility";

  /** Every key a group, user or world layer may set. Unknown keys are ignored on purpose. */
  public static final Set<String> KEYS =
      Set.of(
          TAB_PREFIX,
          TAB_SUFFIX,
          CUSTOM_TAB_NAME,
          TAG_PREFIX,
          TAG_SUFFIX,
          HEADER,
          FOOTER,
          COLLISION,
          VISIBILITY);

  /** Values accepted by the {@code visibility} property (Bukkit name-tag visibility). */
  public static final Set<String> VISIBILITY_VALUES = Set.of("always", "never");

  /** A named layer, for example {@code group:admin} or {@code user:Steve/world_nether}. */
  public record Layer(String source, Map<String, String> values) {
    public Layer {
      Objects.requireNonNull(source);
      values = Map.copyOf(values);
    }
  }

  /** Merged values and the source layer each value came from (shown by {@code /le tab debug}). */
  public record Resolved(Map<String, String> values, Map<String, String> sources) {
    public Resolved {
      values = Map.copyOf(values);
      sources = Map.copyOf(sources);
    }

    public Optional<String> value(String key) {
      return Optional.ofNullable(values.get(key));
    }
  }

  /** Merges layers ordered from the highest priority to the lowest. */
  public static Resolved resolve(List<Layer> highestFirst) {
    var values = new HashMap<String, String>();
    var sources = new HashMap<String, String>();
    for (Layer layer : highestFirst)
      for (String key : KEYS)
        if (!values.containsKey(key) && layer.values().containsKey(key)) {
          values.put(key, layer.values().get(key));
          sources.put(key, layer.source());
        }
    return new Resolved(values, sources);
  }

  /** Rejects keys and values that could not be applied, so a bad command never reaches tab.yml. */
  public static void validate(String key, String value) {
    if (!KEYS.contains(key)) throw new IllegalArgumentException("errors.input");
    if (value == null) return;
    if (key.equals(VISIBILITY) && !VISIBILITY_VALUES.contains(value))
      throw new IllegalArgumentException("errors.input");
    if (key.equals(COLLISION) && !value.equals("true") && !value.equals("false"))
      throw new IllegalArgumentException("errors.input");
  }
}
