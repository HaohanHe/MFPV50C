/*
 * FPV Craft - MIT
 * Unified binding + calibration for one physical control (a gimbal axis, a
 * slider/dial, a momentary button, or a multi-position switch).
 *
 * The raw source type and index are device-specific and are written only by
 * the guided move-to-bind wizard - no axis order, button order or channel order
 * is ever assumed. Raw ranges default to the GLFW-documented joystick range
 * [-1, 1]; the wizard replaces them with measured endpoints.
 */
package dev.fpv.input

import dev.fpv.flight.Defaults

data class SlotCalib(
    /** Raw source kind: "AXIS" (analog), "BUTTON" (0/1) or "HAT" (direction bitfield). */
    var type: String = "AXIS",

    /** Raw source index (axis / button / hat index), -1 = not bound. */
    var axisIndex: Int = -1,

    /** Raw direction is flipped when the device reports it inverted. */
    var reversed: Boolean = false,

    /** Measured/assumed raw endpoint and center (AXIS kind). */
    var rawMin: Float = Defaults.RAW_RANGE_MIN,
    var rawMid: Float = Defaults.RAW_RANGE_MID,
    var rawMax: Float = Defaults.RAW_RANGE_MAX,

    /** Center deadband in raw-axis units (AXIS kind). */
    var deadzone: Float = Defaults.CHANNEL_DEADZONE,

    /**
     * For a switch: the observed raw value of each position, in order. Empty
     * means a continuous analog control. For BUTTON/HAT kinds this also marks
     * the binding as discrete.
     */
    var positions: MutableList<Float> = mutableListOf(),

    /**
     * True once the wizard measured real endpoints / positions for this control.
     * A manual pick uses the documented GLFW range and leaves this false.
     */
    var learned: Boolean = false,
) {
    /** True when this control is a discrete switch rather than analog. */
    val isSwitch: Boolean get() = type != "AXIS" || positions.isNotEmpty()
}
