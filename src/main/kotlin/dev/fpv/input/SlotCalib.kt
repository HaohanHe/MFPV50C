/*
 * FPV Craft - MIT
 * Raw-axis binding and calibration for one physical stick slot.
 *
 * The axis index is device-specific and is written only by the calibration
 * wizard or explicit user selection - it is never guessed from a "common
 * order". Raw ranges default to the GLFW-documented joystick axis range
 * [-1, 1]; the wizard replaces them with measured min/mid/max.
 */
package dev.fpv.input

import dev.fpv.flight.Defaults

data class SlotCalib(
    /** Raw joystick axis index, -1 = not bound. */
    var axisIndex: Int = -1,

    /** Raw direction is flipped when the device reports the axis inverted. */
    var reversed: Boolean = false,

    /** Measured/assumed raw endpoint and center. */
    var rawMin: Float = Defaults.RAW_RANGE_MIN,
    var rawMid: Float = Defaults.RAW_RANGE_MID,
    var rawMax: Float = Defaults.RAW_RANGE_MAX,

    /** Center deadband in raw-axis units. */
    var deadzone: Float = Defaults.CHANNEL_DEADZONE,

    /**
     * True once the full wizard measured real min/mid/max for this slot.
     * A manual axis pick binds the index using the documented GLFW range but
     * leaves this false until calibrated.
     */
    var learned: Boolean = false,
)
