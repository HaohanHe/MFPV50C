/*
 * FPV Craft - MIT
 * Logical control channels shared between the input layer and the flight core.
 */
package dev.fpv.input

/**
 * Normalized, hand-mode-independent control channels produced after calibration.
 *
 * Sign convention (stick convention):
 *  - roll:     -1..1, positive = right
 *  - pitch:    -1..1, positive = push forward / nose down
 *  - yaw:      -1..1, positive = right
 *  - throttle:  0..1 in normal mode; -1..1 with 0 at center in reversible-3D mode
 *  - aux:      switch channels, -1..1
 */
data class StickChannels(
    @JvmField var roll: Float = 0f,
    @JvmField var pitch: Float = 0f,
    @JvmField var yaw: Float = 0f,
    @JvmField var throttle: Float = 0f,
    @JvmField var aux: FloatArray = FloatArray(0),
    @JvmField var present: Boolean = false,
    @JvmField var sourceName: String = "",
) {
    companion object {
        /** Logical channel indices. */
        const val ROLL = 0
        const val PITCH = 1
        const val YAW = 2
        const val THROTTLE = 3
    }
}
