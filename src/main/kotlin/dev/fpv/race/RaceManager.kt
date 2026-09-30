/*
 * FPV Craft - MIT
 * RaceManager: F9U race core, pure client, no packets, no entities.
 *
 * State machine:  IDLE -> ARMED -> FLYING -> PROVISIONAL -> FINISHED
 *   (TIMING_GATE mode: clock starts on the timing gate crossing; ARMED->FLYING
 *    at that point. START_SIGNAL mode: clock runs from startHeat).
 *
 * Integration hooks:
 *   RaceManager.onClientTick(mc)          // per client tick
 *   RaceManager.onFrame(mc, dt)           // per rendered frame
 *   RaceManager.registerWorldRendering()   // once
 *   RaceManager.drawHud(ctx)              // HUD render tail
 *   RaceManager.openScreen(mc)            // open the race GUI
 */
package dev.fpv.race

import dev.fpv.client.FpvClient
import dev.fpv.flight.FpvConfig
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

enum class RacePhase { IDLE, ARMED, FLYING, PROVISIONAL, FINISHED }

object RaceManager {

    var track: TrackDoc = TrackDoc()
        private set

    // ---- heat state ----
    var phase: RacePhase = RacePhase.IDLE
        private set

    private var clockStarted = false
    private var roundStartNanos = 0L
    private var lapStartNanos = 0L
    private var prevGateNanos = 0L
    private var expectedIdx = 0
    private var lapsCompleted = 0
    private var penaltyMs = 0L
    private var resultMs = 0L
    private var dnf = false

    private var lastPos: Vec3? = null
    private var lastCrossGate = -1
    private var lastCrossNanos = 0L

    private var splitsMs = mutableListOf<Long>()

    // transient flash
    private var flashText = ""
    private var flashUntil = 0L
    private var bannerText = ""
    private var bannerUntil = 0L

    // ---- ghost ----
    private var recording = mutableListOf<GhostSample>()
    var ghostPos: Vec3? = null
        private set
    var ghostAttitude: Quaternionf? = null
        private set

    // ------------------------------------------------------------------
    private val cfg: FpvConfig get() = FpvClient.config
    private fun race() = cfg.race

    /**
     * Master race gate: when false (the default) NO gate detection, timing,
     * time limit, landing-zone check, ghost recording/replay, gate rendering
     * or race HUD runs. The data structures and track editor stay intact; the
     * pilot flies plain single-player freestyle immediately, with no race
     * calibration required.
     */
    fun racingEnabled(): Boolean = race()?.raceEnabled == true

    private fun requiredLaps(): Int = race()?.requiredLaps ?: F9URules.REQUIRED_LAPS
    private fun timeLimitNs(): Long =
        ((race()?.timeLimitSec ?: F9URules.TIME_LIMIT_SEC).toLong()) * 1_000_000_000L

    fun gateCount(): Int = track.gates.size
    fun nextGateIndex(): Int = expectedIdx
    fun lapsCompleted(): Int = lapsCompleted
    fun lapsRemaining(): Int = (requiredLaps() - lapsCompleted).coerceAtLeast(0)
    fun penaltyMs(): Long = penaltyMs
    fun isDnf(): Boolean = dnf

    fun elapsedMs(): Long =
        if (clockStarted) (System.nanoTime() - roundStartNanos) / 1_000_000L else 0L

    fun remainingTimeMs(): Long = (timeLimitNs() / 1_000_000L - elapsedMs()).coerceAtLeast(0L)

    fun currentLapMs(): Long =
        if (clockStarted && phase != RacePhase.IDLE) (System.nanoTime() - lapStartNanos) / 1_000_000L else 0L

    fun bestRoundMs(): Long = track.bestRoundMs
    fun validLapCount(): Int = track.validLapsMs.size

    /** Average of the up-to-3 fastest valid single laps (ms; 0 if none). */
    fun avgBest3LapsMs(): Long {
        if (track.validLapsMs.isEmpty()) return 0L
        val top = track.validLapsMs.sorted().take(3)
        return top.average().toLong()
    }

    /** Ranking result line per configured method. */
    fun rankingSummary(): String {
        return when (race()?.rankingMethod) {
            "BEST_FULL_ROUND" -> "BEST ROUND  ${fmt(track.bestRoundMs)}"
            else -> "AVG TOP3 LAPS  ${fmt(avgBest3LapsMs())}  (${track.validLapsMs.size} laps)"
        }
    }

    fun flash(): String = if (System.currentTimeMillis() < flashUntil) flashText else ""
    fun banner(): String = if (System.currentTimeMillis() < bannerUntil) bannerText else ""

