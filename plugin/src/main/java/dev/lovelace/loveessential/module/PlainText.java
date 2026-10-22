package dev.lovelace.loveessential.module;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Plain text of a rendered component, for placing it inside another message as a variable. */
final class PlainText {
  private PlainText() {}

  static String of(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }
}
