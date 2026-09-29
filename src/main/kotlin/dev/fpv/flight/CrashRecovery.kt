/*
 * FPV Craft - MIT
 *
 * Crash-recovery detector, clean-room modeled on the published crash-recovery
 * behavior: while the pilot's sticks are near center but the airframe is
 * tumbling fast (measured body rate far above the commanded setpoint), arm a
 * recovery after a short persistence window.  This detector emits a *signal plus
 * a leveling suggestion*; the integration party temporarily routes control to a
 * self-level (Angle) controller to bring the craft within the recovery angle.
 *
 * Published default thresholds (engineering starting values, tunable in-game):
 *   gyro tumble threshold   400 dps
 *   setpoint mismatch       50 dps  (|measured - setpoint|)
 *   pilot-command gate      350 dps  (|setpoint| below = sticks near center)
 *   trigger persistence     500 ms
 *   recovery dead-zone      10 deg
 *   recovery rate           100 dps
 */
package dev.fpv.flight

import kotlin.math.abs

/** Outcome of one crash-recovery update. */
data class CrashResult(
    val state: State,
    /** Axis that tripped the detector (0 pitch, 1 roll, 2 yaw); -1 when NONE. */
    val triggerAxis: Int,
    /** Suggested level attitude for the integration party's temporary Angle mode. */
    val targetRollDeg: Float,
    val targetPitchDeg: Float,
    /** Suggested correction rate (dps) toward level, already clamped to the recovery rate. */
    val suggestedRollRateDps: Float,
    val suggestedPitchRateDps: Float,
    /** Recovery holds throttle cut; the integration party should obey. */
    val cutThrottle: Boolean,
) {
    enum class State { NONE, RECOVER }

    companion object {
        val NONE = CrashResult(
            state = State.NONE,
            triggerAxis = -1,
            targetRollDeg = 0f,
            targetPitchDeg = 0f,
            suggestedRollRateDps = 0f,
            suggestedPitchRateDps = 0f,
            cutThrottle = false,
        )
    }
}

/**
 * Tumbling detector + recovery suggestion state machine.
 *
 * Inputs per frame:
 *  - setpointDps / measuredDps: [pitch, roll, yaw] in dps
 *  - attitudeDeg: [rollDeg, pitchDeg, yawDeg] current attitude errors from level
 *  - throttle: unused for detection (reserved for future throttling rules)
 */
class CrashRecovery {

    // Published defaults (mutable for in-game tuning).
    var gyroThresholdDps = 400f
    var mismatchThresholdDps = 50f
    var setpointGateDps = 350f
    var triggerMs = 500f
    var recoveryAngleDeg = 10f
    var recoveryRateDps = 100f

    /** Engineering: how long the craft must stay level before recovery releases. */
    private val exitDebounceMs = 300f

    private var armedMs = 0f
    private var exitMs = 0f
    private var recovering = false

    /** Clear state on arm / disarm / mode switch. */
    fun reset() {
        armedMs = 0f
        exitMs = 0f
        recovering = false
    }

    fun update(
        setpointDps: FloatArray,
        measuredDps: FloatArray,
        attitudeDeg: FloatArray,
        throttle: Float,
        dt: Float,
    ): CrashResult {
        val h = dt.coerceIn(1e-4f, 0.1f)
        if (!throttle.isFinite()) return CrashResult.NONE

        if (!recovering) {
            // ── Watch for: fast tumble, far from a small stick command. ──
            var trippedAxis = -1
            for (i in 0..2) {
                val sp = sanitize(setpointDps.getOrElse(i) { 0f })
                val m = sanitize(measuredDps.getOrElse(i) { 0f })
                if (abs(m) > gyroThresholdDps &&
                    abs(m - sp) > mismatchThresholdDps &&
                    abs(sp) < setpointGateDps
                ) {
                    trippedAxis = i
                    break
                }
            }
            armedMs = if (trippedAxis >= 0) armedMs + h * 1000f else 0f
            if (armedMs >= triggerMs) {
                recovering = true
                exitMs = 0f
            }
            return CrashResult.NONE
        }

        // ── Recovery active: pilot takes over if a stick demands real rate;
        //    release once the attitude has settled within the dead-zone. ──
        for (i in 0..2) {
            val sp = sanitize(setpointDps.getOrElse(i) { 0f })
            if (abs(sp) > setpointGateDps) {
                reset()
                return CrashResult.NONE
            }
        }

        val rollDeg = sanitize(attitudeDeg.getOrElse(0) { 0f })
        val pitchDeg = sanitize(attitudeDeg.getOrElse(1) { 0f })
        val leveled = abs(rollDeg) < recoveryAngleDeg && abs(pitchDeg) < recoveryAngleDeg

        if (leveled) {
            exitMs += h * 1000f
            if (exitMs >= exitDebounceMs) {
                reset()
                return CrashResult.NONE
            }
        } else {
            exitMs = 0f
        }

        // Suggest: drive roll/pitch attitude error toward zero at recoveryRate.
        // Error sign convention: +rollDeg = roll-right needed to level; the
        // correction rate must oppose it.
        val k = 5f // engineering proportionality: dps per degree of residual error
        val sugRoll = clamp(-rollDeg * k, recoveryRateDps)
        val sugPitch = clamp(-pitchDeg * k, recoveryRateDps)

        return CrashResult(
            state = CrashResult.State.RECOVER,
            triggerAxis = -1,
            targetRollDeg = 0f,
            targetPitchDeg = 0f,
            suggestedRollRateDps = sugRoll,
            suggestedPitchRateDps = sugPitch,
            cutThrottle = true,
        )
    }

    private fun clamp(v: Float, limit: Float): Float =
        if (v > limit) limit else if (v < -limit) -limit else v

    private fun sanitize(v: Float): Float = if (v.isFinite()) v else 0f
}
