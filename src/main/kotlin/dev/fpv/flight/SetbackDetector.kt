/*
 * FPV Craft - MIT
 *
 * Pure, headless-testable rubber-band (server setback) classifier. A vanilla
 * server corrects the client position all the time; only LARGE absolute snaps
 * are rubber-bands. This helper decides:
 *   - is the correction an absolute teleport (relatives set empty)?
 *   - is the distance above the configured threshold?
 *   - is the cooldown elapsed since the last reaction?
 *
 * No Minecraft types here so it unit-tests on a plain JVM.
 */
package dev.fpv.flight

object SetbackDetector {

    /**
     * @param distanceBlocks absolute 3D distance between client-predicted position
     *        and the server-supplied absolute position, in blocks.
     * @param relativesEmpty true when the server packet carried no relative-axis
     *        flags (i.e. an absolute teleport). Relative deltas are ordinary
     *        corrections and must never count as a setback.
     * @param thresholdBlocks configured trigger distance.
     * @param msSinceLastEvent ms elapsed since the previous accepted reaction
     *        (Long.MAX_VALUE = no prior event).
     * @param cooldownMs debounce window.
     */
    fun isSetback(
        distanceBlocks: Double,
        relativesEmpty: Boolean,
        thresholdBlocks: Float,
        msSinceLastEvent: Long,
        cooldownMs: Long,
    ): Boolean {
        if (!relativesEmpty) return false
        if (!distanceBlocks.isFinite()) return false
        if (distanceBlocks > thresholdBlocks) {
            return msSinceLastEvent >= cooldownMs
        }
        return false
    }
}
