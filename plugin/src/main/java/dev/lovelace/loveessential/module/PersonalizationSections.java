package dev.lovelace.loveessential.module;

/**
 * The four sections of the personalization menus (nick colour, mark, display, join/quit message) and where their
 * buttons sit in the header. The slots are FIXED, the same in every one of the menus, so the player finds a
 * section where they expect it (owner request 2026-11-12); a section that is not available leaves its slot as glass.
 * Slots 6 and 7 belong to the filter and the sort button of the menus that have them. No server types, so it is
 * tested on its own.
 */
public final class PersonalizationSections {
  private PersonalizationSections() {}

  public enum Section {
    NICK(2),
    MARKS(3),
    DISPLAY(4),
    JOINQUIT(5);

    private final int slot;

    Section(int slot) {
      this.slot = slot;
    }

    public int slot() {
      return slot;
    }
  }

  public static final int FILTER_SLOT = 6;
  public static final int SORT_SLOT = 7;

  /** The command every section's Back runs: all four are siblings under the player's profile. */
  public static final String PROFILE_COMMAND = "loveessential:profile";
}
