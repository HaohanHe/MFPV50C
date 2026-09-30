/*
 * FPV Craft - MIT
 * Stick-to-rate curves implemented clean-room from the publicly documented
 * Betaflight "actual/legacy rates" mathematical model. Mathematics itself is
 * not copyrightable; no third-party source code is copied.
 */
package dev.fpv.flight

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

object Rates {

    /**
     * Betaflight-compatible Actual Rates, clean-room reimplementation of the
     * published mathematical model (no source copied).
     *
     * @param x      stick position in -1..1
     * @param center center sensitivity (deg/s at small deflection), BF default 70
     * @param max    maximum rate at full deflection (deg/s), BF default 670
     * @param expo   0..1, softens the center, BF default 0
     */
    fun actual(x: Float, center: Float, max: Float, expo: Float): Float {
        val ax = abs(x)
        // expof = rcCommandfAbs * (power5(rcCommandf) * expo + rcCommandf * (1 - expo))
        val curve = ax * (pow5(x) * expo + x * (1f - expo))
        val stickMovement = max(0f, max - center)
        return x * center + stickMovement * curve
    }

    /**
     * Legacy Betaflight-compatible rates (rcRate / superRate / expo),
     * clean-room reimplementation of the published model. This is the
     * "BETAFLIGHT" branch of the rate-type trio (ACTUAL / BETAFLIGHT / QUICK).
     */
    fun legacy(x: Float, rcRate: Float, superRate: Float, expo: Float): Float {
        var cmd = x
        if (expo != 0f) {
            cmd = cmd * pow3(abs(cmd)) * expo + cmd * (1f - expo)
        }
        var rate = rcRate
        if (rate > 2f) {
            rate += RC_RATE_INCREMENTAL * (rate - 2f)
        }
        var angleRate = 200f * rate * cmd
        if (superRate != 0f) {
            val superFactor = 1f / (1f - abs(cmd) * superRate).coerceIn(0.01f, 1f)
            angleRate *= superFactor
        }
        return angleRate
    }

    /**
     * Quick (RaceFlight-style) rates, clean-room reimplementation of the third
     * published rate branch (the "QUICK" option alongside ACTUAL and BETAFLIGHT).
     *
     * Unlike ACTUAL's quintic center softening, the quick model ramps the base
     * rate linearly with stick and bows the mid-stick region toward [center]
     * with a single quadratic (1-|x|) expo term - a more direct, predictable
     * feel. Sign-preserving, zero at center, reaches [max] at full deflection.
     *
     * @param x      stick position -1..1
     * @param center base rate at a small deflection (deg/s)
     * @param max    rate at full deflection (deg/s)
     * @param expo   0..1, softens mid-stick toward center
     */
    fun quick(x: Float, center: Float, max: Float, expo: Float): Float {
        val ax = abs(x)
        val e = expo.coerceIn(0f, 1f)
        val mid = ax * (1f - e * (1f - ax))
        val span = (max - center).coerceAtLeast(0f)
        return x * center + sign(x) * span * mid
    }

    private const val RC_RATE_INCREMENTAL = 14.54f

    private fun pow3(x: Float): Float = x * x * x
    private fun pow5(x: Float): Float = x * x * x * x * x
}
