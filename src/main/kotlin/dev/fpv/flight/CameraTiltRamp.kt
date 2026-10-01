/*
 * FPV Craft - MIT
 *
 * Smooth camera-tilt ramp: on arm the user FPV camera tilt (e.g. 25 deg) must NOT
 * snap in one frame (that was the reported "unlock camera jerk"); it ramps in at
 * a bounded per-frame rate over [Defaults.CAMERA_TILT_RAMP_SEC], and ramps back
 * to zero on disarm. Pure state, unit-tested headless.
 */
package dev.fpv.flight

class CameraTiltRamp(
    /** Time (seconds) to go from 0 to full tilt. */
    private val rampSec: Float = Defaults.CAMERA_TILT_RAMP_SEC,
) {
    /** Current smoothed tilt in degrees (always finite). */
    var value: Float = 0f
        private set

    /**
     * Advance the ramp toward [targetDeg] by [dt] seconds. The per-frame step is
     * bounded so a long frame (lag spike) can never produce a visible jump.
     * @return the tilt to apply this frame, in degrees.
     */
    fun update(targetDeg: Float, dt: Float): Float {
        val safeTarget = if (targetDeg.isFinite()) targetDeg else 0f
        val safeDt = if (dt.isFinite() && dt > 0f) dt else 0f
        val rate = (if (rampSec > 0.01f) safeTarget / rampSec else safeTarget * 100f)
        var next = value + rate * safeDt
        // Clamp into [0, target]: on disarm (target 0) this never overshoots.
        val lo = 0f.coerceAtMost(safeTarget)
        val hi = 0f.coerceAtLeast(safeTarget)
        if (next < lo) next = lo
        if (next > hi) next = hi
        value = if (next.isFinite()) next else 0f
        return value
    }
}
