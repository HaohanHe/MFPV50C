/*
 * FPV Craft - MIT
 * Throttle curve: a two-segment quadratic bezier (clean-room) with a precomputed
 * fixed-length LUT and linear interpolation. thrMid=50 / thrExpo=0 degenerates to
 * the identity (linear) curve.
 *
 *   Left segment  P0=(0,0)  C=(m, m*(1-2e))        P1=(m,m)
 *   Right segment P0=(m,m)  C=(m, m+(1-m)*2e)      P1=(1,1)
 *
 * At e=0 each control point collapses onto its endpoint, yielding straight
 * lines. This is an independent re-derivation of the published shape, not a
 * transcription of any firmware source.
 *
 * Throttle boost adds a high-passed (fast transient) term on top of the shaped
 * steady throttle. The default gain is an engineering equivalent of the
 * published throttle_boost=5 / cutoff 15Hz and must be tuned in-game.
 */
package dev.fpv.flight

import kotlin.math.sqrt

class ThrottleCurve(private val cfg: FpvConfig) {

    private var hp = HighPass(Defaults.THROTTLE_BOOST_CUTOFF_HZ)
    private var hpBuiltCutoff = Defaults.THROTTLE_BOOST_CUTOFF_HZ
    private val lut = FloatArray(Defaults.THR_LUT_SIZE)

    private var builtMid = -1f
    private var builtExpo = -1f

    /**
     * Sample the bezier at input x in 0..1.
     *
     * The control point shares the segment's X coordinate (left Cx=m, right
     * Cx=m), so the bezier is NOT linear in its own parameter. To read y at a
     * given *input* x we must invert the bezier X-curve for the parameter t:
     *   left:  Bx(t) = m(2t - t²) = x  ->  t = 1 - sqrt(1 - x/m)
     *   right: Bx(t) = m + t²(1-m) = x ->  t = sqrt((x - m)/(1 - m))
     * With this inversion, at e=0 each control point lands on its endpoint and
     * the curve collapses to y = x (linear), as documented. Using t = x/m
     * directly (the previous bug) bowed the curve even at zero expo.
     */
    private fun sample(x: Float): Float {
        val m = (cfg.thrMidPct / 100f).coerceIn(0.05f, 0.95f)
        val e = (cfg.thrExpoPct / 100f).coerceIn(0f, 1f)
        return if (x <= m) {
            val u = (x / m).coerceIn(0f, 1f)
            val t = (1f - sqrt((1f - u).toDouble())).toFloat()
            // P0=(0,0), C=(m, m*(1-2e)), P1=(m,m)
            val cY = m * (1f - 2f * e)
            (1 - t) * (1 - t) * 0f + 2 * (1 - t) * t * cY + t * t * m
        } else {
            val u = ((x - m) / (1f - m)).coerceIn(0f, 1f)
            val t = sqrt(u.toDouble()).toFloat()
            // P0=(m,m), C=(m, m+(1-m)*2e), P1=(1,1)
            val cY = m + (1f - m) * 2f * e
            (1 - t) * (1 - t) * m + 2 * (1 - t) * t * cY + t * t * 1f
        }
    }

    private fun rebuild() {
        if (builtMid == cfg.thrMidPct && builtExpo == cfg.thrExpoPct) return
        builtMid = cfg.thrMidPct
        builtExpo = cfg.thrExpoPct
        val n = lut.size - 1
        for (i in 0..n) lut[i] = sample(i.toFloat() / n)
    }

    /** Linear LUT lookup for a normal-mode throttle input 0..1. */
    fun lookup(x: Float): Float {
        rebuild()
        val p = x.coerceIn(0f, 1f) * (lut.size - 1)
        val i = p.toInt().coerceIn(0, lut.size - 2)
        val f = p - i
        return lut[i] * (1f - f) + lut[i + 1] * f
    }

    /**
     * Apply curve + boost to the raw throttle channel.
     *
     * @param raw           normalized throttle (0..1 normal; -1..1 in 3D)
     * @param dt            frame seconds
     * @param reversible3D  when true the channel is centered/signed: skip the
     *                      0..1 curve but still add the symmetric boost transient.
     */
    fun apply(raw: Float, dt: Float, reversible3D: Boolean): Float {
        return if (reversible3D) {
            val boost = if (cfg.throttleBoostEnabled) boost(raw, dt) else 0f
            (raw + boost).coerceIn(-1f, 1f)
        } else {
            val shaped = lookup(raw)
            val boost = if (cfg.throttleBoostEnabled) boost(shaped, dt) else 0f
            (multiPointGain(raw) * (shaped + boost)).coerceIn(0f, 1f)
        }
    }

    /**
     * Multi-point throttle gain: linear piecewise interpolation through
     * (0,thrLow) -> (0.5,thrMid) -> (1,thrHigh) from the active airframe.
     * Shapes low/mid/high throttle independently (default ~1 = neutral).
     */
    private fun multiPointGain(x: Float): Float {
        val af = cfg.activeAirframe()
        val t = x.coerceIn(0f, 1f)
        return if (t <= 0.5f) {
            af.thrLow + (af.thrMid - af.thrLow) * (t / 0.5f)
        } else {
            af.thrMid + (af.thrHigh - af.thrMid) * ((t - 0.5f) / 0.5f)
        }
    }

    private fun boost(x: Float, dt: Float): Float {
        if (cfg.boostCutoffHz != hpBuiltCutoff) {
            hp = HighPass(cfg.boostCutoffHz)
            hpBuiltCutoff = cfg.boostCutoffHz
        }
        return cfg.boostGain * hp.update(x, dt)
    }

    fun reset() {
        hp.reset(0f)
    }
}
