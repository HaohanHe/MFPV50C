/*
 * FPV Craft - MIT
 * Remote vanilla-server compatibility layer (P-B), wired to the local player.
 *
 * On a real server we do NOT cancel travel and do NOT inject off-vanilla velocity.
 * Instead, each tick we map the FPV attitude onto the vanilla player:
 *   - nose = attitude*(0,0,-1) -> smoothed vanilla yaw/pitch, applied via
 *     Entity.changeLookDirection(d,e) (which itself does yaw+=d*0.15, pitch+=e*0.15),
 *     so client prediction and the sent movement packet stay consistent.
 *   - bank (roll attitude) drives a sustained coordinated-turn yaw-rate integrator
 *     (ServerCompatLogic.integrateTurn): pure roll curves the heading without the
 *     pilot holding yaw; holding the bank keeps the turn going.
 *   - throttle above threshold while fall-flying triggers a firework rocket. The
 *     ignite beat is derived from the vanilla thrust envelope so rockets overlap
 *     continuously (no thrust gap) without over-saturating the velocity pin.
 *   - a server setback (rubber-band) is reacted to by the pure logic (interval/turn
 *     backoff); vanilla already snaps us to the server position.
 *
 * IMPORTANT: ServerCompatLogic is ONE persistent instance per session (held in this
 * mixin), so its beat / turn-heading / look-LPF state survives across ticks. The
 * previous per-tick `val logic = ServerCompatLogic(sc)` reset the interval counter
 * to MAX every tick, which bypassed the whole anti-spam beat.
 *
 * All math lives in dev.fpv.flight.ServerCompatLogic (headless-tested); this mixin
 * is only I/O and is wrapped in runCatching so a runtime API mismatch can never crash.
 * [NEEDS LOCAL VERIFICATION] against a real server / Paper / Velocity: remote is an
 * APPROXIMATION (vanilla elytra+fireworks cannot true-hover / on-spot yaw / vertical
 * climb); single-player/creative runs the full quadcopter body-up model.
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import dev.fpv.flight.ServerCompatLogic
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import org.joml.Vector3f
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LocalPlayer::class)
class LocalPlayerMixin {

    /** Persistent compat state (beat / turn heading / look LPF), owned for the session. */
    @Unique private var fpvCompat: ServerCompatLogic? = null
    @Unique private var fpvCompatCfg: dev.fpv.flight.ServerCompatConfig? = null

    @Inject(method = ["tick"], at = [At("HEAD")])
    private fun fpvCompatTick(ci: CallbackInfo) {
        runCatching {
            val self = (this as Any) as LocalPlayer
            val mc = Minecraft.getInstance()
            if (self !== mc.player) return@runCatching
            val cfg = FpvClient.config
            val sc = cfg.serverCompat ?: return@runCatching
            if (!sc.compatEnabled) return@runCatching
            // Only the remote-compat path; single-player runs full translational physics.
            if (mc.isLocalServer) return@runCatching
            // Map attitude to look only while actually flying; disarmed/idle must never
            // fight the mouse (no forced recentering).
            if (!FpvClient.flight.ready) return@runCatching

            // (Re)bind the persistent logic when the config object changes (reload/reset).
            if (fpvCompat == null || fpvCompatCfg !== sc) {
                fpvCompat = ServerCompatLogic(sc)
                fpvCompatCfg = sc
            }
            val logic = fpvCompat!!
            logic.tick()

            // nose = attitude * (0,0,-1); map to vanilla look.
            val attitude = FpvClient.flight.attitude
            val nose = Vector3f(0f, 0f, -1f).rotate(attitude)
            val yp = logic.noseToYawPitch(nose.x(), nose.y(), nose.z())
            // Roll ATTITUDE (bank angle) -> sustained coordinated-turn rate integrator.
            val up = Vector3f(0f, 1f, 0f).rotate(attitude)
            val rollDeg = Math.toDegrees(
                kotlin.math.atan2(up.x().toDouble(), up.y().toDouble())).toFloat()
            // Current horizontal speed (blocks/tick) for turn-rate normalization + limiter.
            val spd = Math.sqrt(
                self.deltaMovement.x * self.deltaMovement.x +
                self.deltaMovement.z * self.deltaMovement.z)
            logic.integrateTurn(rollDeg, spd.toFloat())
            val turn = logic.coordinatedTurn(rollDeg)
            val targetYaw = yp[0] + turn[0]
            val targetPitch = (yp[1] + turn[1]).coerceIn(-90f, 90f)
            // Low-pass the commanded look so attitude ripple is not amplified into thrust.
            val dtTick = 1f / 20f
            val smooth = logic.smoothLook(targetYaw, targetPitch, dtTick)
            val d = logic.lookDelta(self.yRot, self.xRot, smooth[0], smooth[1])
            self.turn(d[0].toDouble(), d[1].toDouble())

            // Throttle -> fireworks rocket (continuous-overlap beat, speed-aware).
            val holdingFirework = holdsFirework(self)
            if (logic.shouldFirework(FpvClient.throttle, self.isFallFlying, holdingFirework, spd.toFloat())) {
                mc.gameMode?.useItem(self, InteractionHand.MAIN_HAND)
                logic.onFired()
            }
        }
    }

    private fun holdsFirework(player: LocalPlayer): Boolean {
        val main = player.mainHandItem
        val off = player.offhandItem
        fun isFw(stack: net.minecraft.world.item.ItemStack) =
            !stack.isEmpty && stack.item == net.minecraft.world.item.Items.FIREWORK_ROCKET
        return isFw(main) || isFw(off)
    }
}
