/*
 * FPV Craft - MIT
 * Data table of transmitters whose channel layout is already known. When such a
 * device is first seen, its DeviceProfile is pre-filled so the pilot does not
 * have to run the move-to-bind wizard. This is plain DATA (no behavior): the
 * arm/flight logic stays generic and reads the resulting bindings.
 *
 * Indices are 0-based HID axis / button indices as reported by GLFW for that
 * specific device. Entries here are device-scoped and never used as a generic
 * axis order. Unknown controls (e.g. S1/S2/LS/RS when their HID landing is not
 * confirmed) are intentionally left unbound rather than guessed.
 */
package dev.fpv.input

import dev.fpv.flight.Defaults

object KnownDevices {

    internal class Known(
        val nameContains: String,
        val handMode: Int,
        val gimbalAxis: Map<StickSlot, Int>,
        val axisAux: LinkedHashMap<String, Int>,
        val buttonAux: LinkedHashMap<String, Int>,
        val armButton: Int,
    )

    private val ALL: List<Known> = listOf(        // EdgeTX "FF F16 Joystick": 8 axes + 24 buttons.
        // Gimbals: Roll axis1(0), Pitch axis2(1), Thr axis3(2), Yaw axis4(3).
        // Axis-form switches: SA axis5(4), SB axis6(5), SD axis7(6), SC axis8(7).
        // Button-form switches: SE btn1(0), SF btn2(1), SG btn3(2), SH btn4(3).
        Known(
            nameContains = "FF F16 Joystick",
            handMode = 2,
            gimbalAxis = mapOf(
                StickSlot.LH to 3,   // Yaw
                StickSlot.LV to 2,   // Throttle
                StickSlot.RH to 0,   // Roll
                StickSlot.RV to 1,   // Pitch
            ),
            axisAux = linkedMapOf("SA" to 4, "SB" to 5, "SD" to 6, "SC" to 7),
            buttonAux = linkedMapOf("SE" to 0, "SF" to 1, "SG" to 2, "SH" to 3),
            armButton = 1,           // SF = button index 1
        ),
    )

    internal fun match(deviceName: String): Known? =
        ALL.firstOrNull { deviceName.contains(it.nameContains, ignoreCase = true) }

    /** Pre-fill a freshly created [profile] for a known device. */
    internal fun apply(profile: DeviceProfile, known: Known) {
        profile.handMode = known.handMode
        for (slot in StickSlot.entries) {
            val axis = known.gimbalAxis[slot] ?: continue
            profile.gimbal[slot.ordinal] = SlotCalib(
                type = "AXIS",
                axisIndex = axis,
                deadzone = Defaults.CHANNEL_DEADZONE,
                learned = true,
            )
        }
        profile.aux.clear()
        for ((name, axis) in known.axisAux) {
            profile.aux += AuxChannel(name = name, kind = "AXIS", axisIndex = axis, learned = true)
        }
        for ((name, btn) in known.buttonAux) {
            profile.aux += AuxChannel(
                name = name, kind = "BUTTONS", axisIndex = btn,
                buttons = mutableListOf(btn), learned = true,
            )
        }
        profile.armButton = known.armButton
    }
}
