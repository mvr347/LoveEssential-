package dev.lovelace.loveessential.scoreboard;

import java.util.*;

/**
 * One player's chosen lines. The order of {@link #ids()} is the order of the lines on the board and
 * the order in which the numbered cards are filled. Free of Bukkit so it can be tested.
 */
public final class PersonalState {
  /** A chosen line: the placeholder id and the index of its format variant. */
  public record Entry(String id, int variant) {}

  /** The stored form; {@code viewAll} belongs to the retired flat view and is ignored. The on/off switch is kept by the module. */
  public record Stored(boolean viewAll, List<Entry> entries) {}

  private final List<String> active = new ArrayList<>();
  private final Map<String, Integer> variants = new HashMap<>();

  public PersonalState() {}

  public PersonalState(Stored stored) {
    replaceWith(stored.entries() == null ? List.of() : stored.entries());
  }

  public Stored stored() {
    return new Stored(false, entries());
  }

  public List<String> ids() {
    return Collections.unmodifiableList(active);
  }

  public boolean has(String id) {
    return active.contains(id);
  }

  public int variant(String id) {
    return variants.getOrDefault(id, 0);
  }

  /** Cycles to the next format; a line with one variant stays on it. */
  public int cycleVariant(String id, int count) {
    int next = count <= 1 ? 0 : (normalize(variant(id), count) + 1) % count;
    if (next <= 0) variants.remove(id);
    else variants.put(id, next);
    return next;
  }

  /** A stored index clamped into {@code [0, count)}; anything out of range is the first variant. */
  public static int normalize(int variant, int count) {
    return count > 0 && variant >= 0 && variant < count ? variant : 0;
  }

  public List<Entry> entries() {
    var out = new ArrayList<Entry>(active.size());
    for (String id : active) out.add(new Entry(id, variant(id)));
    return out;
  }

  public void replaceWith(List<Entry> entries) {
    active.clear();
    variants.clear();
    for (Entry entry : entries) {
      if (entry == null || entry.id() == null || active.contains(entry.id())) continue;
      active.add(entry.id());
      if (entry.variant() > 0) variants.put(entry.id(), entry.variant());
    }
  }

  /** Appends to the first free slot; false when already enabled or all slots are taken. */
  public boolean enable(String id, int max) {
    if (active.contains(id) || active.size() >= max) return false;
    active.add(id);
    return true;
  }

  /** Removes a line; the ones after it move up, so the cards stay gap-free. */
  public boolean disable(String id) {
    variants.remove(id);
    return active.remove(id);
  }

  /** Puts {@code id} into slot {@code index}, dropping the line that was there; its format is forgotten. */
  public boolean replaceAt(int index, String id) {
    if (index < 0 || index >= active.size() || active.contains(id)) return false;
    variants.remove(active.get(index));
    active.set(index, id);
    return true;
  }

  /** Exchanges two slots. */
  public boolean swap(int a, int b) {
    if (a == b || a < 0 || b < 0 || a >= active.size() || b >= active.size()) return false;
    Collections.swap(active, a, b);
    return true;
  }

  public boolean moveUp(String id) {
    int index = active.indexOf(id);
    if (index <= 0) return false;
    active.remove(index);
    active.add(index - 1, id);
    return true;
  }

  public boolean moveDown(String id) {
    int index = active.indexOf(id);
    if (index < 0 || index >= active.size() - 1) return false;
    active.remove(index);
    active.add(index + 1, id);
    return true;
  }

  /** Drops lines beyond {@code max} (limit lowered in the config, or an old stored row). */
  public void clampTo(int max) {
    while (active.size() > max) disable(active.get(active.size() - 1));
  }

  /** Drops lines the catalog no longer has. */
  public void retain(java.util.function.Predicate<String> known) {
    for (String id : List.copyOf(active)) if (!known.test(id)) disable(id);
  }
}
