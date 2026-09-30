/*
 * FPV Craft - MIT
 * Pure, headless-testable logic for the REMOTE vanilla-server compatibility layer (P-B).
 *
 * On a real server we cannot freely rewrite velocity, so we map the FPV attitude onto
 * the vanilla player instead of cancelling travel:
 *   - the nose vector  nose = attitude*(0,0,-1)  is converted to vanilla yaw/pitch and
 *     applied via the vanilla Entity.changeLookDirection(d,e) (which itself does
 *     yaw += d*0.15, pitch += e*0.15). We invert that 0.15 scale so one per-tick call
 *     reaches the target look exactly.
 *   - roll is turned into a *coordinated-turn* yaw/pitch bias baked into the look
 *     (compatibility mapping, NOT real fixed-wing flight).
 *   - throttle is realized with fireworks rockets, gated by a threshold and a minimum
 *     interval; a server setback (rubber-band) widens the interval and softens the turn.
 *
 * No Minecraft types appear here so the math can be unit-tested on a plain JVM.
 */
package dev.fpv.flight

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.asin
import kotlin.math.sqrt

class ServerCompatLogic(val cfg: ServerCompatConfig) {

    /** Vanilla Entity.changeLookDirection internal scale. */
    private val LOOK_SCALE = 0.15f

    /** Ticks since last firework use (state). */
    private var ticksSinceFirework = Int.MAX_VALUE

    /** Setback penalty 0..1 grown after a rubber-band, decayed over time. */
    private var setbackPenalty = 0f

    // ---- Nose -> vanilla look ----

    /**
     * Convert a world nose (forward unit) vector to vanilla (yawDeg, pitchDeg).
     * MC look dir = (-sin(yaw)*cos(pitch), -sin(pitch), cos(yaw)*cos(pitch)).
     */
    fun noseToYawPitch(nx: Float, ny: Float, nz: Float): FloatArray {
        val len = sqrt((nx*nx + ny*ny + nz*nz).toDouble()).toFloat().coerceAtLeast(1e-6f)
        val dx = nx / len; val dy = ny / len; val dz = nz / len
        val pitch = -Math.toDegrees(asin(dy.coerceIn(-1f, 1f)).toDouble()).toFloat()
        val yaw = Math.toDegrees(atan2((-dx).toDouble(), dz.toDouble())).toFloat()
        return floatArrayOf(yaw, pitch)
    }

    /**
     * Inverse of changeLookDirection: given current and target (yawDeg,pitchDeg),
     * return (d,e) such that one vanilla call reaches the target exactly
     * (d = deltaYaw/0.15, e = deltaPitch/0.15), yaw delta wrapped to [-180,180].
     */
    fun lookDelta(curYaw: Float, curPitch: Float, targetYaw: Float, targetPitch: Float): FloatArray {
        var dYaw = (targetYaw - curYaw) % 360f
        if (dYaw > 180f) dYaw -= 360f
        if (dYaw < -180f) dYaw += 360f
        val dPitch = (targetPitch - curPitch).coerceIn(-90f, 90f)
        return floatArrayOf(dYaw / LOOK_SCALE, dPitch / LOOK_SCALE)
    }

    // ---- Coordinated turn ----

    /**
     * Map current body roll (deg, +right) and roll rate to an extra (yawDeg, pitchDeg)
     * bias baked into the vanilla look. Returns zeros when disabled.
     */
    fun coordinatedTurn(rollDeg: Float): FloatArray {
        if (!cfg.coordinatedTurn) return floatArrayOf(0f, 0f)
        val gain = cfg.coordTurnGain * (1f - 0.5f * setbackPenalty)
        val extraYaw = rollDeg * gain
        val pitchComp = -cfg.coordPitchCompensation * kotlin.math.abs(rollDeg)
        return floatArrayOf(extraYaw, pitchComp)
    }

    // ---- Firework boost ----

    /** Record that a tick elapsed (ages the firework interval and the setback penalty). */
    fun tick() {
        if (ticksSinceFirework < Int.MAX_VALUE) ticksSinceFirework++
        setbackPenalty *= 0.98f
    }

    /** Effective firework interval in ticks, widened by setback penalty. */
    fun effectiveFireworkInterval(): Int =
        (cfg.fireworkMinIntervalTicks * (1f + 3f * setbackPenalty)).toInt()

    /**
     * Decide whether to fire a firework this tick.
     * @param throttle current throttle 0..1
     * @param fallFlying whether the player is gliding (elytra/fall-fly)
     * @param holdingFirework whether the player holds a firework in main/off hand
     */
    fun shouldFirework(throttle: Float, fallFlying: Boolean, holdingFirework: Boolean): Boolean {
        if (!cfg.compatEnabled || !cfg.fireworkEnabled) return false
        if (!fallFlying || !holdingFirework) return false
        if (throttle < cfg.fireworkThrottleThreshold) return false
        return ticksSinceFirework >= effectiveFireworkInterval()
    }

    /** Call when a firework was actually used. */
    fun onFired() { ticksSinceFirework = 0 }

    // ---- Anti-kick / setback ----

    /** True when the soft speed limit should be applied right now. */
    fun speedLimitActive(remote: Boolean, creativeOrSinglePlayer: Boolean): Boolean =
        cfg.softSpeedLimit && cfg.antiKick && remote && !creativeOrSinglePlayer

    /**
     * React to a detected rubber-band / setback: accept the server position (handled by
     * vanilla), raise the penalty which widens firework interval and softens the turn.
     */
    fun onSetback() { if (cfg.antiKick) setbackPenalty = 1f }

    fun currentPenalty() = setbackPenalty
}
