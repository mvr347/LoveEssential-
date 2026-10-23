package dev.lovelace.loveessential.scoreboard;

/**
 * Slot arithmetic of the personal scoreboard menus (gui_gen v2.1). Free of Bukkit so it can be
 * tested. Header is row 0 (plus Row1 from 45 slots), Footer is always the last row, the work zone is
 * everything in between and its side walls stay empty.
 */
public final class PersonalLayout {
  /** Hard cap of lines: 8 cards fit a 2x4 grid and the 15-line body of a sidebar. */
  public static final int MAX_LINES = 8;

  public static final int SIZE = 54;

  /** Numbered line cards of the "by category" view: two pairs per row, like pairs on the board. */
  public static final int[] CARD_SLOTS = {20, 21, 23, 24, 29, 30, 32, 33};

  /** Category buttons of the main menu. */
  public static final int[] CATEGORY_SLOTS = {37, 38, 39, 40, 41, 42, 43};

  /** The flat "all" view: the work zone without side walls. */
  public static final int[] FLAT_SLOTS = {
    19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43
  };

  public static final int PAGE_SIZE = FLAT_SLOTS.length;
  public static final int PAGE_PREV = 36, PAGE_NEXT = 44;

  /** A category submenu holds at most three work rows. */
  public static final int MAX_SUBMENU_ITEMS = 21;

  /** Header slots 2-7 minus the view toggle. */
  public static final int MAX_PRESETS = 5;

  private PersonalLayout() {}

  /** Number of line cards: the configured limit clamped to [1, 8]. */
  public static int cardCount(int max) {
    return Math.max(1, Math.min(MAX_LINES, max));
  }

  /** Header slots 2-7 for {@code count} control buttons, centered (gui_gen rule 4). */
  public static int[] controlSlots(int count) {
    return switch (count) {
      case 0 -> new int[0];
      case 1 -> new int[] {4};
      case 2 -> new int[] {3, 5};
      case 3 -> new int[] {2, 4, 6};
      case 4 -> new int[] {2, 3, 5, 6};
      case 5 -> new int[] {2, 3, 4, 6, 7};
      case 6 -> new int[] {2, 3, 4, 5, 6, 7};
      default -> throw new IllegalArgumentException("Header fits 0-6 control buttons, got " + count);
    };
  }

  /** Index (in header order) of the view toggle: the middle of the controls. */
  public static int toggleIndex(int controls) {
    return controls / 2 - (controls % 2 == 0 ? 1 : 0);
  }

  /** Category buttons are centered in the 37-43 row. */
  public static int[] categorySlots(int count) {
    int n = Math.min(count, CATEGORY_SLOTS.length);
    int[] columns = centeredColumns(n);
    int[] out = new int[n];
    for (int i = 0; i < n; i++) out[i] = 36 + columns[i];
    return out;
  }

  public static int pageCount(int total) {
    return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
  }

  public static int clampPage(int page, int total) {
    return Math.max(0, Math.min(page, pageCount(total) - 1));
  }

  /** {from, to} (exclusive) indices of a page. */
  public static int[] pageRange(int total, int page) {
    int p = clampPage(page, total);
    int from = Math.min(total, p * PAGE_SIZE);
    return new int[] {from, Math.min(total, from + PAGE_SIZE)};
  }

  public static int workStart(int size) {
    return size >= 45 ? 18 : 9;
  }

  public static int workRows(int size) {
    return (size - 9 - workStart(size)) / 9;
  }

  /** Rows needed for {@code count} items (7 per row), clamped to the three rows of the biggest menu. */
  public static int submenuRows(int count) {
    return Math.max(1, Math.min(3, (Math.min(count, MAX_SUBMENU_ITEMS) + 6) / 7));
  }

  /**
   * 27 (one work row), 36 (two) or 54 (three). 45 is skipped: it has the same two work rows as 36,
   * so it would only add glass. Pagination is not needed, 21 items fit three rows.
   */
  public static int submenuSize(int count) {
    return switch (submenuRows(count)) {
      case 1 -> 27;
      case 2 -> 36;
      default -> 54;
    };
  }

  /** Columns (1-7) used by a row of {@code n} buttons, symmetric around column 4. */
  public static int[] centeredColumns(int n) {
    return switch (n) {
      case 0 -> new int[0];
      case 1 -> new int[] {4};
      case 2 -> new int[] {3, 5};
      case 3 -> new int[] {3, 4, 5};
      case 4 -> new int[] {2, 3, 5, 6};
      case 5 -> new int[] {2, 3, 4, 5, 6};
      case 6 -> new int[] {1, 2, 3, 5, 6, 7};
      case 7 -> new int[] {1, 2, 3, 4, 5, 6, 7};
      default -> throw new IllegalArgumentException("A row holds 0-7 buttons, got " + n);
    };
  }

  /** Content slots of a category submenu: items spread evenly over the needed rows, each row centered. */
  public static int[] submenuSlots(int size, int count) {
    int shown = Math.min(count, MAX_SUBMENU_ITEMS);
    if (shown == 0) return new int[0];
    int rows = Math.min(workRows(size), (shown + 6) / 7);
    int base = shown / rows, extra = shown % rows;
    int[] slots = new int[shown];
    int index = 0;
    for (int r = 0; r < rows; r++) {
      int inRow = base + (r < extra ? 1 : 0);
      int rowStart = workStart(size) + r * 9;
      for (int column : centeredColumns(inRow)) slots[index++] = rowStart + column;
    }
    return slots;
  }
}
