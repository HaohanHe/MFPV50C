/*
 * FPV Craft - MIT
 * Published transmitter hand layouts (public common knowledge for R/C
 * transmitters). A layout only states which physical stick position each
 * logical channel lives on; it never specifies a raw HID axis index, which
 * is device-specific and can only be learned by calibration.
 *
 * Slot positive directions are: horizontal right = +, vertical up (away
 * from the pilot) = +. These already match the logical sign convention
 * (roll right +, pitch pushed forward / nose down +, yaw right +, throttle
 * up +), so every mapping has sign +; per-device axis inversion is handled
 * by SlotCalib.reversed during calibration.
 */
package dev.fpv.input

object HandLayout {

    /**
     * Physical slot that logical channel [channel] (StickChannels.*) reads
     * under hand [mode] (1..4).
     */
    fun slot(mode: Int, channel: Int): StickSlot =
        TABLE[(mode - 1).coerceIn(0, TABLE.lastIndex)][channel]

    /**
     * Reverse lookup: which logical channel (StickChannels.ROLL/PITCH/YAW/
     * THROTTLE) currently lives on physical [slot] under hand [mode], or -1 if
     * the slot has no logical channel. The channel monitor uses this so its
     * gimbal labels follow the active hand mode instead of being hard-coded to
     * Mode 2.
     */
    @JvmStatic
    fun channelOn(mode: Int, slot: StickSlot): Int {
        val row = TABLE[(mode - 1).coerceIn(0, TABLE.lastIndex)]
        for (ch in StickChannels.ROLL..StickChannels.THROTTLE) {
            if (row[ch] == slot) return ch
        }
        return -1
    }

    /**
     * Display name of the logical channel on physical [slot] under hand [mode],
     * or "?" if unbound. Callers that show gimbal labels must go through this so
     * the label tracks the configured hand mode.
     */
    @JvmStatic
    fun slotLabel(mode: Int, slot: StickSlot): String = when (channelOn(mode, slot)) {
        StickChannels.ROLL -> "Roll"
        StickChannels.PITCH -> "Pitch"
        StickChannels.YAW -> "Yaw"
        StickChannels.THROTTLE -> "Throttle"
        else -> "?"
    }

    // Index order per row: ROLL=0, PITCH=1, YAW=2, THROTTLE=3.
    private val TABLE: Array<Array<StickSlot>> = arrayOf(
        // Mode 1: left = roll(H)/pitch(V), right = yaw(H)/throttle(V)
        arrayOf(StickSlot.LH, StickSlot.LV, StickSlot.RH, StickSlot.RV),
        // Mode 2: left = yaw(H)/throttle(V), right = roll(H)/pitch(V)
        arrayOf(StickSlot.RH, StickSlot.RV, StickSlot.LH, StickSlot.LV),
        // Mode 3: left = roll(H)/throttle(V), right = yaw(H)/pitch(V)
        arrayOf(StickSlot.LH, StickSlot.RV, StickSlot.RH, StickSlot.LV),
        // Mode 4: left = yaw(H)/pitch(V), right = roll(H)/throttle(V)
        arrayOf(StickSlot.RH, StickSlot.LV, StickSlot.LH, StickSlot.RV),
    )
}
