/*
 * FPV Craft - MIT
 * A recorded best-lap ghost trail sample. Stored in world coordinates so the
 * ghost replays correctly anywhere; attitude quaternion + throttle let the
 * replayed marker show the drone's heading.
 */
package dev.fpv.race

import org.joml.Quaternionf

/** One recorded frame of a flown lap. */
data class GhostSample(
    /** Seconds since lap start. */
    var t: Float,
    var x: Double,
    var y: Double,
    var z: Double,
    /** Attitude quaternion (world rotation, same convention as FlightController.attitude). */
    var qx: Float,
    var qy: Float,
    var qz: Float,
    var qw: Float,
    /** Throttle at the sample (0..1). */
    var throttle: Float,
) {
    fun quaternion(): Quaternionf = Quaternionf(qx, qy, qz, qw)
}
