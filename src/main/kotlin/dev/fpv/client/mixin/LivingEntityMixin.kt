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

        var vx = player.deltaMovement.x
        var vy = player.deltaMovement.y
        var vz = player.deltaMovement.z

        // Thrust along the nose + gravity.
        val thrust = t * cfg.thrustPower
        vx += fwd.x() * thrust
        vy += fwd.y() * thrust - GRAVITY
        vz += fwd.z() * thrust

        // Quadratic drag: deceleration vector = k * speed * velocity (magnitude k*speed^2).
        val speed = sqrt(vx * vx + vy * vy + vz * vz)
        if (speed > 1e-4) {
            val d = cfg.dragK * speed
            vx -= d * vx
            vy -= d * vy
            vz -= d * vz
        }

        player.deltaMovement = Vec3(vx, vy, vz)
    }

    private companion object {
        private const val GRAVITY = 0.05f
    }
}
