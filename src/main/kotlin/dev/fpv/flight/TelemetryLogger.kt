/*
 * FPV Craft - MIT
 * Blackbox-style telemetry logger (clean-room, NOT a copy of the firmware's
 * binary encoding). When enabled it opens one timestamped JSONL file per session
 * under <gameDir>/fpv-telemetry/ and appends one JSON object per sampled frame.
 *
 * Logged fields (per the agreed field list): time, rcCommand[roll,pitch,yaw,thr],
 * setpointRates[pitch,roll,yaw], bodyRates[pitch,roll,yaw], vbat, mAh, virtual LQ,
 * flightMode, armed.
 */
package dev.fpv.flight

import net.fabricmc.loader.api.FabricLoader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TelemetryLogger {

    private var writer: BufferedWriter? = null
    private var accumulated = 0f

    /** Session metadata stamped into the header (D: incident traceability). */
    private var meta: String = ""

    private val dir: Path
        get() = FabricLoader.getInstance().gameDir.resolve("fpv-telemetry")

    fun isOpen(): Boolean = writer != null

    /**
     * Open a fresh timestamped file for this session, stamping the mod version,
     * config SHA-256 and device fingerprint into the header for post-incident
     * review. No-op if already open.
     */
    fun open(modVersion: String, configHash: String, deviceFingerprint: String) {
        if (writer != null) return
        Files.createDirectories(dir)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val file = dir.resolve("flight_$stamp.jsonl")
        writer = Files.newBufferedWriter(file)
        meta = """{"_meta":{"modVersion":"$modVersion","configHash":"$configHash",""" +
            """"device":"$deviceFingerprint","opened":"$stamp"}}"""
        // Header: metadata + a line documenting the virtual nature of the data.
        writer!!.append(meta).append('\n')
        writer!!.append(
            "{\"_comment\":\"virtual FPV telemetry (no real link/ESC); vbat/LQ are simulated\"}\n"
        )
        writer!!.flush()
    }

    /** Append an immediately-flushed severe-event marker (never silently lost). */
    fun crashMark(reason: String) {
        val w = writer ?: return
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        runCatching {
            w.append("{\"_event\":\"$reason\",\"at\":\"$stamp\"}\n")
            w.flush()
        }
    }

    fun close() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
        accumulated = 0f
        meta = ""
    }

    /**
     * Called every rendered frame; writes only when the fixed sample period has
     * elapsed, so the log rate is render-rate independent.
     */
    fun maybeWrite(dt: Float, sample: TelemetrySample) {
        val w = writer ?: return
        accumulated += dt
        if (accumulated < Defaults.TELEMETRY_PERIOD_S) return
        accumulated = 0f
        w.append(sample.toJson()).append('\n')
    }

    fun flush() { try { writer?.flush() } catch (_: Exception) {} }
}

/** Plain data row assembled by the client each sample. */
data class TelemetrySample(
    val time: Float,
    val rc: FloatArray,        // roll, pitch, yaw, thr
    val setpoint: FloatArray,  // pitch, roll, yaw dps
    val bodyRates: FloatArray, // pitch, roll, yaw dps
    val vbat: Float,
    val mAh: Float,
    val lq: Int,
    val flightMode: String,
    val armed: Boolean,
) {
    fun toJson(): String {
        fun arr(a: FloatArray) = a.joinToString(",", "[", "]") { "%.3f".format(it) }
        return "{\"t\":%.3f,\"rc\":%s,\"sp\":%s,\"gyro\":%s,\"vbat\":%.3f,\"mah\":%.1f," +
            "\"lq\":%d,\"mode\":\"%s\",\"armed\":%b}"
            .format(time, arr(rc), arr(setpoint), arr(bodyRates), vbat, mAh, lq, flightMode, armed)
    }
}
