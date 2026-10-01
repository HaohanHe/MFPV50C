/*
 * FPV Craft - MIT
 * Pure, headless-testable racing core: double-precision gate geometry + an injectable-clock
 * timing state machine. No net.minecraft Vec3 / System.nanoTime here — the MC RaceManager
 * wraps these (Vec3 wrapper) and supplies the wall clock. Clean-room: F9U rule values only.
 */
package dev.fpv.race

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Double-precision gate geometry. A gate = plane through (cx,cy,cz) with normal n (direction
 *  you must fly through), aperture = halfW along right axis r, halfH along up axis u. */
object GateGeo {

    /** MC look-vector normal from yaw/pitch in degrees (matches GateDef.forward). */
    fun forward(yawDeg: Float, pitchDeg: Float): DoubleArray {
        val y = Math.toRadians(yawDeg.toDouble())
        val p = Math.toRadians(pitchDeg.toDouble())
        val cp = cos(p)
        val nx = -sin(y) * cp
        val ny = -sin(p)
        val nz = cos(y) * cp
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        return doubleArrayOf(nx / len, ny / len, nz / len)
    }

    private fun cross(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): DoubleArray =
        doubleArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)

    /** Right axis (gate horizontal). */
    fun right(nx: Double, ny: Double, nz: Double): DoubleArray {
        var r = cross(nx, ny, nz, 0.0, 1.0, 0.0)
        val l = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
        if (l < 1e-9) r = doubleArrayOf(1.0, 0.0, 0.0)
        else { r = doubleArrayOf(r[0] / l, r[1] / l, r[2] / l) }
        return r
    }

    /**
     * Segment [a,b] vs gate. Returns:
     *   1 = valid forward crossing inside the aperture
     *  -1 = straddles the plane but flies the WRONG way (dot(segment,n) < 0)
     *   0 = no valid crossing (missed / outside aperture / parallel)
     */
    fun intersect(
        ax: Double, ay: Double, az: Double,
        bx: Double, by: Double, bz: Double,
        cx: Double, cy: Double, cz: Double,
        nx: Double, ny: Double, nz: Double,
        halfW: Double, halfH: Double,
        ring: Boolean,
    ): Int {
        val da = nx * (cx - ax) + ny * (cy - ay) + nz * (cz - az)
        val db = nx * (cx - bx) + ny * (cy - by) + nz * (cz - bz)
        if (da * db >= 0.0) return 0           // never crosses the plane
        val t = da / (da - db)
        if (t < 0.0 || t > 1.0) return 0
        val px = ax + (bx - ax) * t
        val py = ay + (by - ay) * t
        val pz = az + (bz - az) * t
        // Forward-only: the segment must progress along the plane normal.
        val sdx = bx - ax; val sdy = by - ay; val sdz = bz - az
        val dir = sdx * nx + sdy * ny + sdz * nz
        val rx = right(nx, ny, nz)
        // up = right × normal
        val ux = rx[1] * nz - rx[2] * ny
        val uy = rx[2] * nx - rx[0] * nz
        val uz = rx[0] * ny - rx[1] * nx
        val alongR = (px - cx) * rx[0] + (py - cy) * rx[1] + (pz - cz) * rx[2]
        val alongU = (px - cx) * ux + (py - cy) * uy + (pz - cz) * uz
        val inside = if (ring) {
            alongR * alongR + alongU * alongU <= halfW * halfW
        } else {
            kotlin.math.abs(alongR) <= halfW && kotlin.math.abs(alongU) <= halfH
        }
        if (!inside) return 0
        return if (dir > 0.0) 1 else -1
    }
}

/** A simple immutable gate for the pure timing core. */
data class CoreGate(
    val cx: Double, val cy: Double, val cz: Double,
    val nx: Double, val ny: Double, val nz: Double,
    val halfW: Double, val halfH: Double,
    val ring: Boolean = false,
    val boost: Boolean = false,
)

/** Result of feeding one position step. */
enum class CoreEvent { NONE, GATE_HIT, LAP, MISSED, BOOST, JUMP_START, TIMEOUT_DNF }

/**
 * Injectable-clock race timing core. now() returns ns; startHeat arms; crossing the timing
 * gate (index 0) starts the clock; gates must be hit in order. A lap completed with any gate
 * missed is flagged invalid (correctable). Crossing gate 0 while still ARMED (clock not yet
 * running) = jump start penalty.
 */
