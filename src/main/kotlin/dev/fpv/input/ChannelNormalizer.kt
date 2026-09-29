/*
 * FPV Craft - MIT
 * Clean-room axis normalization. Only public mathematical conventions are
 * used: a centered channel maps its two sides linearly onto [-1,1] with
 * independent slopes (non-proportional sticks), a normal throttle maps
 * [min,max] onto [0,1], and a 3D/reversible throttle maps [min,mid,max]
 * onto [-1,0,1].
 */
package dev.fpv.input

import kotlin.math.abs

object ChannelNormalizer {

    /**
     * Symmetric center channel (roll / pitch / yaw).
     * Raw value around [SlotCalib.rawMid]. Each side is scaled by its own
     * span so endpoints reach exactly +/-1 even when the stick is not
     * centered. A raw-center deadband collapses tiny jitter to zero.
     */
    fun centered(raw: Float, c: SlotCalib): Float {
        if (raw.isNaN() || !raw.isFinite()) return 0f
        if (abs(raw - c.rawMid) <= c.deadzone) return 0f
        val out = if (raw >= c.rawMid) {
            val span = c.rawMax - c.rawMid
            if (span <= 1e-6f) 0f else (raw - c.rawMid) / span
        } else {
            val span = c.rawMid - c.rawMin
            if (span <= 1e-6f) 0f else (raw - c.rawMid) / span
        }
        val signed = if (c.reversed) -out else out
        return signed.coerceIn(-1f, 1f)
    }

    /** Normal throttle: [rawMin, rawMax] -> [0, 1]. Reversed flips 0<->1. */
    fun throttle(raw: Float, c: SlotCalib): Float {
        if (raw.isNaN() || !raw.isFinite()) return 0f
        val span = c.rawMax - c.rawMin
        var t = if (span <= 1e-6f) 0f else (raw - c.rawMin) / span
        t = t.coerceIn(0f, 1f)
        return if (c.reversed) 1f - t else t
    }

    /**
     * Reversible-3D throttle: rawMid -> 0, both sides -> +/-1, with a
     * normalized deadband around center so small spring jitter does not spool
     * the motor.
     */
    fun throttle3d(raw: Float, c: SlotCalib, normalizedDeadband: Float): Float {
        val s = centered(raw, c)
        if (abs(s) < normalizedDeadband) return 0f
        val keep = (abs(s) - normalizedDeadband) / (1f - normalizedDeadband)
        val k = keep.coerceIn(0f, 1f)
        return if (s >= 0f) k else -k
    }
}
