/*
 * FPV Craft - MIT
 * Camera trajectory exchange: dump the final per-frame camera pose (recorded, or sampled
 * from the cinematic keyframe track when active) to a simple JSON path that Blender /
 * After Effects can import. Frames = fps * duration, each with position + quaternion + fov.
 * Atomic write; no glTF (overkill for the exchange need).
 */
package dev.fpv.replay

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor

object CameraPathExporter {

    data class CamFrame(val t: Double, val x: Double, val y: Double, val z: Double,
                        val qx: Float, val qy: Float, val qz: Float, val qw: Float, val fov: Float)

    data class CameraPath(val fps: Int, val frames: List<CamFrame>)

    /**
     * Build the path for [file] at [fps], using the cinematic track when [track] is
     * non-empty (spline-sampled), else the recorded poses. Writes JSON to [outPath].
     * Returns the frame count written.
     */
    fun export(file: ReplayFile, track: CinematicTrack?, fps: Int, outPath: Path): Int {
        val f = fps.coerceIn(12, 120)
        val total = floor(file.durationSec * f).toInt().coerceAtLeast(1)
        val useTrack = track != null && !track.isEmpty()
        val tr = track
        val rec = ReplaySample(
            0.0, 0.0, 0.0, 0.0,
            0f, 0f, 0f, 1f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f, 0f,
            false, 0,
            FloatArray(ReplaySample.AUX_SLOTS),
        )
        val tp = TrackPose()
        val frames = ArrayList<CamFrame>(total)
        for (i in 0 until total) {
            val t = (i.toDouble() / f).coerceIn(0.0, file.durationSec)
            if (useTrack) {
                tr.sample(t, tp)
                frames.add(CamFrame(t, tp.x, tp.y, tp.z, tp.q.x, tp.q.y, tp.q.z, tp.q.w, ReplaySample.DEFAULT_FOV_DEG))
            } else {
                file.sampleAt(t, rec)
                frames.add(CamFrame(t, rec.x, rec.y, rec.z, rec.qx, rec.qy, rec.qz, rec.qw, ReplaySample.DEFAULT_FOV_DEG))
            }
        }
        val json = GsonBuilder().setPrettyPrinting().create().toJson(CameraPath(f, frames))
        dev.fpv.flight.AtomicFiles.writeText(outPath, json)
        return frames.size
    }
}
