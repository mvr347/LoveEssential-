package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RetireWorldLineTest {
  @Test
  void onlyTheWorldCardIsRemovedAndOtherTemplatesStayExact() {
    var yaml = new YamlConfiguration();
    yaml.set("placeholders.world.template", "&7бродит по миру &b%lovetweaks_world%");
    String wander = "&7странствует &b%statistic_hours_played% &7ч. &6⏳ &8на &e%player_direction%";
    yaml.set("placeholders.wander.template", wander);
    yaml.set("placeholders.custom.template", "&a%world% &f%player_name%");
    yaml.set("categories.general.lore", List.of("&7Координаты, мир, пинг, дата и время"));
    assertTrue(ModuleConfigs.removeWorldLine(yaml));
    assertFalse(yaml.contains("placeholders.world"));
    assertEquals(wander, yaml.getString("placeholders.wander.template"));
    assertEquals("&a%world% &f%player_name%", yaml.getString("placeholders.custom.template"));
    assertFalse(ModuleConfigs.removeWorldLine(yaml), "a second load cannot restore the removed card");
  }
}
