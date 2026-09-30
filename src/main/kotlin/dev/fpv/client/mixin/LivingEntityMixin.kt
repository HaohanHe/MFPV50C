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
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

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

        // Data-driven airframe; all physics lives in the headless-testable
        // TranslationalDynamics. Thrust points along BODY-UP (attitude*(0,1,0)):
        // level + hoverThrottle -> net vertical ~0; tilting -> translation + natural
        // altitude loss. Battery sag derates thrust; ground effect boosts near ground.
        val af = cfg.activeAirframe()
        val dyn = dev.fpv.flight.TranslationalDynamics(af)

        // Height above local ground for ground effect; unknown -> far away (no effect).
        val agl = runCatching {
            val bp = player.blockPosition()
            player.y - player.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bp.x, bp.z
            )
        }.getOrDefault(100.0).toFloat()

        val derate = FpvClient.flight.batteryDerate

        // A pre-existing NaN velocity is reset to zero rather than carried forward.
        var vx = if (player.deltaMovement.x.isFinite()) player.deltaMovement.x else 0.0
        var vy = if (player.deltaMovement.y.isFinite()) player.deltaMovement.y else 0.0
        var vz = if (player.deltaMovement.z.isFinite()) player.deltaMovement.z else 0.0

        // Feed flight condition to the condition-driven prop-wash model, and fold its
        // thrust drop into the derate (high-power descent into own wash loses lift).
        val horiz = Math.sqrt(vx * vx + vz * vz)
        FpvClient.flight.setTranslationState(vy.toFloat(), horiz.toFloat())
        val totalDerate = derate * FpvClient.flight.propwashThrustScale

        val d = dyn.step(
            FpvClient.flight.attitude, FpvClient.throttle,
            vx, vy, vz, agl, totalDerate,
            cfg.reversible3D, cfg.threeDThrottleDeadband,
        )
        vx += d.x().toDouble()
        vy += d.y().toDouble()
        vz += d.z().toDouble()

        // Final guard: never assign/move on a NaN velocity (vanilla kicks on NaN).
        val fx = if (vx.isFinite()) vx else 0.0
        val fy = if (vy.isFinite()) vy else 0.0
        val fz = if (vz.isFinite()) vz else 0.0
        val v = Vec3(fx, fy, fz)
        player.deltaMovement = v
        // Vanilla travel would have moved us after setting velocity; do it here.
        player.move(MoverType.SELF, v)
    }
}