class RaceTimingCore(
    val gates: List<CoreGate>,
    val requiredLaps: Int = F9URules.REQUIRED_LAPS,
    val timeLimitNs: Long = F9URules.TIME_LIMIT_SEC * 1_000_000_000L,
    val debounceNs: Long = 2_000_000_000L,
    val jumpStartPenaltyNs: Long = 30_000_000_000L,
    val boostDurationNs: Long = BOOST_DURATION_MS * 1_000_000L,
    val now: () -> Long,
) {
    var clockStarted = false; private set
    var finished = false; private set
    var dnf = false; private set
    var expectedIdx = 0; private set
    var lapsCompleted = 0; private set
    var penaltyNs = 0L; private set
    var lapInvalid = false; private set
    var boostUntilNs = 0L; private set
    var lastEvent = CoreEvent.NONE; private set
    val validLapsNs = mutableListOf<Long>()
    val splitsNs = mutableListOf<Long>()

    private var roundStartNs = 0L
    private var lapStartNs = 0L
    private var prevGateNs = 0L
    private var lastPos: DoubleArray? = null
    private var lastCrossIdx = -1
    private var lastCrossNs = 0L

    fun arm() {
        clockStarted = false; finished = false; dnf = false
        expectedIdx = 0; lapsCompleted = 0; penaltyNs = 0L; lapInvalid = false
        validLapsNs.clear(); splitsNs.clear()
        lastPos = null; lastCrossIdx = -1; lastCrossNs = 0L
        boostUntilNs = 0L; lastEvent = CoreEvent.NONE
    }

    fun boostActive(): Boolean = now() < boostUntilNs

    fun elapsedNs(): Long = if (clockStarted) now() - roundStartNs else 0L

    /** Feed one eye position step. */
    fun step(x: Double, y: Double, z: Double) {
        if (finished) return
        val p = doubleArrayOf(x, y, z)
        val a = lastPos
        if (a != null && gates.isNotEmpty()) {
            handle(a, p)
            // timeout
            if (clockStarted && !finished && now() - roundStartNs >= timeLimitNs) {
                dnf = true; finished = true; lastEvent = CoreEvent.TIMEOUT_DNF
            }
        }
        lastPos = p
    }

    private fun handle(a: DoubleArray, b: DoubleArray) {
        if (expectedIdx !in gates.indices) return
        if (expectedIdx == lastCrossIdx && now() - lastCrossNs < debounceNs) return
        val g = gates[expectedIdx]
        val hit = GateGeo.intersect(
            a[0], a[1], a[2], b[0], b[1], b[2],
            g.cx, g.cy, g.cz, g.nx, g.ny, g.nz,
            g.halfW, g.halfH, g.ring,
        )
        if (hit == 0) return
        if (hit < 0) { lastEvent = CoreEvent.MISSED; return } // wrong way

        val t = now()
        // Jump start: hit gate 0 before the clock started.
        var jump = false
        if (!clockStarted && expectedIdx == 0) {
            penaltyNs += jumpStartPenaltyNs
            jump = true
        }
        lastCrossIdx = expectedIdx
        lastCrossNs = t
        lastEvent = if (jump) CoreEvent.JUMP_START else CoreEvent.GATE_HIT

        if (!clockStarted) {
            clockStarted = true; roundStartNs = t; lapStartNs = t; prevGateNs = t
            lapsCompleted = 0; lapInvalid = false
            splitsNs.clear()
        } else {
            splitsNs.add(t - prevGateNs); prevGateNs = t
        }
        if (g.boost) boostUntilNs = t + boostDurationNs

        if (expectedIdx == 0) {
            val lapNs = t - lapStartNs
            lapStartNs = t
            if (!lapInvalid) validLapsNs.add(lapNs)
            lapsCompleted++
            lapInvalid = false
            if (lapsCompleted >= requiredLaps) finished = true
        }
        expectedIdx = (expectedIdx + 1) % gates.size
    }

    fun avgBest3Ns(): Long {
        if (validLapsNs.isEmpty()) return 0L
        return validLapsNs.sorted().take(3).average().toLong()
    }
}

/** Pure screen-space helpers for the next-gate arrow and live gap. No MC types. */
object LineHelper {
    /**
     * Bearing to the next gate relative to the drone's current yaw (MC degrees, 0=+Z),
     * in [-180,180]. Positive = turn right. Screen-space; caller renders the arrow.
     */
    fun relativeBearingDeg(droneX: Double, droneZ: Double, yawDeg: Float,
                           gateX: Double, gateZ: Double): Double {
        val worldToGate = Math.toDegrees(Math.atan2(-(gateX - droneX), gateZ - droneZ))
        var rel = worldToGate - yawDeg
        while (rel > 180.0) rel -= 360.0
        while (rel < -180.0) rel += 360.0
        return rel
    }

    /** Live gap to the best-lap ghost in seconds: + = behind ghost, - = ahead. */
    fun liveGapSec(currentLapNs: Long, ghostClockNs: Long): Double =
        (currentLapNs - ghostClockNs) / 1_000_000_000.0
}
