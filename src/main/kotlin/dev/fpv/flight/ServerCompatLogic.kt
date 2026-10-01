/*
 * FPV Craft - MIT
 * Pure, headless-testable logic for the REMOTE vanilla-server compatibility layer.
 *
 * On a real server we cannot freely rewrite velocity, so we map the FPV attitude
 * onto the vanilla player instead of cancelling travel:
 *   - the nose vector  nose = attitude*(0,0,-1)  is converted to vanilla yaw/pitch and
 *     applied via the vanilla Entity.changeLookDirection(d,e) (which itself does
 *     yaw += d*0.15, pitch += e*0.15). We invert that 0.15 scale so one per-tick call
 *     reaches the smoothed target look.
 *   - bank (roll) drives a *sustained* coordinated-turn yaw-rate integrator
 *     (omega ~= g*tan(phi)/V): pure roll curves the heading without the pilot
 *     holding yaw, and holding the bank keeps the turn going. This is the remote
 *     equivalent of lift tilting with the body-up axis (the local build already
 *     does real body-up lift in TranslationalDynamics).
 *   - throttle is realized with fireworks rockets. The ignite beat is DERIVED from
 *     the vanilla firework thrust envelope (FireworkEnvelope) so that rockets
 *     overlap continuously (no thrust gap) without over-saturating the velocity pin.
 *   - a server setback (rubber-band) widens the interval and softens the turn.
 *
 * No Minecraft types appear here so the math can be unit-tested on a plain JVM.
 */
package dev.fpv.flight

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.math.tan

class ServerCompatLogic(val cfg: ServerCompatConfig) {

    /** Vanilla Entity.changeLookDirection internal scale. */
    private val LOOK_SCALE = 0.15f

    // ---- persistent beat state (must survive across ticks; the mixin owns ONE instance) ----
    private var ticksSinceFirework = Int.MAX_VALUE

    /** Setback penalty 0..1 grown after a rubber-band, decayed over time. */
    private var setbackPenalty = 0f

    /** Bank-driven accumulated turn yaw offset (deg). Integrates while bank is held. */
    private var turnHeadingDeg = 0f

    /** Look low-pass state (deg), lazily seeded on first use. */
    private var lookSeeded = false
    private var lookYawSmooth = 0f
    private var lookPitchSmooth = 0f

    /** Throttle-on hysteresis latch: latched on until throttle drops below (threshold - hys). */
    private var boostLatched = false

    private val envelope = FireworkEnvelope(Defaults.FW_POWER_DEFAULT)

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

    // ---- Bank-driven coordinated turn (real-roll equivalence) ----

    /**
     * Advance the bank -> turn-rate integrator one tick. While a bank angle phi is
     * held, the heading yaws at  omega = gain * tan(phi) / speed  (the textbook
     * coordinated-turn rate g*tan(phi)/V), so pure roll curves the trajectory
     * without the pilot holding yaw. Level bank (phi~0) freezes the accumulated
     * heading (the turn has already changed direction; it does not unwind).
     * @return the extra yaw deg accumulated THIS tick (for telemetry).
     */
    fun integrateTurn(bankDeg: Float, speedBpt: Float, dtTicks: Float = 1f): Float {
        if (!cfg.coordinatedTurn) return 0f
        val gain = cfg.coordTurnGain * (1f - 0.5f * setbackPenalty)
        val phi = Math.toRadians((bankDeg * gain).toDouble())
        val v = speedBpt.coerceAtLeast(Defaults.TURN_REF_SPEED_BPT)
        var dYaw = (Defaults.TURN_RATE_GAIN * tan(phi) / v).toFloat()
        dYaw = dYaw.coerceIn(-Defaults.TURN_MAX_YAW_PER_TICK_DEG, Defaults.TURN_MAX_YAW_PER_TICK_DEG)
        dYaw *= dtTicks
        turnHeadingDeg += dYaw
        // Wrap so the offset stays bounded.
        turnHeadingDeg = ((turnHeadingDeg + 180f) % 360f + 360f) % 360f - 180f
        return dYaw
    }

    /** Current accumulated turn heading offset (deg). */
    fun currentTurnHeadingDeg(): Float = turnHeadingDeg

    /**
     * Extra (yawDeg, pitchDeg) baked into the vanilla look: the accumulated turn
     * heading plus a pitch compensation for bank-induced altitude loss.
     */
    fun coordinatedTurn(bankDeg: Float): FloatArray {
        if (!cfg.coordinatedTurn) return floatArrayOf(0f, 0f)
        val pitchComp = -Defaults.TURN_PITCH_COMP_DEG_PER_DEG * Math.abs(bankDeg)
        return floatArrayOf(turnHeadingDeg, pitchComp)
    }

    // ---- Firework boost ----

