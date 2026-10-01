/*
 * FPV Craft - MIT
 * Self-leveling attitude controller (clean-room). Only the published control
 * structure of a rate-vs-attitude mixer is used; gains are engineering
 * starting values tuned in-game, not claims of third-party defaults.
 */
package dev.fpv.flight

import dev.fpv.input.StickChannels
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Angle / Horizon self-leveling.
 *
 * Model:
 *  - sticks command a desired body inclination relative to the world level plane;
 *  - a PD law on the body-frame attitude error commands body rates;
 *  - yaw remains an open-loop heading-rate command (the locked heading integrates
 *    the yaw stick, exactly like a real self-leveling copter).
 *
 * Error extraction: qErr = conj(qCurrent) ⊗ qTarget, axis-angle decomposed in
 * the body frame. Roll/pitch errors close the loop; yaw error is intentionally
 * not fed back (heading is integrated open-loop).
 */
class AngleController {

    /** World heading (MC yaw convention, deg) the level plane is locked to. */
    var lockedHeadingDeg = 0f
        private set

    /** Measured body rates [pitchNoseDownDps, rollRightDps, yawRightDps]. */
    val measuredDps = FloatArray(3)

    /** Telemetry for OSD. */
    var targetRollDeg = 0f
        private set
    var targetPitchDeg = 0f
        private set

    /** Horizon leveling-strength rise filter state (PT1; rise-limited, fall immediate). */
    private var horizonSmoothed = 0f

    /** Latest computed horizon leveling strength 0..1 (telemetry / headless). */
    var horizonStrength = 0f
        private set

    /**
     * Re-baseline the level plane onto the current attitude so that switching
     * into self-leveling causes no jump.
     */
    fun rebaseline(attitude: Quaternionf) {
        val e = Vector3f()
        attitude.getEulerAnglesYXZ(e)
        // engage() builds rotationYXZ(PI - yawRad, -pitchRad, 0), so:
        lockedHeadingDeg = ((PI - e.y) * 180.0 / PI).toFloat()
        targetRollDeg = 0f
        targetPitchDeg = 0f
    }

    /**
     * ANGLE mode: stick -> target inclination -> PD on body error -> body rates.
     *
     * @return [pitchNoseDownDps, rollRightDps, yawRightDps]
     */
    fun angleRates(ch: StickChannels, attitude: Quaternionf, dt: Float, cfg: FpvConfig): FloatArray {
        targetRollDeg = (ch.roll * cfg.angleMaxDeg).coerceIn(-cfg.angleMaxDeg, cfg.angleMaxDeg)
        targetPitchDeg = (ch.pitch * cfg.angleMaxDeg).coerceIn(-cfg.angleMaxDeg, cfg.angleMaxDeg)

        // Yaw stick is an open-loop world-heading rate.
        val yawDps = Rates.actual(ch.yaw, cfg.yaw.center, cfg.yaw.max, cfg.yaw.expo)
        lockedHeadingDeg += yawDps * dt

        // Target world attitude, same YXZ form as FlightController.engage():
        //   angY = PI - headingRad, angX = -pitchRad, angZ = -rollRad
        val target = Quaternionf().rotationYXZ(
            (PI - lockedHeadingDeg * PI / 180.0).toFloat(),
            (-targetPitchDeg * PI / 180.0).toFloat(),
            (-targetRollDeg * PI / 180.0).toFloat(),
        )

        val err = AttitudeMath.attitudeErrorBodyRad(attitude, target)
        val pitchErrDeg = (err[0] * 180.0 / PI).toFloat()  // + = nose-down needed
        val rollErrDeg = (err[2] * 180.0 / PI).toFloat()    // + = roll-right needed

        var pitchCmd = cfg.angleP * pitchErrDeg - cfg.angleD * measuredDps[0]
        var rollCmd = cfg.angleP * rollErrDeg - cfg.angleD * measuredDps[1]
        pitchCmd = pitchCmd.coerceIn(-cfg.pitch.max, cfg.pitch.max)
        rollCmd = rollCmd.coerceIn(-cfg.roll.max, cfg.roll.max)

        return floatArrayOf(pitchCmd, rollCmd, yawDps)
    }

    /**
     * HORIZON mode (published BF pid.c:542-561 calcHorizonLevelStrength):
     *
     *   strength = max((HORIZON_LIMIT_DEG - |inclination|)/LIMIT, 0)
     *            * max(1 - |stick|, ignore) * gain
     *
     * The leveling weight therefore fades with BOTH the current bank angle AND the
     * stick deflection, then passes through a rise-limited PT1 (smooth on the way
     * up, immediate on the way down). out = strength*angle + (1-strength)*acro.
     * Center stick / level attitude -> full self-level; full stick / steep bank ->
     * full acro. Yaw always stays acro.
     *
     * @return [pitchNoseDownDps, rollRightDps, yawRightDps]
     */
    fun horizonRates(ch: StickChannels, attitude: Quaternionf, dt: Float, cfg: FpvConfig): FloatArray {
        val angleOut = angleRates(ch, attitude, dt, cfg)
        val acroPitch = Rates.actual(ch.pitch, cfg.pitch.center, cfg.pitch.max, cfg.pitch.expo)
        val acroRoll = Rates.actual(ch.roll, cfg.roll.center, cfg.roll.max, cfg.roll.expo)

        // Current inclination = max(|roll|,|pitch|) deg (euler, same YXZ as rebaseline).
        val e = Vector3f()
        attitude.getEulerAnglesYXZ(e)
        val rollDeg = (-e.z * 180.0 / PI).toFloat()
        val pitchDeg = (-e.x * 180.0 / PI).toFloat()
        val inclination = max(abs(rollDeg), abs(pitchDeg))

        val limit = Defaults.HORIZON_LIMIT_DEG
        val angleFade = max((limit - inclination) / limit, 0f)
        val stick = max(abs(ch.roll), abs(ch.pitch)).coerceIn(0f, 1f)
        val stickFade = max(1f - stick, 0f)
        val raw = (angleFade * stickFade).coerceIn(0f, 1f)

        // Rise-limited PT1: smooth the strength up, but drop immediately when it falls.
        val tau = Defaults.HORIZON_SMOOTH_TAU_SEC.coerceAtLeast(1e-3f)
        val h = dt.coerceIn(1e-4f, 0.1f)
        val k = (h / (tau + h)).coerceIn(0f, 1f)
        horizonSmoothed += k * (raw - horizonSmoothed)
        if (!horizonSmoothed.isFinite()) horizonSmoothed = 0f
        val strength = min(raw, horizonSmoothed).coerceIn(0f, 1f)
        horizonStrength = strength

        val pitch = strength * angleOut[0] + (1f - strength) * acroPitch
        val roll = strength * angleOut[1] + (1f - strength) * acroRoll
        return floatArrayOf(pitch, roll, angleOut[2]) // yaw always acro
    }
}
