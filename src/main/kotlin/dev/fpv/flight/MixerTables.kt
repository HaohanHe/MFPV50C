/*
 * FPV Craft - MIT
 * Quad-X motor mixer table as read-only data. These are the published sign
 * coefficients of a standard Quad-X; this module only stores them with a comment
 * and does NOT wire them into runtime motor output (that differential mixing /
 * PID output is a later phase). It exists so the future mixer has a single,
 * traceable numeric source instead of magic numbers scattered in code.
 *
 * Layout, motor order [M1..M4]:
 *   M1 = REAR_R, M2 = FRONT_R, M3 = REAR_L, M4 = FRONT_L
 * Column order:              [throttle, roll, pitch, yaw]
 */
package dev.fpv.flight

object MixerTables {

    /** Human-readable motor labels aligned with [QUAD_X]. */
    val MOTOR_NAMES = arrayOf("REAR_R(M1)", "FRONT_R(M2)", "REAR_L(M3)", "FRONT_L(M4)")

    /**
     * Quad-X mixing coefficients, one row per motor.
     *
     *   M1 REAR_R:  thr=+1, roll=-1, pitch=+1, yaw=-1
     *   M2 FRONT_R: thr=+1, roll=-1, pitch=-1, yaw=+1
     *   M3 REAR_L:  thr=+1, roll=+1, pitch=+1, yaw=+1
     *   M4 FRONT_L: thr=+1, roll=+1, pitch=-1, yaw=-1
     */
    val QUAD_X: Array<FloatArray> = arrayOf(
        floatArrayOf(+1f, -1f, +1f, -1f), // M1 REAR_R
        floatArrayOf(+1f, -1f, -1f, +1f), // M2 FRONT_R
        floatArrayOf(+1f, +1f, +1f, +1f), // M3 REAR_L
        floatArrayOf(+1f, +1f, -1f, -1f), // M4 FRONT_L
    )

    /** Column index into each motor row. */
    const val THR = 0
    const val ROLL = 1
    const val PITCH = 2
    const val YAW = 3

    /** Scaling constant from the published mixer (PID->motor normalization). */
    const val PID_MIXER_SCALING = 1000f

    fun motorCount(): Int = QUAD_X.size
}
