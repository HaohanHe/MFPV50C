/*
 * FPV Craft - MIT
 * One logical AUX channel (everything beyond the 4 AETR gimbals). AUX channels
 * are data-driven: the count is discovered at runtime from the USB HID device
 * and persisted, never hard-coded. Two source kinds exist (per the researched
 * EdgeTX USB joystick modes):
 *   - AXIS:    a continuous raw HID axis (slider / dial / pot, or a switch
 *              exposed as a discrete level). Calibration reuses the same
 *              min/mid/mid/endpoint model as the gimbal slots.
 *   - BUTTONS: one HID button per switch position (EdgeTX "Btn" mode). The
 *              grouped buttons produce a discrete position index 0..n-1.
 */
package dev.fpv.input

data class AuxChannel(
    /** Display label; auto-suggested from EdgeTX convention, user may rename. */
    var name: String = "",

    /** "AXIS" or "BUTTONS". */
    var kind: String = "AXIS",

    /** Raw HID axis index (kind = AXIS). -1 = unbound. */
    var axisIndex: Int = -1,

    /** Raw HID button index per position (kind = BUTTONS). buttons[position]. */
    var buttons: MutableList<Int> = mutableListOf(),

    /** Direction flip. */
    var reversed: Boolean = false,

    /** Measured/assumed raw endpoints and center for AXIS kind. */
    var rawMin: Float = -1f,
    var rawMid: Float = 0f,
    var rawMax: Float = 1f,

    /** Center deadband in raw-axis units (AXIS kind). */
    var deadzone: Float = 0.02f,

    /** True once the measured endpoints come from real samples. */
    var learned: Boolean = false,
) {
    /** Number of switch positions; 1 for a momentary button, 2/3+ for a switch. */
    val positionCount: Int get() = if (kind == "BUTTONS") buttons.size else 0
}
