package dev.lovelace.loveessential.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Every text of the NPC wizard menu must be bound in presentation-bindings.yml: a text without a binding is not
 * registered and the menu shows its raw key ({@code [wizard.fields.work-from]}) instead of the name.
 */
class WizardBindingsTest {
  private static YamlConfiguration resource(String name) throws Exception {
    try (var in = WizardBindingsTest.class.getClassLoader().getResourceAsStream(name)) {
      assertNotNull(in, name);
      return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
  }

  private static void leaves(ConfigurationSection section, String prefix, List<String> out) {
    for (String key : section.getKeys(false)) {
      Object value = section.get(key);
      if (value instanceof ConfigurationSection child) leaves(child, prefix + key + ".", out);
      else out.add(prefix + key);
    }
  }

  @Test
  void everyWizardLabelHasABinding() throws Exception {
    var gui = resource("modules/npc/gui.yml");
    var labels = gui.getConfigurationSection("menus.gui-wizard-title.labels");
    assertNotNull(labels);
    var all = new ArrayList<String>();
    leaves(labels, "", all);
    Set<String> bound = new HashSet<>();
    for (Map<?, ?> binding : resource("presentation-bindings.yml").getMapList("bindings")) {
      Object module = binding.get("module"), key = binding.get("key");
      if ("npc".equals(module) && key != null) bound.add(key.toString());
    }
    var missing = all.stream().filter(k -> !bound.contains(k)).toList();
    assertTrue(missing.isEmpty(), "Wizard labels without a binding: " + missing);
  }
}
