/*
 * FPV Craft - MIT
 * Physical stick positions on the transmitter.
 *
 * A raw HID axis belongs to a physical stick position, never to a logical
 * channel: hand modes only remap which logical channel reads each slot, while
 * the raw-axis calibration attached to a slot stays fixed for the device.
 * This is why calibration is stored per StickSlot and is never overwritten
 * when the hand mode changes.
 */
package dev.fpv.input

enum class StickSlot(
    /** Which physical gimbal. */
    val side: Side,
    /** Which gimbal dimension. */
    val dimension: Dimension,
) {
    /** Left stick, horizontal. */
    LH(Side.LEFT, Dimension.HORIZONTAL),

    /** Left stick, vertical. */
    LV(Side.LEFT, Dimension.VERTICAL),

    /** Right stick, horizontal. */
    RH(Side.RIGHT, Dimension.HORIZONTAL),

    /** Right stick, vertical. */
    RV(Side.RIGHT, Dimension.VERTICAL);

    enum class Side { LEFT, RIGHT }
    enum class Dimension { HORIZONTAL, VERTICAL }
}
