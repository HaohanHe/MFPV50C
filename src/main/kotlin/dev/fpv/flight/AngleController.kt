/*
 * FPV Craft - MIT
 * Self-leveling attitude controller (clean-room). Only the published control
 * structure of a rate-vs-attitude mixer is used; gains are engineering starting
 * values, tuned in-game, not claims of third-party defaults.
 */
package dev.fpv.flight

import dev.fpv.input.StickChannels
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * Angle / Horizon self-leveling.
 *
 * Model:
 *  - sticks command a desired body inclination relative to the world level plane;
 *  - a proportional law on the body-frame attitude error commands body rates;
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

    /** Measured body rates [pitchNoseDownDps, rollRightDps, yawRightDps] from last frame. */
    val measuredDps = FloatArray(3)

    /** Telemetry for OSD. */
    var targetRollDeg = 0f
        private set
    var targetPitchDeg = 0f
        private set

    /**
     * Re-baseline the level plane onto the current attitude so that switching
     * into self-leveling causes no attitude jump.
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

        // Target world attitude, same YXZ form as FlightController.engage().
        //   angY = PI - headingRad, angX = -pitchRad, angZ = -rollRad
        // (angZ sign: roll right integrates as rotation about -Z, i.e. qZ(-roll)).
        val target = Quaternionf().rotationYXZ(
            (PI - lockedHeadingDeg * PI / 180.0).toFloat(),
            (-targetPitchDeg * PI / 180.0).toFloat(),
            (-targetRollDeg * PI / 180.0).toFloat(),
        )

        val err = AttitudeMath.attitudeErrorBodyRad(attitude, target)
        val pitchErrDeg = (err[0] * 180.0 / PI).toFloat()  // + = nose-down needed
        val rollErrDeg = (err[2] * 180.0 / PI).toFloat()    // + = roll-right needed

        var pitchCmd = cfg.angleP * pitchErrDeg - ANGLE_D * measuredDps[0]
        var rollCmd = cfg.angleP * rollErrDeg - ANGLE_D * measuredDps[1]
        pitchCmd = pitchCmd.coerceIn(-cfg.pitch.max, cfg.pitch.max)
        rollCmd = rollCmd.coerceIn(-cfg.roll.max, cfg.roll.max)

        return floatArrayOf(pitchCmd, rollCmd, yawDps)
    }

    /**
     * HORIZON mode: linearly blend self-leveling (center) into acro rates (edge).
     *   s = max(|roll|, |pitch|);  out = (1-s)*angle + s*acro.
     * Yaw always stays acro.
     *
     * @return [pitchNoseDownDps, rollRightDps, yawRightDps]
     */
    fun horizonRates(ch: StickChannels, attitude: Quaternionf, dt: Float, cfg: FpvConfig): FloatArray {
        val angle = angleRates(ch, attitude, dt, cfg)
        val acroPitch = Rates.actual(ch.pitch, cfg.pitch.center, cfg.pitch.max, cfg.pitch.expo)
        val acroRoll = Rates.actual(ch.roll, cfg.roll.center, cfg.roll.max, cfg.roll.expo)
        val s = max(abs(ch.roll), abs(ch.pitch)).coerceIn(0f, 1f)
        val pitch = (1f - s) * angle[0] + s * acroPitch
        val roll = (1f - s) * angle[1] + s * acroRoll
        return floatArrayOf(pitch, roll, angle[2]) // yaw always acro
    }

    companion object {
        /**
         * Damping gain on measured body rate (deg/s commanded per deg/s measured).
         * Engineering starting value; 0 = pure P. Kept internal (not in config)
         * until in-game tuning data exists.
         */
        private const val ANGLE_D = 0.0f
    }
}
