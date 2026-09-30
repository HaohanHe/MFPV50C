/*
 * FPV Craft - MIT
 * Camera sources, in priority order:
 *   1. Replay playback (ReplayManager): fully overrides position AND rotation,
 *      including roll, in FPV/CHASE views; FREE view hands control back.
 *   2. Live FPV flight: rotation = integrated drone attitude + camera tilt.
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import dev.fpv.replay.ReplayManager
import net.minecraft.client.Camera
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
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
            val base = FpvClient.flight.attitude
            val tiltDeg = FpvClient.config.activeAirframe().cameraTiltDeg
            val q = Quaternionf(base)
            if (tiltDeg != 0f) {
                // Rotate about the body X (pitch) axis by -tilt so the camera
                // sees further ahead when the quad is level.
                q.mul(org.joml.Quaternionf().rotateX(
                    (-tiltDeg * Math.PI / 180.0).toFloat()))
            }
            rotation?.set(q)
        }
    }
}
