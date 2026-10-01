/*
 * FPV Craft - MIT
 * Stage 2: data-driven playback engine. Loads a .fpr file and drives a virtual
 * camera by interpolating the recorded trajectory (linear on position/velocity/
 * scalars, quaternion slerp on attitude). Freezes live flight control while
 * active. Three views:
 *   FPV   - recorded nose attitude + airframe camera tilt (faithful to the flight)
 *   CHASE - derived third-person camera behind/above the drone
 *   FREE  - hands the camera back to vanilla (parked observer)
 *
 * HONEST LIMITATION: we do not record network packets, so the world is whatever
 * is currently loaded on the client; other entities / dynamic block state are
 * NOT reconstructed (unlike ReplayMod, which re-injects S2C packets).
 */
package dev.fpv.replay

import dev.fpv.client.FpvClient
import dev.fpv.flight.ExportConfig
import dev.fpv.flight.ReplayConfig
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import java.nio.file.Path

enum class ReplayView { FPV, CHASE, FREE }

object ReplayManager {

    var file: ReplayFile? = null
        private set

    @Volatile var playing = false
        private set

    @Volatile var paused = false

    /** Playback speed 0.25x..4x. */
    var speed: Float = 1f
        set(value) { field = value.coerceIn(0.25f, 4f) }

    var view: ReplayView = ReplayView.FPV
        private set

    /** Current playback cursor, seconds. */
    @Volatile var cursorSec: Double = 0.0
        private set

    private val scratch = ReplaySample(
        0.0, 0.0, 0.0, 0.0, 0f, 0f, 0f, 1f,
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f, 0f, 0f, false, 0, FloatArray(ReplaySample.AUX_SLOTS),
    )

    /** Cinematic keyframe track for the loaded replay (empty = follow recorded flight). */
    var track: CinematicTrack = CinematicTrack()
        private set

    /** When true, [cameraTransform] follows the keyframe spline instead of the recorded pose. */
    var trackActive: Boolean = false
        set(value) { field = value && !track.isEmpty() }

    private val trackSample = TrackPose()

    val active: Boolean get() = playing && file != null

    val durationSec: Double get() = file?.durationSec ?: 0.0

    fun load(path: Path): Boolean {
        return try {
            stop()
            file = ReplayFile.read(path)
            track = CinematicTrack.loadSidecar(path)
            trackActive = !track.isEmpty()
            cursorSec = 0.0
            paused = false
            playing = true
            val cfg = FpvClient.config.replay ?: ReplayConfig()
            speed = cfg.lastSpeed
            view = when (cfg.lastView.uppercase()) {
                "CHASE" -> ReplayView.CHASE
                "FREE" -> ReplayView.FREE
                else -> ReplayView.FPV
            }
            true
        } catch (e: Exception) {
            file = null
            playing = false
            false
        }
    }

    fun play() { playing = true; paused = false }
    fun pause() { paused = true }
    fun togglePause() { paused = !paused }

    fun stop() {
        playing = false
        paused = false
        cursorSec = 0.0
        // file is kept so the screen can show metadata; call close() to drop it.
    }

    fun close() {
        stop()
        file = null
    }

    /** Jump to a session time (seconds). */
    fun scrubTo(t: Double) {
        val dur = durationSec
        cursorSec = t.coerceIn(0.0, dur)
    }

    fun seekDelta(dt: Double) = scrubTo(cursorSec + dt)

    fun cycleSpeed() {
        val choices = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
        val i = choices.indexOfFirst { it >= speed }.let { if (it < 0) choices.lastIndex else it }
        speed = choices[(i + 1) % choices.size]
        (FpvClient.config.replay ?: ReplayConfig()).lastSpeed = speed
    }

    fun cycleView() {
        view = ReplayView.entries[(view.ordinal + 1) % ReplayView.entries.size]
        (FpvClient.config.replay ?: ReplayConfig()).lastView = view.name
    }

    /** Advance the cursor by real time * speed. Called from the client frame. */
    fun update(dtReal: Float) {
        val f = file ?: return
        if (!playing || paused) return
        cursorSec += dtReal * speed
        if (cursorSec >= f.durationSec) {
            cursorSec = f.durationSec
            paused = true // hold at the end until the user scrubs.
        }
    }

    /** The interpolated pose the camera mixin (and exporter) should use now. */
    fun currentPose(): ReplaySample {
        val f = file ?: return scratch
        return f.sampleAt(cursorSec, scratch)
    }

    /**
     * Compute the world camera transform for the active view, applied by
     * CameraMixin. Returns null in FREE view (leave the vanilla camera alone).
     */
    fun cameraTransform(eyeHeight: Float): Pair<Vec3, Quaternionf>? {
        val f = file ?: return null
        if (!active || view == ReplayView.FREE) return null
        // Keyframe spline track overrides the recorded camera when active.
        if (trackActive && !track.isEmpty()) {
            val tp = trackSample.apply { track.sample(cursorSec, this) }
            return Vec3(tp.x, tp.y, tp.z) to Quaternionf(tp.q)
        }
        val s = f.sampleAt(cursorSec, scratch)
        val q = s.attitude(Quaternionf())

        val camPos: Vec3 = when (view) {
            ReplayView.FPV -> Vec3(s.x, s.y + eyeHeight, s.z)
            ReplayView.CHASE -> {
                val dist = (FpvClient.config.export ?: ExportConfig()).chaseDistance
                // Camera sits above + behind the nose, looking along the nose.
                val off = Vector3f(0f, 0.6f, -dist).rotate(q)
                Vec3(s.x + off.x, s.y + eyeHeight + off.y, s.z + off.z)
            }
            ReplayView.FREE -> Vec3(s.x, s.y + eyeHeight, s.z)
        }

        if (view == ReplayView.FPV) {
            // Match the live FPV camera: apply the airframe camera tilt as a
            // nose-down pitch about body X.
            val tiltDeg = FpvClient.config.activeAirframe().cameraTiltDeg
            if (tiltDeg != 0f) {
                q.mul(org.joml.Quaternionf().rotateX(
                    (-tiltDeg * Math.PI / 180.0).toFloat()))
            }
        }
        return camPos to q
    }

    /** Called when leaving the world so a recording isn't left dangling. */
    fun onWorldUnload() {
        if (playing) stop()
    }

    fun formatTime(t: Double): String {
        val s = t.toInt()
        return String.format("%d:%02d.%03d", s / 60, s % 60, ((t - s) * 1000).toInt())
    }
}
