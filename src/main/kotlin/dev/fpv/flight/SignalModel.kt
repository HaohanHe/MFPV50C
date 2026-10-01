/*
 * FPV Craft - MIT
 *
 * Module 2 signal-quality model (clean-room). Maps home distance -> link quality (0..1) and
 * quality -> "badness" (0 clean .. 1 fully corrupted). The GLSL glitch shader takes badness;
 * these thresholds mirror the FPVEffect(MIT) fsh grading (blockCorrupt>=0.25 etc.) so the
 * visual tiers line up with a deterministic, testable input.
 */
package dev.fpv.flight

object SignalModel {

    /** Half-distance at which LQ has fallen to ~50% (1/(1+d/half)). */
    fun linkQuality(homeDistBlocks: Double, halfDistBlocks: Float): Double {
        val d = homeDistBlocks.coerceAtLeast(0.0)
        val h = halfDistBlocks.coerceAtLeast(1f).toDouble()
        return (1.0 / (1.0 + d / h)).coerceIn(0.0, 1.0)
    }

    /** badness = 1 - lq, clamped. */
    fun badness(lq: Double): Double = (1.0 - lq).coerceIn(0.0, 1.0)

    // Tier thresholds (mirror FPVEffect fsh).
    const val BLOCK_CORRUPT_AT = 0.25
    const val FRAME_FREEZE_AT = 0.40

    fun blockCorruptActive(badness: Double): Boolean = badness >= BLOCK_CORRUPT_AT
    fun frameFreezeActive(badness: Double): Boolean = badness >= FRAME_FREEZE_AT

    /** Overall tier label for UI / logging, from clean -> worst. */
    fun tier(badness: Double): String = when {
        badness < 0.10 -> "clean"
        badness < BLOCK_CORRUPT_AT -> "grain"
        badness < FRAME_FREEZE_AT -> "bands"
        badness < 0.75 -> "blocks"
        else -> "dropout"
    }
}
