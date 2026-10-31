package dev.lone.itemsadder.api.FontImages;

/** Test stand-in: real font glyphs include the legacy color/reset emitted by ItemsAdder. */
public final class FontImageWrapper {
  private FontImageWrapper() {}

  public static String replaceFontImages(String string) {
    return string
        .replace(":offset_-8:", "\uF808")
        .replace(":voidcore:stats_menu:", "§f\uE020§r")
        .replace(":stats_menu:", "§f\uE020§r")
        .replaceAll(":([a-zA-Z0-9_-]+):", "[$1]");
  }
}
