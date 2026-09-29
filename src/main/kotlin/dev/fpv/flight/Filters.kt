/*
 * FPV Craft - MIT
 * Clean-room lag/transient filters built from the standard first-order RC-lag
 * recurrence (public mathematics; no third-party source copied).
 *
 * PT1:  y += k (x - y),  k = dt / (RC + dt),  RC = 1 / (2*pi*cutoff)
 * PT2/PT3: two/three PT1 stages cascaded at the same cutoff. The published
 * setpoint smoother that turns sharp stick steps into smooth target rates is
 * a third-order lag of exactly this cascaded form.
 *
 * One filter instance is stateful and tracks one signal; call [update] once
 * per frame on the render thread with a fixed, positive dt.
 */
package dev.fpv.flight

import kotlin.math.PI

/** First-order low-pass lag. A cutoff <= 0 makes the filter transparent. */
class Pt1(var cutoffHz: Float, private var y: Float = 0f) {

    fun update(x: Float, dt: Float): Float {
        if (cutoffHz <= 0f || dt <= 0f) {
            y = x
            return y
        }
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val k = (dt / (rc + dt)).coerceIn(0f, 1f)
        y += k * (x - y)
        return y
    }

    fun reset(x: Float = 0f) { y = x }

    val value: Float get() = y
}

/** Second-order lag: two cascaded PT1 stages. */
class Pt2(cutoffHz: Float) {
    private val a = Pt1(cutoffHz)
    private val b = Pt1(cutoffHz)

    fun update(x: Float, dt: Float): Float = b.update(a.update(x, dt), dt)

    fun reset(x: Float = 0f) { a.reset(x); b.reset(x) }
}

/** Third-order lag: three cascaded PT1 stages (setpoint-smoother shape). */
class Pt3(var cutoffHz: Float) {
    private val a = Pt1(cutoffHz)
    private val b = Pt1(cutoffHz)
    private val c = Pt1(cutoffHz)

    fun update(x: Float, dt: Float): Float =
        c.update(b.update(a.update(x, dt), dt), dt)

    fun reset(x: Float = 0f) { a.reset(x); b.reset(x); c.reset(x) }

    /** Re-cut all stages when the configured cutoff changes. */
    fun setCutoff(hz: Float) {
        cutoffHz = hz; a.cutoffHz = hz; b.cutoffHz = hz; c.cutoffHz = hz
    }
}

/**
 * First-order high-pass: y = x - lowpass(x). Its output is the fast transient
 * of a step, which decays at the given cutoff - the standard building block for
 * a throttle "boost" term added on top of the steady throttle curve.
 */
class HighPass(cutoffHz: Float) {
    private val lp = Pt1(cutoffHz)

    fun update(x: Float, dt: Float): Float = x - lp.update(x, dt)

    fun reset(x: Float = 0f) = lp.reset(x)
}
