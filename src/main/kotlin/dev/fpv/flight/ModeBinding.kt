/*
 * FPV Craft - MIT
 * One persisted routing row: a channel reference plus the function it drives and
 * the normalized position range in which it counts as active. Rows are plain
 * data; nothing about a channel name, index or function is hard-coded in the
 * evaluation logic.
 *
 * Source kinds:
 *  - AUX:      a named logical aux channel (StickChannels.auxChannels), e.g. "SF".
 *  - RAW_AXIS: a raw HID axis by index (StickChannels.aux, un-gimbal axes).
 *  - BUTTON:   a raw HID button by index (StickChannels.rawButtons).
 *  - HAT:      a raw HAT by index + a direction bit mask (StickChannels.rawHats).
 *
 * Active range is in normalized [-1,1] for analog/named channels: a 2-position
 * switch active-high uses [0.5, 1]; a 3-position switch can bind its middle band
 * (e.g. [-0.5, 0.5]) or high band. BUTTON/HAT use [0.5,1] when engaged.
 */
package dev.fpv.flight

data class ModeBinding(
    /** Driven function id (FlightFunction.id). */
    var function: String = FlightFunction.ARM.id,

    /** Source kind: "AUX", "RAW_AXIS", "BUTTON" or "HAT". */
    var sourceKind: String = "AUX",

    /** AUX channel name (sourceKind = AUX). */
    var sourceName: String = "",

    /** Raw axis / button / hat index (sourceKind != AUX). */
    var sourceIndex: Int = -1,

    /** HAT direction bit mask (GLFW_HAT_*); 0 = any non-centered. */
    var hatDirection: Int = 0,

    /** Inclusive normalized lower bound of the active range. */
    var activeLow: Float = Defaults.SWITCH_ACTIVE_HIGH_LOW,

    /** Inclusive normalized upper bound of the active range. */
    var activeHigh: Float = 1f,

    /** Invert the active result after the range test. */
    var negated: Boolean = false,
)