    /** Record that a tick elapsed (ages the beat, decays the setback penalty). */
    fun tick() {
        if (ticksSinceFirework < Int.MAX_VALUE) ticksSinceFirework++
        setbackPenalty *= 0.98f
    }

    /**
     * Continuous ignite interval (ticks), DERIVED from the firework thrust envelope
     * so rockets overlap (no thrust gap) without over-saturating the velocity pin.
     * Widened by a setback penalty (anti-kick) and by the smooth speed limiter.
     */
    fun effectiveFireworkInterval(): Int {
        var base = envelope.recommendedIntervalTicks()
        // Honour the configured anti-spam floor, but the envelope beat leads.
        if (cfg.fireworkMinIntervalTicks > 0) base = maxOf(base, 1)
        val setbackWiden = 1f + 3f * setbackPenalty
        return (base * setbackWiden).toInt().coerceAtLeast(1)
    }

    /**
     * Smooth continuous speed-limiter gain on the firework beat: 1.0 below the knee,
     * rolling off to 0.0 at the roof via a smooth cosine taper (NEVER on/off).
     * Returns a multiplier on the ignite RATE (1 = full rate, 0 = no new rockets).
     * Only meaningful when the limiter is active (remote anti-kick); local/creative
     * never call this.
     */
    fun speedLimitGain(speedBpt: Float): Float {
        val knee = Defaults.SPEED_LIM_KNEE_BPT
        val roof = Defaults.SPEED_LIM_ROOF_BPT
        if (speedBpt <= knee) return 1f
        if (speedBpt >= roof) return 0f
        val u = ((speedBpt - knee) / (roof - knee)).coerceIn(0f, 1f)
        // Smooth cosine taper: 1 at knee -> 0 at roof, continuous in value AND slope.
        return 0.5f * (1f + cos(PI * u)).toFloat()
    }

    /**
     * Decide whether to ignite a firework this tick, with throttle hysteresis so the
     * boost latches on over threshold and stops cleanly below it (no residual queue).
     * @param throttle current throttle 0..1 (already filtered)
     */
    fun shouldFirework(throttle: Float, fallFlying: Boolean, holdingFirework: Boolean,
                       speedBpt: Float = 0f): Boolean {
        if (!cfg.compatEnabled || !cfg.fireworkEnabled) return false
        if (!fallFlying || !holdingFirework) { boostLatched = false; return false }
        val onThr = cfg.fireworkThrottleThreshold
        val offThr = onThr - Defaults.FW_THROTTLE_HYSTERESIS
        if (!boostLatched && throttle >= onThr) boostLatched = true
        else if (boostLatched && throttle <= offThr) boostLatched = false
        if (!boostLatched) return false
        // Smooth limiter: near the speed roof, widen the beat continuously. ONLY when
        // the operator enabled the soft speed limit (remote anti-kick); default off so
        // full throttle flies un-capped. The limiter gain is a continuous cosine taper,
        // never an on/off switch, so it cannot bang-bang at the cap.
        val gain = if (cfg.softSpeedLimit) speedLimitGain(speedBpt) else 1f
        if (gain <= 0.01f) return false
        val interval = (effectiveFireworkInterval() / gain.toDouble()).toInt()
        return ticksSinceFirework >= interval
    }

    /** Call when a firework was actually used. */
    fun onFired() { ticksSinceFirework = 0 }

    /**
     * Low-pass the commanded (yaw,pitch) before it reaches self.turn(), so attitude
     * ripple is not amplified directly into the (look-pointing) firework velocity.
     * @return smoothed [yaw, pitch]
     */
    fun smoothLook(targetYaw: Float, targetPitch: Float, dt: Float): FloatArray {
        if (!lookSeeded) { lookYawSmooth = targetYaw; lookPitchSmooth = targetPitch; lookSeeded = true }
        val hz = Defaults.LOOK_LPF_HZ
        if (hz <= 0f || dt <= 0f) { lookYawSmooth = targetYaw; lookPitchSmooth = targetPitch;
            return floatArrayOf(lookYawSmooth, lookPitchSmooth) }
        val a = (dt * hz * 2.0 * PI / (1.0 + dt * hz * 2.0 * PI)).toFloat().coerceIn(0f, 1f)
        // Wrap yaw onto the smoothed value for the lerp.
        var dyaw = (targetYaw - lookYawSmooth) % 360f
        if (dyaw > 180f) dyaw -= 360f
        if (dyaw < -180f) dyaw += 360f
        lookYawSmooth += a * dyaw
        lookPitchSmooth += a * (targetPitch - lookPitchSmooth)
        return floatArrayOf(lookYawSmooth, lookPitchSmooth)
    }

    fun resetLook() { lookSeeded = false }

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
