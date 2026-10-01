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

    private val records = ArrayDeque<ReplayFile.Rec>()
    private val events = ArrayList<ReplayEvent>()
    private var sampleAccumulator = 0f
    private var sessionClock = 0f

    // Previous-sample velocity for the world-frame accel finite difference.
    private var hasPrevVel = false
    private var prevVx = 0f; private var prevVy = 0f; private var prevVz = 0f
    // Session clock of the last written sample (drives the accel window).
    private var prevSampleClock = 0.0

    // Edge-detected event state.
    private var prevArmed = false
    private var prevModeCode = -1
    private var prevGate = -1

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

    /** Begin a new recording. No-op if already recording or recording disabled. */
    fun start(): Boolean {
        if (recording) return false
        if (!(FpvClient.config.replay ?: ReplayConfig()).recordingEnabled) return false
        records.clear()
        events.clear()
        sampleAccumulator = 0f
        sessionClock = 0f
        recordedSec = 0f
        sampleCount = 0
        hasPrevVel = false
        prevSampleClock = 0.0
        prevArmed = FpvClient.armed
        prevModeCode = FpvClient.flight.currentMode.ordinal
        prevGate = -1
        recording = true
        return true
    }

    /** Stop and flush to disk atomically. Returns the saved file, or null when empty. */
    fun stop(): Path? {
        if (!recording) return null
        recording = false
        lastSaved = null
        if (records.isEmpty()) return null
        val dir = replaysDir()
        val name = "rec_" + LocalDateTime.now().format(stampFmt) + ".fpr"
        val out = dir.resolve(name)
        val cfg = FpvClient.config.replay ?: ReplayConfig()
        ReplayFile.write(out, cfg.sampleRateHz, LocalDateTime.now().toString(), records.toList(), events.toList())
        lastSaved = out
        records.clear()
        events.clear()
        return out
    }

    /** Hard abort without saving (e.g. world unload mid-recording). */
    fun abort() {
        recording = false
        records.clear()
        events.clear()
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
        // World-frame accel = d(velocity)/d(inter-sample time). In FIXED mode
        // samples are decimated, so we MUST divide by the real time between the
        // last written sample and this one (sessionClock delta), NOT by the
        // render frame dt -- otherwise accel is scaled down by the decimation
        // factor. In FRAME mode the two are equal.
        val sampleDt = (sessionClock - prevSampleClock).toFloat()
        val ax: Float; val ay: Float; val az: Float
        if (hasPrevVel && sampleDt > 1e-4f) {
            // deltaMovement is blocks/tick; convert to blocks/s (*20) then diff.
            val inv = (1f / sampleDt).coerceIn(0f, 240f)
            ax = ((v.x * 20.0 - prevVx * 20.0) * inv).toFloat()
            ay = ((v.y * 20.0 - prevVy * 20.0) * inv).toFloat()
            az = ((v.z * 20.0 - prevVz * 20.0) * inv).toFloat()
        } else {
            ax = 0f; ay = 0f; az = 0f
        }
        prevVx = v.x.toFloat(); prevVy = v.y.toFloat(); prevVz = v.z.toFloat()
        prevSampleClock = sessionClock.toDouble()
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
        for (k in 0 until ReplaySample.AUX_SLOTS) rec.aux[k] = ReplayFile.cleanFloat(auxSrc.getOrElse(k) { 0f })
        // v2: four mixer outputs + camera + subframe phase.
        val mn = FpvClient.flight.readMotorNorm()
        for (k in 0 until ReplaySample.MOTOR_SLOTS) rec.motor[k] = ReplayFile.cleanFloat(mn.getOrElse(k) { 0f })
        rec.camTiltDeg = ReplayFile.cleanFloat(FpvClient.config.activeAirframe().cameraTiltDeg)
        rec.camFovDeg = ReplaySample.DEFAULT_FOV_DEG
        rec.phase = (sampleAccumulator / (1f / cfg.sampleRateHz.coerceIn(25f, 240f)))

        // NaN/Inf defence: never write a non-finite value into the ring.
        rec.qx = ReplayFile.cleanFloat(rec.qx); rec.qy = ReplayFile.cleanFloat(rec.qy); rec.qz = ReplayFile.cleanFloat(rec.qz); rec.qw = ReplayFile.cleanFloat(rec.qw)
        rec.vx = ReplayFile.cleanFloat(rec.vx); rec.vy = ReplayFile.cleanFloat(rec.vy); rec.vz = ReplayFile.cleanFloat(rec.vz)
        rec.gx = ReplayFile.cleanFloat(rec.gx); rec.gy = ReplayFile.cleanFloat(rec.gy); rec.gz = ReplayFile.cleanFloat(rec.gz)
        rec.ax = ReplayFile.cleanFloat(rec.ax); rec.ay = ReplayFile.cleanFloat(rec.ay); rec.az = ReplayFile.cleanFloat(rec.az)

        // Discrete events (edge-detected, independent of the sample decimation).
        if (FpvClient.armed != prevArmed) events.add(ReplayEvent(sessionClock.toDouble(),
            if (FpvClient.armed) ReplayEvent.ARM else ReplayEvent.DISARM, 0))
        prevArmed = FpvClient.armed
        if (rec.modeCode != prevModeCode) events.add(ReplayEvent(sessionClock.toDouble(), ReplayEvent.MODE, rec.modeCode))
        prevModeCode = rec.modeCode
        val gate = dev.fpv.race.RaceManager.lastCrossGateIndex()
        if (gate >= 0 && gate != prevGate) events.add(ReplayEvent(sessionClock.toDouble(), ReplayEvent.GATE, gate))
        if (gate >= 0) prevGate = gate

        // Ring buffer: bound memory by dropping the oldest samples on long runs.
        records.addLast(rec)
        val cap = cfg.maxSamples.coerceAtLeast(256)
        while (records.size > cap) records.removeFirst()
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
