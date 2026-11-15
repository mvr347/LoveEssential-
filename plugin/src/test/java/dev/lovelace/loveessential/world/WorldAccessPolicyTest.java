package dev.lovelace.loveessential.world;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class WorldAccessPolicyTest {
  @Test void defaultsAreOpenAndEnvironmentDoesNotDependOnTheFolderName() {
    var yaml=new YamlConfiguration();
    assertTrue(WorldAccessPolicy.read(yaml).allows("custom_sky","THE_END"));
    yaml.set("worlds.entry.nether.enabled",false);
    var policy=WorldAccessPolicy.read(yaml);
    assertFalse(policy.allows("any_custom_name","NETHER"));
    assertTrue(policy.allows("world_nether","NORMAL"));
  }
  @Test void namedClosuresAreCaseInsensitiveAndDoNotReopenWithTheDimensionSwitch() {
    var yaml=new YamlConfiguration();
    yaml.set("worlds.entry.disabled-worlds",List.of("Arena"));
    var p=WorldAccessPolicy.read(yaml);
    assertFalse(p.allows("ARENA","NORMAL")); assertTrue(p.allows("other","NORMAL"));
  }
  @Test void badTypesNamesAndAClosedFallbackAreRejected() {
    for (Object value : List.of("false",123,List.of("false"))) {
      var yaml=new YamlConfiguration(); yaml.set("worlds.entry.end.enabled",value);
      assertThrows(IllegalArgumentException.class,() -> WorldAccessPolicy.read(yaml));
    }
    var yaml=new YamlConfiguration(); yaml.set("worlds.entry.disabled-worlds",List.of("../world"));
    assertThrows(IllegalArgumentException.class,() -> WorldAccessPolicy.read(yaml));
    yaml.set("worlds.entry.disabled-worlds",List.of("world")); yaml.set("worlds.entry.fallback-world","world");
    assertThrows(IllegalArgumentException.class,() -> WorldAccessPolicy.read(yaml));
  }
}
