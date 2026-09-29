/*
 * FPV Craft - MIT
 * "Bind by moving the stick" detection: given a raw axis vector and a center
 * reference, find the single axis the user is actually moving (the one with
 * the largest deviation from center that is also beyond a threshold).
 * Clean-room heuristic matching common receiver-setup wizards.
 */
package dev.fpv.input

import dev.fpv.flight.Defaults
import kotlin.math.abs

object AxisLearner {

    /**
     * @param raw     current raw axis vector
     * @param center  per-axis center reference (from the center-calibration step)
     * @param exclude axis indices already bound to other channels
     * @return the most-deviated axis index, or -1 when none qualifies
     */
    fun detect(raw: FloatArray, center: FloatArray, exclude: Set<Int>): Int {
        var best = -1
        var bestDev = Defaults.AXIS_LEARN_THRESHOLD
        for (i in raw.indices) {
            if (i in exclude) continue
            val mid = if (i in center.indices) center[i] else Defaults.RAW_RANGE_MID
            val dev = abs(raw[i] - mid)
            if (dev > bestDev) {
                bestDev = dev
                best = i
            }
        }
        return best
    }
}
