/*
 * FPV Craft - MIT
 * Data-driven replay: the on-disk pose/telemetry container and the
 * interpolation engine.
 *
 * .FPR file format (FPV Replay), clean-room, NOT compatible with ReplayMod's
 * .mcpr (GPL). We record 6-DOF attitude + IMU-derived telemetry only -- no
 * network packets, no blocks, no entities -- so a replay reconstructs the
 * terrain (already loaded in the client) plus the recorded camera trajectory.
 * Other players/entities/dynamic world state are NOT restored (documented
 * limitation; contrast with ReplayMod which re-injects S2C packets).
 *
 * Binary layout (big-endian, DataOutputStream/DataInputStream):
 *
 *   HEADER (fixed, see HEADER_BYTES):
 *     magic      4 bytes  'F','P','V','R'
 *     version    int32    = 1
 *     sampleRate float32  Hz the session was nominally recorded at
 *     auxSlots   int32    fixed record aux width (AUX_SLOTS)
 *     startIso   32 bytes ASCII, NUL-padded UTC-ish local timestamp
 *     reserved   56 bytes zero
 *
 *   RECORD x N (fixed size RECORD_BYTES each, so random access works):
 *     tSec       float64  session time, seconds
 *     x,y,z      float64  player feet position, world blocks
 *     qx,qy,qz,qw float32 world-space attitude quaternion (same basis as the
 *                         live camera override; full roll included)
 *     vx,vy,vz   float32  player velocity, blocks/tick (vanilla deltaMovement)
 *     gx,gy,gz   float32  gyro/body rates, dps, order [pitch, roll, yaw]
 *     ax,ay,az   float32  world-frame acceleration, blocks/s^2 (finite diff)
 *     vbat,mAh   float32  virtual battery
 *     lq         float32  virtual link quality percent
 *     roll,pitch,yaw,thr float32 commanded RC channels, normalized
 *     armed      int32    0/1
 *     modeCode   int32    ordinal of the flight mode string
 *     aux[32]    float32  raw AUX axis snapshot (zero-padded)
 */
package dev.fpv.replay

import org.joml.Quaternionf
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Immutable, already-interpolated pose handed to the camera override + exporter. */
class ReplaySample(
    val tSec: Double,
    val x: Double, val y: Double, val z: Double,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
    val vx: Float, val vy: Float, val vz: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val ax: Float, val ay: Float, val az: Float,
    val vbat: Float, val mAh: Float, val lq: Float,
    val rollCmd: Float, val pitchCmd: Float, val yawCmd: Float, val thrCmd: Float,
    val armed: Boolean,
    val modeCode: Int,
    val aux: FloatArray,
) {
    fun attitude(out: Quaternionf): Quaternionf = out.set(qx, qy, qz, qw).normalize()

    companion object {
        const val AUX_SLOTS = 32
        const val HEADER_BYTES = 4 + 4 + 4 + 4 + 32 + 56
        const val RECORD_BYTES =
            8 * 4 +   // tSec, x, y, z
            4 * 4 +   // quaternion
            4 * 3 +   // velocity
            4 * 3 +   // gyro
            4 * 3 +   // accel
            4 * 2 +   // vbat, mAh
            4 +       // lq
            4 * 4 +   // rc cmd
            4 + 4 +   // armed, modeCode
            4 * AUX_SLOTS
    }
}