    // ------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------
    fun onClientTick(mc: Minecraft) = onFrame(mc, 1f / 20f)

    fun onFrame(mc: Minecraft, dt: Float) {
        val player = mc.player
        // Master switch off: no detection / clock / ghost. Drop any active heat
        // back to IDLE so a disabled config never leaves a stale timer running.
        if (!racingEnabled()) {
            if (phase != RacePhase.IDLE) reset()
            lastPos = null; ghostPos = null; ghostAttitude = null
            return
        }
        if (player == null) { lastPos = null; ghostPos = null; return }
        val eye = player.getEyePosition()
        val prev = lastPos

        if (prev != null && track.gates.isNotEmpty() &&
            (phase == RacePhase.ARMED || phase == RacePhase.FLYING)
        ) {
            detectCrossing(prev, eye)
        }
        lastPos = eye

        // Time limit ends the heat.
        if (clockStarted && phase == RacePhase.FLYING) {
            if (System.nanoTime() - roundStartNanos >= timeLimitNs()) {
                timeoutFinish()
            }
        }

        // PROVISIONAL -> land in the zone to validate.
        if (phase == RacePhase.PROVISIONAL) {
            val dz = track.landingZone.center().distanceTo(eye)
            if (dz <= track.landingZone.radius) {
                phase = RacePhase.FINISHED
                bannerText = "FINISHED (valid)"
                bannerUntil = System.currentTimeMillis() + 3000L
            }
        }

        recordGhost(eye)
        updateGhostReplay()
    }

    // ------------------------------------------------------------------
    // Gate crossing / lap state machine
    // ------------------------------------------------------------------
    private fun detectCrossing(a: Vec3, b: Vec3) {
        val gates = track.gates
        if (expectedIdx !in gates.indices) return
        val g = gates[expectedIdx]
        val debounce = (race()?.minLapDebounceMs ?: 2000L)
        val now = System.nanoTime()
        if (expectedIdx == lastCrossGate && (now - lastCrossNanos) / 1_000_000L < debounce) return
        if (g.intersect(a, b) == null) return

        lastCrossGate = expectedIdx
        lastCrossNanos = now
        onGateCrossed(expectedIdx, now)
    }

    private fun onGateCrossed(idx: Int, now: Long) {
        val gates = track.gates
        val tg = track.timingGateIndex.coerceIn(0, gates.size - 1)

        if (!clockStarted) {
            // The timing gate (or first expected gate in START_SIGNAL) starts the clock.
            clockStarted = true
            roundStartNanos = now
            lapStartNanos = now
            prevGateNanos = now
            lapsCompleted = 0
            splitsMs = MutableList(gates.size) { 0L }
            phase = RacePhase.FLYING
            flashText = "GO! GATE $idx"
            flashUntil = System.currentTimeMillis() + 1200L
            expectedIdx = (idx + 1) % gates.size
            return
        }

        // Record a sector split when enabled.
        if (race()?.sectorsEnabled == true) {
            splitsMs[idx] = (now - prevGateNanos) / 1_000_000L
        }
        prevGateNanos = now

        if (idx == tg) {
            // Completed one full lap.
            val lapMs = (now - lapStartNanos) / 1_000_000L
            lapStartNanos = now
            track.validLapsMs.add(lapMs)
            lapsCompleted++
            flashText = "LAP $lapsCompleted/${requiredLaps()}  ${fmt(lapMs)}"
            flashUntil = System.currentTimeMillis() + 1500L
            if (lapsCompleted >= requiredLaps()) {
                completeRound(now)
            }
        }
        expectedIdx = (idx + 1) % gates.size
    }

    private fun completeRound(now: Long) {
        val flightMs = (now - roundStartNanos) / 1_000_000L
        resultMs = flightMs + penaltyMs
        if (track.bestRoundMs == 0L || resultMs < track.bestRoundMs) {
            track.bestRoundMs = resultMs
            track.ghost = recording.toMutableList()
            TrackStore.save(track)
        }
        phase = if (race()?.requireLandingZone == true) RacePhase.PROVISIONAL else RacePhase.FINISHED
        bannerText = if (phase == RacePhase.PROVISIONAL)
            "ROUND COMPLETE - LAND IN THE ZONE" else "FINISHED  ${fmt(resultMs)}"
        bannerUntil = System.currentTimeMillis() + 3000L
    }

    private fun timeoutFinish() {
        if (lapsCompleted < requiredLaps()) {
            // Incomplete: auto-penalise and mark DNF.
            penaltyMs += (race()?.penaltySec ?: F9URules.ABANDON_PENALTY_SEC).toLong() * 1000L
            dnf = true
            phase = RacePhase.FINISHED
            bannerText = "TIME LIMIT - INCOMPLETE (DNF)"
            bannerUntil = System.currentTimeMillis() + 3000L
        }
    }

