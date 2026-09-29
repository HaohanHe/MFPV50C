/*
 * FPV Craft - MIT
 * Headfree (headless) transform. When headfree is enabled the craft latches its
 * current yaw as the reference heading [yaw0]. Thereafter the roll/pitch sticks
 * are interpreted in that locked earth frame rather than relative to the nose:
 * the earth-frame stick vector is rotated by (currentYaw - yaw0) into the
 * body frame before the rate controller sees it.
 *
 * Yaw stick itself is unaffected (it always rotates the nose).
 *
 * NOTE: the rotation sign convention below is the clean-room choice; the exact
 * handedness relative to a real radio must be confirmed on the bench.
 */
package dev.fpv.flight

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HeadfreeTransform {

    /** Latched reference heading, MC yaw convention, degrees. */
    var yaw0Deg: Float = 0f
        private set

    private var locked = false

    /** Latch the current heading as the reference (call on rising enable edge). */
    fun lock(currentYawDeg: Float) {
        yaw0Deg = currentYawDeg
        locked = true
    }

    fun unlock() { locked = false }

    val isLocked: Boolean get() = locked

    /**
     * Rotate an earth-frame (roll, pitch) stick vector into the body frame.
     *
     * @param earthRoll  roll-right stick in the locked earth frame, -1..1
     * @param earthPitch pitch-forward stick in the locked earth frame, -1..1
     * @param currentYawDeg current heading, MC yaw convention, degrees
     * @return body-frame [roll, pitch]
     */
    fun apply(earthRoll: Float, earthPitch: Float, currentYawDeg: Float): FloatArray {
        if (!locked) return floatArrayOf(earthRoll, earthPitch)
        val d = (currentYawDeg - yaw0Deg) * (PI / 180.0).toFloat()
        val c = cos(d)
        val s = sin(d)
        // Rotate the earth-frame vector by the heading delta into body frame.
        val bodyRoll = earthRoll * c + earthPitch * s
        val bodyPitch = earthPitch * c - earthRoll * s
        return floatArrayOf(bodyRoll, bodyPitch)
    }
}