/** A fully-loaded replay: flat arrays + binary-search interpolation. */
class ReplayFile(
    val path: Path,
    val sampleRateHz: Float,
    val startIso: String,
    val count: Int,
    val t: DoubleArray,
    val px: DoubleArray, val py: DoubleArray, val pz: DoubleArray,
    val qx: FloatArray, val qy: FloatArray, val qz: FloatArray, val qw: FloatArray,
    val vx: FloatArray, val vy: FloatArray, val vz: FloatArray,
    val gx: FloatArray, val gy: FloatArray, val gz: FloatArray,
    val ax: FloatArray, val ay: FloatArray, val az: FloatArray,
    val vbat: FloatArray, val mAh: FloatArray, val lq: FloatArray,
    val rcRoll: FloatArray, val rcPitch: FloatArray, val rcYaw: FloatArray, val rcThr: FloatArray,
    val armed: BooleanArray,
    val modeCode: IntArray,
    val aux: Array<FloatArray>,
) {
    val durationSec: Double get() = if (count == 0) 0.0 else t[count - 1] - t[0]

    /**
     * Interpolate a pose at [atSec] (session time). Position/velocity/scalars
     * linear; attitude quaternion slerped (shortest arc). Clamps outside range.
     */
    fun sampleAt(atSec: Double, out: ReplaySample): ReplaySample {
        if (count == 0) return out
        if (atSec <= t[0]) return emit(0, out)
        if (atSec >= t[count - 1]) return emit(count - 1, out)
        // Binary search for i with t[i] <= atSec < t[i+1].
        var lo = 0
        var hi = count - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= atSec) lo = mid else hi = mid
        }
        val span = (t[hi] - t[lo]).coerceAtLeast(1e-9)
        val u = ((atSec - t[lo]) / span).toFloat().coerceIn(0f, 1f)
        return emitLerp(lo, hi, u, atSec, out)
    }

    private fun emit(i: Int, out: ReplaySample): ReplaySample =
        emitLerp(i, i, 0f, t[i], out)

    private fun emitLerp(
        i0: Int, i1: Int, u: Float, atSec: Double, out: ReplaySample,
    ): ReplaySample {
        val q0 = Quaternionf(qx[i0], qy[i0], qz[i0], qw[i0])
        val q1 = Quaternionf(qx[i1], qy[i1], qz[i1], qw[i1])
        // Shortest-arc slerp (JOML slerp already picks the minor arc).
        val q = q0.slerp(q1, u)
        fun fl(a: FloatArray, b: FloatArray) = a[i0] + (a[i1] - a[i0]) * u
        fun db(a: DoubleArray, b: DoubleArray) = a[i0] + (a[i1] - a[i0]) * u
        val auxOut = if (i0 == i1) aux[i0].copyOf() else {
            val n = aux[i0].size
            FloatArray(n) { k -> aux[i0][k] + (aux[i1][k] - aux[i0][k]) * u }
        }
        // Reuse the holder object; callers must not retain across frames.
        return ReplaySample(
            tSec = atSec,
            x = db(px, px), y = db(py, py), z = db(pz, pz),
            qx = q.x, qy = q.y, qz = q.z, qw = q.w,
            vx = fl(vx, vx), vy = fl(vy, vy), vz = fl(vz, vz),
            gx = fl(gx, gx), gy = fl(gy, gy), gz = fl(gz, gz),
            ax = fl(ax, ax), ay = fl(ay, ay), az = fl(az, az),
            vbat = fl(vbat, vbat), mAh = fl(mAh, mAh), lq = fl(lq, lq),
            rollCmd = fl(rcRoll, rcRoll), pitchCmd = fl(rcPitch, rcPitch),
            yawCmd = fl(rcYaw, rcYaw), thrCmd = fl(rcThr, rcThr),
            armed = if (u < 0.5f) armed[i0] else armed[i1],
            modeCode = if (u < 0.5f) modeCode[i0] else modeCode[i1],
            aux = auxOut,
        ).also { /* out replaced; see note below */ }
    }

    /** Read a .fpr file. Throws on magic/version mismatch. */
    companion object {
        fun read(path: Path): ReplayFile {
            DataInputStream(Files.newInputStream(path).buffered()).use { d ->
                val magic = ByteArray(4)
                d.readFully(magic)
                val m = String(magic, Charsets.US_ASCII)
                check(m == "FPVR") { "Not an .fpr file: $path (magic=$m)" }
                val version = d.readInt()
                check(version == 1) { "Unsupported .fpr version $version" }
                val rate = d.readFloat()
                val auxSlots = d.readInt()
                check(auxSlots == ReplaySample.AUX_SLOTS) { "aux slots mismatch" }
                val iso = ByteArray(32); d.readFully(iso)
                d.skipBytes(56) // reserved
                val len = Files.size(path) - ReplaySample.HEADER_BYTES
                val n = (len / ReplaySample.RECORD_BYTES).toInt()
                val t = DoubleArray(n)
                val px = DoubleArray(n); val py = DoubleArray(n); val pz = DoubleArray(n)
                val qx = FloatArray(n); val qy = FloatArray(n); val qz = FloatArray(n); val qw = FloatArray(n)
                val vx = FloatArray(n); val vy = FloatArray(n); val vz = FloatArray(n)
                val gx = FloatArray(n); val gy = FloatArray(n); val gz = FloatArray(n)
                val ax = FloatArray(n); val ay = FloatArray(n); val az = FloatArray(n)
                val vbat = FloatArray(n); val mAh = FloatArray(n); val lq = FloatArray(n)
                val rcRoll = FloatArray(n); val rcPitch = FloatArray(n)
                val rcYaw = FloatArray(n); val rcThr = FloatArray(n)
                val armed = BooleanArray(n); val modeCode = IntArray(n)
                val aux = Array(n) { FloatArray(ReplaySample.AUX_SLOTS) }
                for (i in 0 until n) {
                    t[i] = d.readDouble()
                    px[i] = d.readDouble(); py[i] = d.readDouble(); pz[i] = d.readDouble()
                    qx[i] = d.readFloat(); qy[i] = d.readFloat(); qz[i] = d.readFloat(); qw[i] = d.readFloat()
                    vx[i] = d.readFloat(); vy[i] = d.readFloat(); vz[i] = d.readFloat()
                    gx[i] = d.readFloat(); gy[i] = d.readFloat(); gz[i] = d.readFloat()
                    ax[i] = d.readFloat(); ay[i] = d.readFloat(); az[i] = d.readFloat()
                    vbat[i] = d.readFloat(); mAh[i] = d.readFloat(); lq[i] = d.readFloat()
                    rcRoll[i] = d.readFloat(); rcPitch[i] = d.readFloat()
                    rcYaw[i] = d.readFloat(); rcThr[i] = d.readFloat()
                    armed[i] = d.readInt() != 0
                    modeCode[i] = d.readInt()
                    for (k in aux[i].indices) aux[i][k] = d.readFloat()
                }
                return ReplayFile(
                    path, rate, String(iso, Charsets.US_ASCII).trimEnd('\u0000'),
                    n, t, px, py, pz, qx, qy, qz, qw, vx, vy, vz,
                    gx, gy, gz, ax, ay, az, vbat, mAh, lq,
                    rcRoll, rcPitch, rcYaw, rcThr, armed, modeCode, aux,
                )
            }
        }

        /** Write a complete .fpr from an in-memory record list. */
        fun write(
            path: Path,
            sampleRateHz: Float,
            startIso: String,
            records: List<Rec>,
        ) {
            Files.createDirectories(path.parent)
            DataOutputStream(Files.newOutputStream(path).buffered()).use { d ->
                d.writeBytes("FPVR")
                d.writeInt(1)
                d.writeFloat(sampleRateHz)
                d.writeInt(ReplaySample.AUX_SLOTS)
                val iso = ByteArray(32)
                startIso.take(32).encodeToByteArray().copyInto(iso)
                d.write(iso)
                d.write(ByteArray(56))
                for (r in records) {
                    d.writeDouble(r.tSec)
                    d.writeDouble(r.x); d.writeDouble(r.y); d.writeDouble(r.z)
                    d.writeFloat(r.qx); d.writeFloat(r.qy); d.writeFloat(r.qz); d.writeFloat(r.qw)
                    d.writeFloat(r.vx); d.writeFloat(r.vy); d.writeFloat(r.vz)
                    d.writeFloat(r.gx); d.writeFloat(r.gy); d.writeFloat(r.gz)
                    d.writeFloat(r.ax); d.writeFloat(r.ay); d.writeFloat(r.az)
                    d.writeFloat(r.vbat); d.writeFloat(r.mAh); d.writeFloat(r.lq)
                    d.writeFloat(r.rollCmd); d.writeFloat(r.pitchCmd)
                    d.writeFloat(r.yawCmd); d.writeFloat(r.thrCmd)
                    d.writeInt(if (r.armed) 1 else 0)
                    d.writeInt(r.modeCode)
                    val a = r.aux
                    for (k in 0 until ReplaySample.AUX_SLOTS) d.writeFloat(a.getOrElse(k) { 0f })
                }
            }
        }
    }

    /** Mutable row accumulated by the recorder while flying. */
    class Rec {
        var tSec = 0.0; var x = 0.0; var y = 0.0; var z = 0.0
        var qx = 0f; var qy = 0f; var qz = 0f; var qw = 1f
        var vx = 0f; var vy = 0f; var vz = 0f
        var gx = 0f; var gy = 0f; var gz = 0f
        var ax = 0f; var ay = 0f; var az = 0f
        var vbat = 0f; var mAh = 0f; var lq = 0f
        var rollCmd = 0f; var pitchCmd = 0f; var yawCmd = 0f; var thrCmd = 0f
        var armed = false; var modeCode = 0
        var aux: FloatArray = FloatArray(ReplaySample.AUX_SLOTS)
    }
}
