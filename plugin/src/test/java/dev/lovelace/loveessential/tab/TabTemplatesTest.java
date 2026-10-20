package dev.lovelace.loveessential.tab;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TabTemplatesTest {
  @Test
  void nestedLocalPlaceholdersKeepColorsAndPapiExpressions() {
    var aliases = new YamlConfiguration();
    aliases.set("rank", "<gold>[Страж]");
    aliases.set("name", "%loveessential_real_name%");
    aliases.set("heading", "<placeholder:rank> <placeholder:name>");
    assertEquals(
        "<gold>[Страж] %loveessential_real_name% | <world>",
        TabModule.expandAliases(aliases, "<placeholder:heading> | <world>"));
  }

  @Test
  void removedOrRecursivePlaceholdersCannotLeaveAnUnboundedExpansion() {
    var aliases = new YamlConfiguration();
    aliases.set("loop", "<placeholder:loop>");
    assertEquals("", TabModule.expandAliases(aliases, "<placeholder:loop><placeholder:removed>"));
    aliases.set("large", "x".repeat(16385));
    assertThrows(
        IllegalArgumentException.class,
        () -> TabModule.expandAliases(aliases, "<placeholder:large>"));
  }
}
