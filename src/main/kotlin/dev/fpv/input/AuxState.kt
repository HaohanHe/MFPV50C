/*
 * FPV Craft - MIT
 * Per-frame evaluated state of one logical AUX channel, produced by the input
 * provider and consumed by the GUI / flight features (mode switch, arm,
 * camera, etc).
 */
package dev.fpv.input

data class AuxState(
    /** Display name (suggested EdgeTX label, or a user rename). */
    val name: String,
    /** Normalized value: -1..1 for AXIS, 0/1 for a single button. */
    val value: Float,
    /**
     * Discrete switch position 0..positionCount-1 for a grouped BUTTONS switch;
     * -1 when AXIS kind or no button currently pressed.
     */
    val position: Int,
    /** Number of detected positions (0 for a continuous AXIS). */
    val positionCount: Int,
    /** "AXIS" or "BUTTONS". */
    val kind: String,
    /** Human-readable source descriptor, e.g. "Axis 3" / "Btns 5,6,7". */
    val source: String,
    /** True when endpoints are measured rather than assumed [-1,1]. */
    val calibrated: Boolean,
)
