package dev.lovelace.loveessential.condition;

import java.util.Collection;
import java.util.Locale;

/** The editor and executor use exactly the same command syntax and permission list. */
public final class PlayerCommand {
  private PlayerCommand() {}

  public static String validated(String input, Collection<String> allowed) {
    if (input == null || input.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("npc-command.invalid");
    String command = input.strip();
    if (command.startsWith("/")) command = command.substring(1).stripLeading();
    if (command.isEmpty() || command.startsWith("/"))
      throw new IllegalArgumentException("npc-command.invalid");
    String root = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
    if (allowed.stream().noneMatch(value -> value.strip().equalsIgnoreCase(root)))
      throw new IllegalArgumentException("npc-command.denied");
    return command;
  }
}
