/*
 * FPV Craft - MIT
 * Serializable track document. Saved as JSON under
 * <gameDir>/fpv-tracks/<name>.json and directly shareable.
 *
 * A NEW (empty) track is the minimal template: just a start position, zero gates.
 * Gates are built entirely by the user in the editor; the mod ships no course.
 */
package dev.fpv.race

import net.minecraft.world.phys.Vec3

/** Orientation/position marker used for start and finish lines. */
class PoseMarker {
    var x: Double = 0.0
    var y: Double = 0.0
    var z: Double = 0.0
    var yawDeg: Float = 0f
    var pitchDeg: Float = 0f

    fun pos(): Vec3 = Vec3(x, y, z)
}

/** Landing zone: a horizontal cylinder centred on the marker with [radius] blocks. */
class LandingZone {
    var x: Double = 0.0
    var y: Double = 0.0
    var z: Double = 0.0
    var radius: Double = 4.0

    fun center(): Vec3 = Vec3(x, y, z)
}

/** On-disk track document. Gson-friendly public fields. */
class TrackDoc {
    /** Human-readable track name (also the file name). */
    var name: String = "untitled"

    /** Gate list in lap order. Empty for the minimal new-track template. */
    var gates: MutableList<GateDef> = mutableListOf()

    /** Fallback opening size (blocks = metres) when a gate omits it. */
    var defaultWidth: Float = 1.5f
    var defaultHeight: Float = 1.5f

    /** Start line (position + facing). Minimal template places this at creation. */
    var start: PoseMarker = PoseMarker()

    /** Index of the gate that starts the clock in TIMING_GATE mode; -1 = none. */
    var timingGateIndex: Int = -1

    /** Finish line; defaults to the start plane when unset. */
    var finish: PoseMarker = PoseMarker()

    /** Mandatory return area after completing the required laps. */
    var landingZone: LandingZone = LandingZone()

    /** Best completed round (full required-lap time), ms; 0 = none. */
    var bestRoundMs: Long = 0L

    /** All valid single-lap times recorded across rounds (ms). */
    var validLapsMs: MutableList<Long> = mutableListOf()

    /** Recorded best-lap ghost; empty when none. */
    var ghost: MutableList<GhostSample> = mutableListOf()
}
