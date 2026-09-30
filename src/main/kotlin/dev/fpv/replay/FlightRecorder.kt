/*
 * FPV Craft - MIT
 * Stage 1: high-density flight recorder. Records ONLY data (no screen capture,
 * no packets): 6-DOF attitude (full roll), position, velocity, body rates,
 * derived IMU accel, RC channels, battery and link state. Flushed as a single
 * compact .fpr binary (format documented in ReplayFile.kt).
 *
 * Sampling:
 *  - FIXED (default): decimate to config.replay.sampleRateHz (100-200 Hz).
 *  - FRAME: write every rendered frame (adaptive to the render rate).
 * Recording is armed/disarmed by key, GUI button, or a reserved AUX channel.
 */
package dev.fpv.replay

import dev.fpv.client.FpvClient
import dev.fpv.flight.ReplayConfig
import dev.fpv.input.StickChannels
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import org.joml.Quaternionf
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object FlightRecorder {

    /** Recording state machine surfaced to the OSD / GUI. */
    @Volatile var recording = false
        private set

    /** Seconds recorded (session clock, not wall time). */
    var recordedSec = 0f
        private set

    /** Samples written so far (also the OSD sample counter). */
    var sampleCount = 0
        private set

    /** Most recently saved file, for the "just saved" toast. */
    var lastSaved: Path? = null
        private set

    private val records = ArrayList<ReplayFile.Rec>()
    private var sampleAccumulator = 0f
    private var sessionClock = 0f

    // Previous-sample velocity for the world-frame accel finite difference.
    private var hasPrevVel = false
    private var prevVx = 0f; private var prevVy = 0f; private var prevVz = 0f

    private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun replaysDir(): Path =
        FabricLoader.getInstance().gameDir.resolve("fpv-replays")

    fun listReplays(): List<Path> {
        val dir = replaysDir()
        if (!java.nio.file.Files.isDirectory(dir)) return emptyList()
        return dir.toFile().listFiles { f -> f.extension == "fpr" }
            ?.map { it.toPath() }
            ?.sortedByDescending { it.toFile().lastModified() }
            ?: emptyList()
    }

    /** Begin a new recording. No-op if already recording. */
    fun start(): Boolean {
        if (recording) return false
        records.clear()
        sampleAccumulator = 0f
        sessionClock = 0f
        recordedSec = 0f
        sampleCount = 0
        hasPrevVel = false
        recording = true
        return true
    }

    /** Stop and flush to disk. Returns the saved file, or null when empty. */
    fun stop(): Path? {
        if (!recording) return null
        recording = false
        lastSaved = null
        if (records.isEmpty()) return null
        val dir = replaysDir()
        val name = "rec_" + LocalDateTime.now().format(stampFmt) + ".fpr"
        val out = dir.resolve(name)
        val cfg = FpvClient.config.replay ?: ReplayConfig()
        ReplayFile.write(out, cfg.sampleRateHz, LocalDateTime.now().toString(), records)
        lastSaved = out
        records.clear()
        return out
    }

    /** Hard abort without saving (e.g. world unload mid-recording). */
    fun abort() {
        recording = false
        records.clear()
        sampleCount = 0
        recordedSec = 0f
    }

    /**
     * Feed one rendered frame. Called from FpvClient.onFrame while flying.
     * Respects the GUI-open freeze (we are skipped there) and never records
     * while a replay session is active.
     */
    fun feed(mc: Minecraft, channels: StickChannels, dt: Float) {
        if (!recording) return
        val p: Player = mc.player ?: return
        sessionClock += dt
        recordedSec = sessionClock

        val cfg = FpvClient.config.replay ?: ReplayConfig()
        val fixed = cfg.recordingMode.equals("FIXED", ignoreCase = true)
        if (fixed) {
            sampleAccumulator += dt
            val period = (1f / cfg.sampleRateHz.coerceIn(25f, 240f))
            if (sampleAccumulator < period) return
            sampleAccumulator = 0f
        }

        val q: Quaternionf = FpvClient.flight.attitude
        val v = p.deltaMovement
        val ax: Float; val ay: Float; val az: Float
        if (hasPrevVel) {
            // deltaMovement is blocks/tick; convert to blocks/s and difference.
            val inv = (1f / dt).coerceIn(0f, 240f)
            ax = ((v.x * 20.0 - prevVx * 20.0) * inv).toFloat()
            ay = ((v.y * 20.0 - prevVy * 20.0) * inv).toFloat()
            az = ((v.z * 20.0 - prevVz * 20.0) * inv).toFloat()
        } else {
            ax = 0f; ay = 0f; az = 0f
        }
        prevVx = v.x.toFloat(); prevVy = v.y.toFloat(); prevVz = v.z.toFloat()
        hasPrevVel = true

        val rec = ReplayFile.Rec()
        rec.tSec = sessionClock.toDouble()
        rec.x = p.x; rec.y = p.y; rec.z = p.z
        rec.qx = q.x; rec.qy = q.y; rec.qz = q.z; rec.qw = q.w
        rec.vx = v.x.toFloat(); rec.vy = v.y.toFloat(); rec.vz = v.z.toFloat()
        // bodyRates order is [pitch, roll, yaw]; see FlightController.
        val br = FpvClient.flight.bodyRates
        rec.gx = br[0]; rec.gy = br[1]; rec.gz = br[2]
        rec.ax = ax; rec.ay = ay; rec.az = az
        rec.vbat = FpvClient.battery.vbat
        rec.mAh = FpvClient.battery.mAhDrawn
        rec.lq = FpvClient.link.lq.toFloat()
        rec.rollCmd = channels.roll
        rec.pitchCmd = channels.pitch
        rec.yawCmd = channels.yaw
        rec.thrCmd = FpvClient.throttle
        rec.armed = FpvClient.armed
        rec.modeCode = FpvClient.flight.currentMode.ordinal
        val auxSrc = channels.aux
        for (k in 0 until ReplaySample.AUX_SLOTS) rec.aux[k] = auxSrc.getOrElse(k) { 0f }
        records.add(rec)
        sampleCount = records.size
    }

    /** Rising-edge AUX trigger (called from the client tick when configured). */
    private var recordAuxWasHigh = false
    fun pollAuxTrigger(channels: StickChannels) {
        val idx = (FpvClient.config.replay ?: ReplayConfig()).recordAuxAxis
        if (idx < 0) { recordAuxWasHigh = false; return }
        val high = channels.aux.getOrElse(idx) { 0f } > dev.fpv.flight.Defaults.SWITCH_TRIGGER
        if (high && !recordAuxWasHigh) {
            if (recording) stop() else start()
        }
        recordAuxWasHigh = high
    }
}
