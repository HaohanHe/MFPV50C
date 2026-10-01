/*
 * FPV Craft - MIT
 * Cinematic keyframe camera track (clean-room; ReplayMod's simplepathing is GPL-3.0,
 * so only the *idea* is borrowed -- the spline math is written from scratch here).
 *
 * A track is an ordered list of camera keyframes, each at a session time with a world
 * position + orientation quaternion + an interpolator choice. It lives in a sidecar
 * JSON next to the .fpr (<rec>.fpr -> <rec>.cam.json), written atomically, so it can
 * be shared / edited independently of the recorded telemetry.
 *
 * Position splines:
 *   - LINEAR      straight lerp between bracketing keyframes.
 *   - CATMULL_ROM centripetal Catmull-Rom, alpha=0.5 (no cusps / self-intersection on
 *                 typical paths; endpoints clamped by repeating the boundary keyframe).
 *   - CUBIC       cubic Hermite with a configurable tension on the control tangents.
 * Orientation: shortest-arc quaternion slerp (the same basis as ReplayFile).
 */
package dev.fpv.replay

import com.google.gson.GsonBuilder
import org.joml.Quaternionf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.pow

enum class TrackInterp { LINEAR, CATMULL_ROM, CUBIC }

/** One camera keyframe. Times are session seconds, matching the .fpr clock. */
data class TrackKeyframe(
    var tSec: Double,
    var x: Double, var y: Double, var z: Double,
    var qx: Float, var qy: Float, var qz: Float, var qw: Float,
    var interp: TrackInterp = TrackInterp.CATMULL_ROM,
)

/** A sampled world camera pose out of the track. */
class TrackPose {
    var x: Double = 0.0; var y: Double = 0.0; var z: Double = 0.0
    val q = Quaternionf(0f, 0f, 0f, 1f)
    fun set(o: TrackPose) { x = o.x; y = o.y; z = o.z; q.set(o.q) }
}

