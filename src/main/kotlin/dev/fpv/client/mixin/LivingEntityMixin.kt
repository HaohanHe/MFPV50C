/*
 * FPV Craft - MIT
 * Client-side translational flight, FULLY taking over the player's travel():
 * when the drone is engaged we cancel vanilla travel (which for an elytra would
 * recompute velocity and move us) and run our own 6-DoF-lite translation:
 *   thrust along the virtual nose + gravity + (linear + v^2) drag, then move().
 * Cancelling at HEAD guarantees our velocity is what actually moves the craft
 * this tick (the old TAIL injection ran after vanilla move() and was overwritten
 * next tick, which is why throttle seemed to do nothing). Client-only velocity
 * hack: enabled in single-player (integrated server follows client moves); on
 * multiplayer it stays off unless explicitly opted in. The camera roll is
 * independent and keeps working either way.
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

@Mixin(LivingEntity::class)
class LivingEntityMixin {

    @Inject(method = ["travel"], at = [At("HEAD")], cancellable = true)
    private fun fpvTravelHead(movementInput: Vec3, ci: CallbackInfo) {
        val self = (this as Any) as LivingEntity
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (self !== player) return
        if (!FpvClient.flight.ready) return

        val cfg = FpvClient.config
        val local = mc.isLocalServer()
        if (!cfg.translationEnhance || (!local && !cfg.allowTranslationMultiplayer)) return

        // Take over completely: vanilla travel (and its move()) is cancelled.
        ci.cancel()

        var t = FpvClient.throttle
        // Reversible-3D: signed throttle with a center deadband (negative = reverse).
        if (cfg.reversible3D && abs(t) < cfg.threeDThrottleDeadband) t = 0f

        // Nose forward in world coordinates (single source: integrated attitude).
        val fwd = Vector3f(0f, 0f, -1f).rotate(FpvClient.flight.attitude)

        // Data-driven airframe. Real SI force -> per-tick velocity units via
        // TICK_ACCEL, anchored so 9.81 m/s^2 maps to the legacy 0.05 blocks/tick^2.
        val af = cfg.activeAirframe()
        val tMag = abs(t)
        val thrustAccelMps2 = af.totalThrustN(tMag) / af.massKg.coerceAtLeast(1e-3f)
        val thrustDelta = thrustAccelMps2 * TICK_ACCEL * sign(t)

        var vx = player.deltaMovement.x
        var vy = player.deltaMovement.y
        var vz = player.deltaMovement.z

        val gravityDelta = GRAVITY * (af.gravity / 9.81f)
        vx += fwd.x() * thrustDelta
        vy += fwd.y() * thrustDelta - gravityDelta
        vz += fwd.z() * thrustDelta

        // Drag: linearDrag*v + quadraticDrag*v^2, scaled by airDrag / relative
        // airspeed; heavier craft coasts longer.
        val speed = sqrt(vx * vx + vy * vy + vz * vz)
        if (speed > 1e-4) {
            val massScale = REF_MASS_KG / af.massKg.coerceAtLeast(1e-3f)
            val airScale = (af.airDrag / 0.40f) * af.relativeAirspeed
            val decel = (af.linearDrag * speed + af.quadraticDrag * speed * speed) *
                massScale * airScale
            vx -= decel * (vx / speed)
            vy -= decel * (vy / speed)
            vz -= decel * (vz / speed)
        }

        val v = Vec3(vx, vy, vz)
        player.deltaMovement = v
        // Vanilla travel would have moved us after setting velocity; do it here.
        player.move(MoverType.SELF, v)
    }

    private companion object {
        private const val GRAVITY = 0.05f
        /** m/s^2 -> blocks/tick^2, anchored at legacy gravity 0.05. */
        private const val TICK_ACCEL = GRAVITY / 9.81f
        /** Reference mass (default freestyle) so drag reproduces the legacy feel. */
        private const val REF_MASS_KG = 0.65f
    }
}
