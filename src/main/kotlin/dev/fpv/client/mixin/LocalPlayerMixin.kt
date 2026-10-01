/*
 * FPV Craft - MIT
 * Remote vanilla-server compatibility layer (P-B), wired to the local player.
 *
 * On a real server we do NOT cancel travel and do NOT inject off-vanilla velocity.
 * Instead, each tick we map the FPV attitude onto the vanilla player:
 *   - nose = attitude*(0,0,-1) -> vanilla yaw/pitch, applied via
 *     Entity.changeLookDirection(d,e) (which itself does yaw+=d*0.15, pitch+=e*0.15),
 *     so client prediction and the sent movement packet stay consistent.
 *   - roll bakes a "coordinated turn" yaw/pitch bias into that look (compat mapping).
 *   - throttle above threshold while fall-flying triggers a firework rocket.
 *   - a server setback (rubber-band) is reacted to by the pure logic (interval/turn
 *     backoff); vanilla already snaps us to the server position.
 *
 * All math lives in dev.fpv.flight.ServerCompatLogic (headless-tested); this mixin is
 * only I/O and is wrapped in runCatching so a runtime API mismatch can never crash.
 * [NEEDS LOCAL VERIFICATION] against a real server / Paper / Velocity.
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import dev.fpv.flight.ServerCompatLogic
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import org.joml.Vector3f
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LocalPlayer::class)
class LocalPlayerMixin {

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

            val logic = ServerCompatLogic(sc)
            logic.tick()

            // nose = attitude * (0,0,-1); map to vanilla look.
            val attitude = FpvClient.flight.attitude
            val nose = Vector3f(0f, 0f, -1f).rotate(attitude)
            val yp = logic.noseToYawPitch(nose.x(), nose.y(), nose.z())
            // Roll ATTITUDE (bank angle, not yaw rate) -> coordinated-turn bias.
            val up = Vector3f(0f, 1f, 0f).rotate(attitude)
            val rollDeg = Math.toDegrees(
                kotlin.math.atan2(up.x().toDouble(), up.y().toDouble())).toFloat()
            val turn = logic.coordinatedTurn(rollDeg)
            val targetYaw = yp[0] + turn[0]
            val targetPitch = (yp[1] + turn[1]).coerceIn(-90f, 90f)
            val d = logic.lookDelta(self.yRot, self.xRot, targetYaw, targetPitch)
            self.turn(d[0].toDouble(), d[1].toDouble())

            // Throttle -> fireworks rocket.
            val holdingFirework = holdsFirework(self)
            if (logic.shouldFirework(FpvClient.throttle, self.isFallFlying, holdingFirework)) {
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
