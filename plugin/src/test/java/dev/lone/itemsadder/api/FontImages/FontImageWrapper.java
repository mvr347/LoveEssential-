package dev.lone.itemsadder.api.FontImages;

/** Test stand-in for the ItemsAdder API: every :name: becomes [name] so the call path is observable. */
public final class FontImageWrapper {
  private FontImageWrapper() {}

  public static String replaceFontImages(String string) {
    return string.replaceAll(":([a-zA-Z0-9_-]+):", "[$1]");
  }
}
