/*
 * FPV Craft - MIT
 * Camera sources, in priority order:
 *   1. Replay playback (ReplayManager): fully overrides position AND rotation,
 *      including roll, in FPV/CHASE views; FREE view hands control back.
 *   2. Live FPV flight: rotation = integrated drone attitude + camera tilt.
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import dev.fpv.flight.CameraTiltRamp
import dev.fpv.replay.ReplayManager
import net.minecraft.client.Camera
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Camera::class)
class CameraMixin {

    @Shadow
    @Final
    private var rotation: Quaternionf? = null

    @Shadow
    private var position: Vec3? = null

    /** Smooth arm/disarm camera-tilt ramp (no 25 deg snap on unlock). */
    @Unique private val tiltRamp = CameraTiltRamp()
    @Unique private var lastSetupNanos = 0L

    @Inject(method = ["setup"], at = [At("TAIL")])
    private fun fpvSetupTail(
        level: Level,
        entity: Entity,
        detached: Boolean,
        mirrored: Boolean,
        partialTick: Float,
        ci: CallbackInfo,
    ) {
        // ---- Replay playback takes over the whole camera transform. ----
        val eyeHeight = entity.eyeHeight
        val replayXform = ReplayManager.cameraTransform(eyeHeight)
        if (replayXform != null) {
            val (pos, q) = replayXform
            position = pos
            rotation?.set(q)
            return
        }

        if (FpvClient.flight.ready) {
            // Render the drone attitude, then apply the user FPV camera tilt as a
            // permanent nose-down pitch offset (positive tilt = camera looks up).
            // The tilt ramps in smoothly on arm (no unlock jerk) and back to 0 on
            // disarm.
            val base = FpvClient.flight.attitude
            val targetTilt = FpvClient.config.activeAirframe().cameraTiltDeg
            val now = System.nanoTime()
            val dtSec = if (lastSetupNanos == 0L) 0.016f
                         else ((now - lastSetupNanos) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.1f)
            lastSetupNanos = now
            val tiltDeg = tiltRamp.update(targetTilt, dtSec)
            val q = Quaternionf(base)
            if (tiltDeg != 0f) {
                // Rotate about the body X (pitch) axis by -tilt so the camera
                // sees further ahead when the quad is level.
                q.mul(org.joml.Quaternionf().rotateX(
                    (-tiltDeg * Math.PI / 180.0).toFloat()))
            }
            rotation?.set(q)
        } else {
            // Disarmed/idle: ramp the tilt back to zero and reset the clock.
            lastSetupNanos = 0L
            tiltRamp.update(0f, 0.016f)
        }
    }
}