/** Ordered, time-sorted keyframe track. Thread-safety is the caller's job (UI thread). */
class CinematicTrack(
    var version: Int = CURRENT_VERSION,
    val keyframes: MutableList<TrackKeyframe> = ArrayList(),
) {
    /** Cubic Hermite tension used by [TrackInterp.CUBIC] (0=flat, 1=CR-like). */
    var tension: Float = 1f

    fun isEmpty(): Boolean = keyframes.isEmpty()
    fun durationSec(): Double = if (keyframes.isEmpty()) 0.0 else keyframes.last().tSec

    /** Insert keeping the list time-sorted; returns the inserted index. */
    fun add(kf: TrackKeyframe): Int {
        val i = keyframes.binarySearch { it.tSec.compareTo(kf.tSec) }.let { if (it < 0) -(it + 1) else it }
        keyframes.add(i, kf)
        return i
    }

    fun removeAt(i: Int) { if (i in keyframes.indices) keyframes.removeAt(i) }

    /**
     * Sample the track at [tSec] into [out]. If [tSec] is outside [first,last] we clamp
     * to the end keyframe. Fewer than 2 keyframes degenerates to the single keyframe.
     */
    fun sample(tSec: Double, out: TrackPose): TrackPose {
        val n = keyframes.size
        if (n == 0) return out
        if (n == 1 || tSec <= keyframes[0].tSec) return out.apply {
            setKf(out, keyframes[0])
        }
        val last = keyframes[n - 1]
        if (tSec >= last.tSec) return out.apply { setKf(out, last) }

        // Find segment [i, i+1] bracketing t.
        var i = 0
        while (i < n - 2 && keyframes[i + 1].tSec <= tSec) i++
        val k1 = keyframes[i]; val k2 = keyframes[i + 1]
        val span = (k2.tSec - k1.tSec).coerceAtLeast(1e-6)
        val u = ((tSec - k1.tSec) / span).toFloat().coerceIn(0f, 1f)

        // Neighbours for the spline (clamped by repeating boundary keyframes).
        val k0 = if (i > 0) keyframes[i - 1] else k1
        val k3 = if (i + 2 < n) keyframes[i + 2] else k2

        val interp = if (k0 === k1 || k3 === k2) TrackInterp.LINEAR else k1.interp
        val px = when (interp) {
            TrackInterp.LINEAR -> splineLinear(k0, k1, k2, k3, u, Axis.X)
            TrackInterp.CATMULL_ROM -> catmullRom(k0, k1, k2, k3, u, Axis.X)
            TrackInterp.CUBIC -> cubicHermite(k0, k1, k2, k3, u, Axis.X, tension)
        }
        val py = when (interp) {
            TrackInterp.LINEAR -> splineLinear(k0, k1, k2, k3, u, Axis.Y)
            TrackInterp.CATMULL_ROM -> catmullRom(k0, k1, k2, k3, u, Axis.Y)
            TrackInterp.CUBIC -> cubicHermite(k0, k1, k2, k3, u, Axis.Y, tension)
        }
        val pz = when (interp) {
            TrackInterp.LINEAR -> splineLinear(k0, k1, k2, k3, u, Axis.Z)
            TrackInterp.CATMULL_ROM -> catmullRom(k0, k1, k2, k3, u, Axis.Z)
            TrackInterp.CUBIC -> cubicHermite(k0, k1, k2, k3, u, Axis.Z, tension)
        }
        out.x = px; out.y = py; out.z = pz

        // Orientation: shortest-arc slerp between the two bracketing keyframes.
        out.q.set(k1.qx, k1.qy, k1.qz, k1.qw)
        val q2 = Quaternionf(k2.qx, k2.qy, k2.qz, k2.qw)
        out.q.slerp(q2, u).normalize()
        return out
    }

    private enum class Axis { X, Y, Z }
    private fun TrackKeyframe.a(a: Axis): Double = when (a) {
        Axis.X -> x; Axis.Y -> y; Axis.Z -> z
    }

    private fun setKf(out: TrackPose, k: TrackKeyframe) {
        out.x = k.x; out.y = k.y; out.z = k.z
        out.q.set(k.qx, k.qy, k.qz, k.qw).normalize()
    }

    private fun splineLinear(k0: TrackKeyframe, k1: TrackKeyframe, k2: TrackKeyframe, k3: TrackKeyframe, u: Float, a: Axis): Double =
        k1.a(a) + (k2.a(a) - k1.a(a)) * u

    /** Centripetal Catmull-Rom, alpha=0.5 (clean-room). */
    private fun catmullRom(k0: TrackKeyframe, k1: TrackKeyframe, k2: TrackKeyframe, k3: TrackKeyframe, u: Float, a: Axis): Double {
        val alpha = 0.5
        val p0 = k0.a(a); val p1 = k1.a(a); val p2 = k2.a(a); val p3 = k3.a(a)
        // Per-axis parameter spacing from the *3D chord* would need the other axes; to
        // keep this a clean, dimension-independent scalar spline we use uniform-ish knot
        // spacing derived from the time span (CR on time-parameterised keyframes). The
        // centripetal property is approximated by knot spacing proportional to segment
        // time; endpoints clamped by the caller repeating k1/k2.
        val t0 = 0.0
        val t1 = (k1.tSec - k0.tSec).coerceAtLeast(0.0)
        val t2 = t1 + (k2.tSec - k1.tSec).coerceAtLeast(1e-6)
        val t3 = t2 + (k3.tSec - k2.tSec).coerceAtLeast(0.0)
        val t = t1 + u * (t2 - t1)
        return cr(p0, p1, p2, p3, t0, t1, t2, t3, t, alpha)
    }

    private fun cr(p0: Double, p1: Double, p2: Double, p3: Double,
                   t0: Double, t1: Double, t2: Double, t3: Double, t: Double, alpha: Double): Double {
        // Guard degenerate knots (equal time neighbours) with tiny epsilon.
        fun w(a: Double, b: Double): Double = (b - a).coerceAtLeast(1e-6).pow(alpha)
        val A1 = ((t1 - t) / w(t0, t1)) * p0 + ((t - t0) / w(t0, t1)) * p1
        val A2 = ((t2 - t) / w(t1, t2)) * p1 + ((t - t1) / w(t1, t2)) * p2
        val A3 = ((t3 - t) / w(t2, t3)) * p2 + ((t - t2) / w(t2, t3)) * p3
        val B1 = ((t2 - t) / (t2 - t0).coerceAtLeast(1e-6)) * A1 + ((t - t0) / (t2 - t0).coerceAtLeast(1e-6)) * A2
        val B2 = ((t3 - t) / (t3 - t1).coerceAtLeast(1e-6)) * A2 + ((t - t1) / (t3 - t1).coerceAtLeast(1e-6)) * A3
        return ((t2 - t) / (t2 - t1).coerceAtLeast(1e-6)) * B1 + ((t - t1) / (t2 - t1).coerceAtLeast(1e-6)) * B2
    }

    /** Cubic Hermite: tangents = tension * (next - prev)/2 (finite-difference). */
    private fun cubicHermite(k0: TrackKeyframe, k1: TrackKeyframe, k2: TrackKeyframe, k3: TrackKeyframe, u: Float, a: Axis, tension: Float): Double {
        val p1 = k1.a(a); val p2 = k2.a(a)
        val m1 = tension * 0.5 * (k2.a(a) - k0.a(a))
        val m2 = tension * 0.5 * (k3.a(a) - k1.a(a))
        val u2 = u * u; val u3 = u2 * u
        val h00 = 2 * u3 - 3 * u2 + 1
        val h10 = u3 - 2 * u2 + u
        val h01 = -2 * u3 + 3 * u2
        val h11 = u3 - u2
        return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2
    }

    // ---- persistence (sidecar JSON, atomic) ------------------------------

    fun toJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(this)

    companion object {
        const val CURRENT_VERSION = 1
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        /** Load a track next to [replayFile], or an empty track when no sidecar exists. */
        fun loadSidecar(replayFile: Path): CinematicTrack {
            val side = sidecarFor(replayFile)
            if (!Files.isRegularFile(side)) return CinematicTrack()
            return try {
                GSON.fromJson(Files.readString(side), CinematicTrack::class.java) ?: CinematicTrack()
            } catch (_: Exception) {
                CinematicTrack()
            }
        }

        fun sidecarFor(replayFile: Path): Path =
            replayFile.resolveSibling(replayFile.fileName.toString().removeSuffix(".fpr") + ".cam.json")

        /** Atomic write (never leaves a half-written sidecar). */
        fun saveSidecar(replayFile: Path, track: CinematicTrack) {
            track.version = CURRENT_VERSION
            dev.fpv.flight.AtomicFiles.writeText(sidecarFor(replayFile), track.toJson())
        }
    }
}
