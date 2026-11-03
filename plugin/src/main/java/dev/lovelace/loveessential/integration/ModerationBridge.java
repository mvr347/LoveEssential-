package dev.lovelace.loveessential.integration;

import java.util.UUID;
import org.bukkit.Bukkit;

/** Optional hot-state lookup; never resolves player profiles or accesses SQLite. */
public final class ModerationBridge {
  private ModerationBridge() {}
  public static boolean muted(UUID id) {
    var owner=Bukkit.getPluginManager().getPlugin("LoveWebAdmin");
    if(owner==null||!owner.isEnabled())return false;
    try {
      var service=owner.getClass().getMethod("getModerationService").invoke(owner);
      return service!=null&&Boolean.TRUE.equals(service.getClass().getMethod("isMuted",UUID.class).invoke(service,id));
    } catch(ReflectiveOperationException unavailable) { return false; }
  }
}
