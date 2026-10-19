package dev.lovelace.loveessential.npc;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/** Bounded rotation without teleports or AI look controllers (NPC AI is disabled). */
final class NpcLook {
  private NpcLook() {}

  static float turn(float current, float target, float limit) {
    float difference = Location.normalizeYaw(target - current);
    return Location.normalizeYaw(current + Math.max(-limit, Math.min(limit, difference)));
  }

  static void update(LivingEntity entity, Location eye, float limit) {
    Location from = entity.getEyeLocation();
    if (!from.getWorld().equals(eye.getWorld())) return;
    double dx = eye.getX() - from.getX(),
        dy = eye.getY() - from.getY(),
        dz = eye.getZ() - from.getZ();
    if (!Double.isFinite(dx)
        || !Double.isFinite(dy)
        || !Double.isFinite(dz)
        || dx * dx + dy * dy + dz * dz < .0001) return;
    double horizontal = Math.hypot(dx, dz);
    float yaw = horizontal < .0001 ? from.getYaw() : (float) Math.toDegrees(Math.atan2(-dx, dz));
    float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    yaw = turn(from.getYaw(), yaw, limit);
    pitch =
        Math.max(
            -90,
            Math.min(
                90, from.getPitch() + Math.max(-limit, Math.min(limit, pitch - from.getPitch()))));
    if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
    entity.setRotation(yaw, pitch);
    entity.setBodyYaw(yaw);
  }
}
