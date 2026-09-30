/*
 * FPV Craft - MIT
 * Clean-room quaternion attitude kinematics. Body axes: right=+X, up=+Y,
 * forward=-Z. Only the well-known rigid-body relation qdot = 0.5 q (0, w) is
 * used; no third-party flight-controller source is reproduced.
 *
 * The logical-axis -> body-axis mapping is read from BodyAxis (the single
 * source of truth shared with attitude integration).
 */
package dev.fpv.flight

import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sin

object AttitudeMath {

    /**
     * Extract the measured body-frame angular velocity (deg/s) implied by a
     * one-frame attitude increment.
     *
     * A world-space attitude post-multiplies a body-frame rotation:
     *   qNew = qOld ⊗ qDelta   =>   qDelta = conj(qOld) ⊗ qNew
     * The short-angle axis-angle of qDelta gives w_body = axis * angle / dt,
     * projected onto each logical axis via BodyAxis.
     *
     * @return [pitchNoseDownDps, rollRightDps, yawRightDps]
     */
    fun bodyRatesDps(qOld: Quaternionf, qNew: Quaternionf, dt: Float): FloatArray {
        val out = FloatArray(3)
        if (dt <= 1e-6f) return out
        val qd = Quaternionf(qOld).conjugate().mul(qNew).normalize()
        unwrapShort(qd)
        val v = axisAngleVectorRad(qd)
        val scale = (180.0 / (PI * dt)).toFloat()
        for (b in BodyAxis.entries) {
            out[b.index] = b.dot(v) * scale
        }
        return out
    }

    /**
     * Body-frame attitude error (rad) that rotates the current attitude onto
     * the target: qErr = conj(qCur) ⊗ qTgt, decomposed as axis*angle.
     *
     * @return [pitchErrRad (+ = nose-down needed), yawErrRad, rollErrRad (+ = roll-right needed)]
     */
    fun attitudeErrorBodyRad(qCur: Quaternionf, qTgt: Quaternionf): FloatArray {
        val qErr = Quaternionf(qCur).conjugate().mul(qTgt).normalize()
        unwrapShort(qErr)
        // Project the short-angle axis vector through the SAME BodyAxis table used
        // by bodyRatesDps, so error signs can never drift from measured-rate signs.
        // (Previously this hand-typed "(ex, ey, -ez)" and got the pitch sign
        // backwards, which made ANGLE pitch positively feed back and flip over.)
        val v = axisAngleVectorRad(qErr)
        val pitchErr = BodyAxis.PITCH.dot(v)
        val yawErr = BodyAxis.YAW.dot(v)
        val rollErr = BodyAxis.ROLL.dot(v)
        return floatArrayOf(pitchErr, yawErr, rollErr)
    }

    /** Fold the double cover: if w < 0, negate the quaternion to take the short path. */
    private fun unwrapShort(q: Quaternionf) {
        if (q.w < 0f) {
            q.x = -q.x; q.y = -q.y; q.z = -q.z; q.w = -q.w
        }
    }

    /** @return axis * angle in radians as (x, y, z) for a normalized quaternion. */
    private fun axisAngleVectorRad(q: Quaternionf): FloatArray {
        var w = q.w
        if (w > 1f) w = 1f else if (w < -1f) w = -1f
        val half = acos(w)          // theta / 2
        val s = sin(half)
        if (s < 1e-6f) return FloatArray(3)
        val k = 2f * half / s       // theta / sin(theta/2)
        return floatArrayOf(q.x * k, q.y * k, q.z * k)
    }
}
