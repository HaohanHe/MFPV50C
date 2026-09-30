/*
 * FPV Craft - MIT
 * One complete control-mapping set for one physical USB transmitter, persisted
 * separately per device fingerprint. Every transmitter routes channels
 * differently (mixer, gimbals, sliders, switches, reverse, subtrim), so a raw
 * axis/button index is only meaningful together with the device it came from.
 */
package dev.fpv.input

class DeviceProfile(
    /** Stable device fingerprint (see DeviceProfiles.fingerprintOf). */
    var fingerprint: String = "",

    /** User-editable label, e.g. the EdgeTX model name. */
    var modelName: String = "",

    /** Hand layout 1..4 (only decides which gimbal slot a logical channel uses). */
    var handMode: Int = 2,

    /** The four physical gimbal slots, indexed by StickSlot.ordinal. */
    var gimbal: MutableList<SlotCalib> = StickSlot.entries.map { SlotCalib() }.toMutableList(),

    /** Named aux controls (sliders/dials/knobs and grouped switches). */
    var aux: MutableList<AuxChannel> = mutableListOf(),

    /** Raw HID button index used to arm, -1 = arm by axis or by key. */
    var armButton: Int = -1,
)