    /** Manual abandon / incomplete-task penalty (+config seconds). */
    fun addPenalty() {
        if (phase == RacePhase.IDLE || phase == RacePhase.FINISHED) return
        penaltyMs += (race()?.penaltySec ?: F9URules.ABANDON_PENALTY_SEC).toLong() * 1000L
        flashText = "+${race()?.penaltySec ?: F9URules.ABANDON_PENALTY_SEC}s PENALTY"
        flashUntil = System.currentTimeMillis() + 1500L
    }

    // ------------------------------------------------------------------
    // Heat control
    // ------------------------------------------------------------------
    /** Start a new heat: reset timing, keep the course. */
    fun startHeat() {
        val gates = track.gates
        expectedIdx = track.timingGateIndex.coerceIn(0, gates.size - 1)
        clockStarted = false
        lapsCompleted = 0
        penaltyMs = 0L
        resultMs = 0L
        dnf = false
        lastPos = null
        lastCrossGate = -1
        recording.clear()
        splitsMs = MutableList(gates.size) { 0L }
        // START_SIGNAL mode: clock runs immediately.
        if (race()?.timingTrigger == "START_SIGNAL" && gates.isNotEmpty()) {
            clockStarted = true
            roundStartNanos = System.nanoTime()
            lapStartNanos = roundStartNanos
            prevGateNanos = roundStartNanos
            phase = RacePhase.FLYING
            flashText = "GO! (start signal)"
            flashUntil = System.currentTimeMillis() + 1200L
        } else {
            phase = RacePhase.ARMED
            flashText = "READY - FLY TO TIMING GATE ${track.timingGateIndex.coerceIn(0, gates.size - 1)}"
            flashUntil = System.currentTimeMillis() + 2000L
        }
    }

    /** Reset to IDLE, keeping gates / best data. */
    fun reset() {
        phase = RacePhase.IDLE
        clockStarted = false
        lapsCompleted = 0
        penaltyMs = 0L
        resultMs = 0L
        dnf = false
        lastPos = null
        lastCrossGate = -1
        recording.clear()
        splitsMs = MutableList(track.gates.size) { 0L }
        flashText = ""; flashUntil = 0L
        bannerText = ""; bannerUntil = 0L
        ghostPos = null
    }

    // ------------------------------------------------------------------
    // Ghost recording / replay
    // ------------------------------------------------------------------
    private fun recordGhost(eye: Vec3) {
        val flying = FpvClient.flight.ready && FpvClient.armed
        if (!clockStarted || !flying || race()?.ghostEnabled != true) return
        val t = (System.nanoTime() - lapStartNanos) / 1_000_000_000f
        recording.add(
            GhostSample(
                t = t, x = eye.x, y = eye.y, z = eye.z,
                qx = FpvClient.flight.attitude.x(), qy = FpvClient.flight.attitude.y(),
                qz = FpvClient.flight.attitude.z(), qw = FpvClient.flight.attitude.w(),
                throttle = FpvClient.throttle,
            )
        )
    }

    private fun updateGhostReplay() {
        ghostPos = null; ghostAttitude = null
        if (!clockStarted || race()?.ghostEnabled != true || !FpvClient.flight.ready) return
        val ghost = track.ghost
        if (ghost.size < 2) return
        val elapsed = (System.nanoTime() - lapStartNanos) / 1_000_000_000f
        if (elapsed <= ghost.first().t) { setGhost(ghost.first()); return }
        if (elapsed >= ghost.last().t) return
        var lo = 0; var hi = ghost.size - 1
        while (lo + 1 < hi) { val m = (lo + hi) / 2; if (ghost[m].t < elapsed) lo = m else hi = m }
        val a = ghost[lo]; val b = ghost[hi]
        val f = ((elapsed - a.t) / (b.t - a.t).coerceAtLeast(1.0e-6f)).coerceIn(0f, 1f)
        ghostPos = Vec3(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f)
        ghostAttitude = Quaternionf(a.quaternion()).apply { slerp(b.quaternion(), f) }
    }

    private fun setGhost(s: GhostSample) {
        ghostPos = Vec3(s.x, s.y, s.z); ghostAttitude = s.quaternion()
    }

