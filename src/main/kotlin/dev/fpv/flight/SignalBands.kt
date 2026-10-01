/*
 * FPV Craft - MIT
 *
 * Discrete signal-interference bands (data-driven). LQ -> badness -> a finite chain, with
 * hysteresis + minimum dwell so the chain never flickers on/off near a threshold. CLEAN maps
 * to null (= clearPostEffect). This is pure JVM logic, headless-tested; the mixin maps the
 * returned chain id to a baked post-chain Identifier.
 *
 * Thresholds sit on badness (=1-LQ); escalating / de-escalating edges differ by a deadband.
 */
package dev.fpv.flight

object SignalBands {

    enum class Band { CLEAN, MILD, HEAVY, FROZEN }

    // Escalating (badness rising) and de-escalating (falling) edges, by badness.
    const val UP_MILD = 0.10
    const val DN_MILD = 0.06
    const val UP_HEAVY = 0.25
    const val DN_HEAVY = 0.18
    const val UP_FROZEN = 0.40
    const val DN_FROZEN = 0.30
    /** Min ticks a band must be held before it may change (anti-flicker). */
    const val MIN_DWELL_TICKS = 10

    /** Baked post-chain resource name per band; null for CLEAN (= clearPostEffect). */
    fun chainId(b: Band): String? = when (b) {
        Band.CLEAN -> null
        Band.MILD -> "fpv_sig_mild"
        Band.HEAVY -> "fpv_sig_heavy"
        Band.FROZEN -> "fpv_sig_frozen"
    }

    /** Raw band from badness (no hysteresis) — for reference / tests. */
    fun rawBand(badness: Double): Band = when {
        badness >= UP_FROZEN -> Band.FROZEN
        badness >= UP_HEAVY -> Band.HEAVY
        badness >= UP_MILD -> Band.MILD
        else -> Band.CLEAN
    }

    /**
     * Stateful band decision with hysteresis + dwell.
     * @param badness current badness 0..1
     * @param prev current band
     * @param ticksInBand ticks already spent in [prev]; blocks switching below MIN_DWELL_TICKS
     */
    fun decide(badness: Double, prev: Band, ticksInBand: Int): Band {
        val target = when (prev) {
            Band.CLEAN -> if (badness > UP_MILD) Band.MILD else Band.CLEAN
            Band.MILD -> when {
                badness > UP_HEAVY -> Band.HEAVY
                badness < DN_MILD -> Band.CLEAN
                else -> Band.MILD
            }
            Band.HEAVY -> when {
                badness > UP_FROZEN -> Band.FROZEN
                badness < DN_HEAVY -> Band.MILD
                else -> Band.HEAVY
            }
            Band.FROZEN -> if (badness < DN_FROZEN) Band.HEAVY else Band.FROZEN
        }
        return if (target != prev && ticksInBand < MIN_DWELL_TICKS) prev else target
    }

    /** Convenience: chain id to load this frame given band state; null means clear. */
    fun selectChain(badness: Double, prev: Band, ticksInBand: Int): Pair<Band, String?> {
        val b = decide(badness, prev, ticksInBand)
        return b to chainId(b)
    }
}
