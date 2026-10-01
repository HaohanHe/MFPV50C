/*
 * FPV Craft - MIT
 *
 * Pure-logic extraction of the FPV OSD artificial-horizon / pitch-ladder layout so
 * the "stable flight -> stable HUD" contract can be unit-tested on a plain JVM.
 *
 * The on-screen horizon group must derive from the SAME, already-smoothed attitude
 * the camera uses (FpvClient.flight.attitude) -- never from a noisy intermediate.
 * Given that attitude it returns the group's pixel translation (y) and roll angle,
 * and the integer pixel row of every pitch-ladder tick. Under a CONSTANT attitude
 * these outputs MUST be bit-identical frame to frame (sub-pixel translation is
 * rounded to integer rows for the ladder so the HUD cannot shimmer at 60fps).
 */
package dev.fpv.flight

import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.asin
import kotlin.math.atan2

object OsdLayoutMath {

    /** Roll angle (rad) of the horizon group for this attitude (same convention as camera). */
    fun rollRad(attitude: Quaternionf): Float {
        val inv = Quaternionf(attitude).conjugate()
        val bodyUp = Vector3f(0f, 1f, 0f).rotate(inv)
        return atan2(bodyUp.x, bodyUp.y)
    }

    /** Pitch (deg, +nose down) for this attitude. */
    fun pitchDeg(attitude: Quaternionf): Float {
        val inv = Quaternionf(attitude).conjugate()
        val bodyFwd = Vector3f(0f, 0f, -1f).rotate(inv)
        return Math.toDegrees(asin((-bodyFwd.y).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /**
     * Vertical group shift (pixels): nose-down pitches the view down so the level
     * horizon rises. Integer-rounded so identical pitch -> identical pixel row.
     */
    fun groupDyPx(attitude: Quaternionf): Int =
        Math.round(-pitchDeg(attitude) * Defaults.OSD_PITCH_PX_PER_DEG)

    /** Integer pixel rows of the visible pitch-ladder ticks (data-driven refs). */
    fun ladderTickRows(): IntArray {
        val rows = ArrayList<Int>()
        for (absL in Defaults.OSD_PITCH_LADDER_DEG) {
            for (signedL in intArrayOf(absL, -absL)) {
                val ly = signedL * Defaults.OSD_PITCH_PX_PER_DEG
                if (ly < -Defaults.OSD_PITCH_LADDER_RANGE_PX ||
                    ly > Defaults.OSD_PITCH_LADDER_RANGE_PX
                ) continue
                rows.add(Math.round(ly))
            }
        }
        return rows.toIntArray()
    }
}