    // ------------------------------------------------------------------
    // World rendering
    // ------------------------------------------------------------------
    fun registerWorldRendering() = GateRenderer.register()

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------
    fun drawHud(ctx: GuiGraphics) {
        if (!racingEnabled()) return
        val mc = Minecraft.getInstance()
        val font = mc.font
        val sw = mc.window.guiScaledWidth
        val sh = mc.window.guiScaledHeight
        val cx = sw / 2

        val px = sw - 120
        var py = 6
        ctx.drawString(font, "F9U RACE", px, py, 0xFFFFFF, true); py += 11
        ctx.drawString(font, "LAP  $lapsCompleted/${requiredLaps()}", px, py, 0x55FF55, true); py += 10
        ctx.drawString(font, "TIME ${fmt(elapsedMs())}", px, py, 0x55FF55, true); py += 10
        ctx.drawString(font, "LEFT ${fmt(remainingTimeMs())}", px, py, 0xFFFF55, true); py += 10
        ctx.drawString(font, "PEN  +${fmt(penaltyMs())}", px, py, 0xFF5555, true); py += 10
        ctx.drawString(font, "BEST ${fmt(track.bestRoundMs)}", px, py, 0xAAAAAA, true); py += 10
        ctx.drawString(font, rankingSummary(), px, py, 0x55FFFF, true)

        // Flash above the crosshair; banner near the bottom.
        if (flash().isNotEmpty()) ctx.drawCenteredString(font, flash(), cx, sh / 2 - 34, 0xFFFFFF)
        if (banner().isNotEmpty()) ctx.drawCenteredString(font, banner(), cx, sh - 40, 0xFFFF55)
    }

    // ------------------------------------------------------------------
    // Track editing
    // ------------------------------------------------------------------
    fun newTrack(name: String) {
        track = TrackDoc().apply { this.name = name }
        reset()
    }

    fun loadTrack(name: String): Boolean {
        track = TrackStore.load(name) ?: return false
        reset()
        return true
    }

    fun saveTrackAs(name: String): Boolean {
        track.name = TrackStore.safeName(name)
        return TrackStore.save(track)
    }

    fun deleteTrack(name: String): Boolean = TrackStore.delete(name)
    fun listTracks(): List<String> = TrackStore.listTracks()

    /** Add a gate at the player's eye, using the chosen F9U template. */
    fun addGateAtEye(mc: Minecraft, template: GateTemplate) {
        val p = mc.player ?: return
        val eye = p.getEyePosition()
        track.gates.add(
            GateDef(
                x = eye.x, y = eye.y, z = eye.z,
                yawDeg = p.yRot, pitchDeg = p.xRot,
                width = template.defaultWidthM, height = template.defaultHeightM,
                index = track.gates.size, shape = template.shape.name,
            )
        )
    }

    fun setStartAtEye(mc: Minecraft) {
        val p = mc.player ?: return
        track.start.x = p.x; track.start.y = p.getEyePosition().y; track.start.z = p.z
        track.start.yawDeg = p.yRot; track.start.pitchDeg = p.xRot
        if (track.timingGateIndex < 0) track.timingGateIndex = 0
    }

    fun setFinishAtEye(mc: Minecraft) {
        val p = mc.player ?: return
        track.finish.x = p.x; track.finish.y = p.getEyePosition().y; track.finish.z = p.z
        track.finish.yawDeg = p.yRot; track.finish.pitchDeg = p.xRot
    }

    fun setLandingZoneAtEye(mc: Minecraft) {
        val p = mc.player ?: return
        track.landingZone.x = p.x; track.landingZone.y = p.y; track.landingZone.z = p.z
    }

    fun removeGate(index: Int) {
        if (index in track.gates.indices) {
            track.gates.removeAt(index)
            track.gates.forEachIndexed { i, g -> g.index = i }
            reset()
        }
    }

    fun setGateWidth(index: Int, width: Float) {
        track.gates.getOrNull(index)?.width = width.coerceAtLeast(0.5f)
    }

    fun setGateHeight(index: Int, height: Float) {
        track.gates.getOrNull(index)?.height = height.coerceAtLeast(0.5f)
    }

    fun setTimingGate(index: Int) {
        if (index in track.gates.indices) track.timingGateIndex = index
    }

    fun toggleGhost() {
        race()?.let { it.ghostEnabled = !it.ghostEnabled }
        cfg.save()
    }

    /** Anti-turtle: delegate to the flight controller (no touch). */
    fun turtleRight(): String {
        if (!FpvClient.flight.ready) return "NOT FLYING (turtle only after a crash)"
        FpvClient.flight.turtleRight()
        return "TURTLE RIGHT: flipping"
    }

    fun openScreen(mc: Minecraft) = mc.setScreen(RaceScreen(null))

    // ------------------------------------------------------------------
    private fun fmt(ms: Long): String {
        if (ms <= 0L) return "--:--.-"
        return "%d:%05.2f".format(ms / 60000, (ms % 60000) / 1000.0)
    }
}
