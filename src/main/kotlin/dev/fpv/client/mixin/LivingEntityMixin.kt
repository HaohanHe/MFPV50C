/*
 * FPV Craft - MIT
 * Client-side translational flight: thrust along the virtual nose + gravity + v^2 drag.
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
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

    @Inject(method = ["travel"], at = [At("TAIL")])
    private fun fpvTravelTail(movementInput: Vec3, ci: CallbackInfo) {
        val self = (this as Any) as LivingEntity
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (self !== player) return
        if (!FpvClient.flight.ready) return

        val cfg = FpvClient.config

        // Translational physics is a client-side velocity hack: it is safe only
        // in single-player. On multiplayer servers we bail out and let vanilla
        // elytra physics run, unless the user explicitly opted in. The camera
        // roll (applied by the camera mixin from the integrated attitude) is
        // independent and keeps working either way.
        val local = mc.isLocalServer()
        if (!cfg.translationEnhance || (!local && !cfg.allowTranslationMultiplayer)) return

        var t = FpvClient.throttle

        // Reversible-3D: throttle is signed (-1..1, center = 0). A center
        // deadband suppresses motor jitter around the hover point; outside the
        // band the signed thrust pushes along the nose (negative = reverse,
        // allowing inverted hover). Normal mode keeps the 0..1 behavior.
        if (cfg.reversible3D && abs(t) < cfg.threeDThrottleDeadband) {
            t = 0f
        }

        // Nose forward in world coordinates.
        val fwd = Vector3f(0f, 0f, -1f).rotate(FpvClient.flight.attitude)

        // Data-driven airframe physics. Real SI forces are converted into the
        // client per-tick velocity-hack units with TICK_ACCEL, calibrated so a
        // 9.81 m/s² gravity maps to the legacy 0.05 blocks/tick².
        val af = cfg.activeAirframe()
        val tMag = abs(t)
        // Total thrust force (N) = motorCount * per-motor max * thrustLaw(|t|);
        // linear acceleration a = F / massKg.
        val thrustAccelMps2 = af.totalThrustN(tMag) / af.massKg.coerceAtLeast(1e-3f)
        val thrustDelta = thrustAccelMps2 * TICK_ACCEL * sign(t)

        var vx = player.deltaMovement.x
        var vy = player.deltaMovement.y
        var vz = player.deltaMovement.z

        // Thrust along the nose. Gravity scales from the airframe g (anchored so
        // g=9.81 reproduces the legacy 0.05 blocks/tick²).
        val gravityDelta = GRAVITY * (af.gravity / 9.81f)
        vx += fwd.x() * thrustDelta
        vy += fwd.y() * thrustDelta - gravityDelta
        vz += fwd.z() * thrustDelta

        // Drag: linearDrag*v + quadraticDrag*v^2 (vector), scaled by the
        // high-level airDrag knob (0.40 = neutral) and relative airspeed.
        // Heavier craft coasts longer (massScale).
        val speed = sqrt(vx * vx + vy * vy + vz * vz)
        if (speed > 1e-4) {
            val massScale = REF_MASS_KG / af.massKg.coerceAtLeast(1e-3f)
            val airScale = (af.airDrag / 0.40f) * af.relativeAirspeed
            val decel = (af.linearDrag * speed + af.quadraticDrag * speed * speed) * massScale * airScale
            vx -= decel * (vx / speed)
            vy -= decel * (vy / speed)
            vz -= decel * (vz / speed)
        }

        player.deltaMovement = Vec3(vx, vy, vz)
    }

    private companion object {
        private const val GRAVITY = 0.05f
        /** m/s² -> blocks/tick² conversion, anchored at legacy gravity 0.05. */
        private const val TICK_ACCEL = GRAVITY / 9.81f
        /** Reference mass: default freestyle profile, so it reproduces legacy drag. */
        private const val REF_MASS_KG = 0.65f
    }
}
