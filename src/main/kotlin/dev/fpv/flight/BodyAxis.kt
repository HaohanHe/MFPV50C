/*
 * FPV Craft - MIT
 * Single source of truth mapping a logical rotational axis to its body-frame
 * axis unit vector. Attitude integration (FlightController) and one-frame rate
 * measurement (AttitudeMath) both read this table, so their axis signs can
 * never drift apart.
 *
 * Body frame convention: right = +X, up = +Y, forward = -Z.
 * Logical output order is [pitch, roll, yaw] (see [index]).
 */
package dev.fpv.flight

import org.joml.Vector3f

enum class BodyAxis(
    /** Output array index: pitch=0, roll=1, yaw=2. */
    val index: Int,
    /** Body-frame unit axis for a positive command. */
    val axis: Vector3f,
) {
    /** Pitch positive = push stick forward = nose down, rotation about +X. */
    PITCH(0, Vector3f(1f, 0f, 0f)),

    /** Roll positive = right, rotation about -Z. */
    ROLL(1, Vector3f(0f, 0f, -1f)),

    /** Yaw positive = nose right, rotation about -Y. */
    YAW(2, Vector3f(0f, -1f, 0f));

    /** Project a body-frame axis-angle vector onto this axis. */
    fun dot(v: FloatArray): Float =
        axis.x * v[0] + axis.y * v[1] + axis.z * v[2]
}
