/*
 * FPV Craft - MIT
 * RaceManager: single-lap racing core, pure client, no packets, no entities.
 *
 * Responsibilities: ordered gate-crossing detection (segment vs gate plane),
 * lap/split timing, best-lap ghost recording & replay, and the transient HUD
 * flash state. Integration points (call these from the client entrypoint):
 *
 *   RaceManager.onClientTick(mc)      // per client tick (END_CLIENT_TICK)
 *   RaceManager.onFrame(mc, dt)       // per rendered frame (render hook)
 *   RaceManager.registerWorldRendering() // once; hooks WorldRenderEvents
 *   RaceManager.drawHud(ctx)          // from the GUI/HUD render tail
 *   RaceManager.openScreen(mc)        // open the race GUI
 */
package dev.fpv.race

import dev.fpv.client.FpvClient
import dev.fpv.flight.FpvConfig
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

object RaceManager {

    // ---- live state (mutable) ----
    /** Track currently being edited/raced on. */
    var track: TrackDoc = TrackDoc()
        private set

    private var lapActive = false
    private var lapStartNanos = 0L
    private var nextGateIndex = 0
    private var lastPos: Vec3? = null

    private var lastCrossGate = -1
    private var lastCrossNanos = 0L

    private var lapTimesMs = mutableListOf<Long>()
    private var splitsMs = mutableListOf<Long>()
    private var prevGateCrossNanos = 0L

    // transient flash (ms timestamps)
    private var gateFlashText = ""
    private var gateFlashUntil = 0L
    private var lapFlashText = ""
    private var lapFlashUntil = 0L

    // ---- ghost recording / replay ----
    private var recording = mutableListOf<GhostSample>()
    private var recordStartNanos = 0L

    /** Interpolated ghost pose for this frame (world coords + attitude). */
    var ghostPos: Vec3? = null
        private set

    var ghostAttitude: Quaternionf? = null
        private set

    // ------------------------------------------------------------------
    // Configuration accessors (single source: FpvConfig.race)
    // ------------------------------------------------------------------
    private val cfg: FpvConfig get() = FpvClient.config
    private fun race() = cfg.race

    fun gateCount(): Int = track.gates.size
    fun isTiming(): Boolean = lapActive
    fun nextGateIndex(): Int = nextGateIndex

    fun currentLapMs(): Long =
        if (lapActive) (System.nanoTime() - lapStartNanos) / 1_000_000L else 0L

    fun bestLapMs(): Long = track.bestLapMs
    fun lastLapMs(): Long = lapTimesMs.lastOrNull() ?: 0L
    fun averageLapMs(): Long =
        if (lapTimesMs.isEmpty()) 0L else lapTimesMs.average().toLong()

    fun splits(): List<Long> = splitsMs.toList()

    /** Remaining gate-flash display in ms (0 = hidden). */
    fun gateFlashRemainingMs(): Long = (gateFlashUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    fun lapFlashRemainingMs(): Long = (lapFlashUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    fun gateFlash(): String = if (gateFlashRemainingMs() > 0) gateFlashText else ""
    fun lapFlash(): String = if (lapFlashRemainingMs() > 0) lapFlashText else ""

    // ------------------------------------------------------------------
    // Hooks called by the integrator
    // ------------------------------------------------------------------

    /** Per client tick. Currently a thin driver; the heavy per-frame work is onFrame. */
    fun onClientTick(mc: Minecraft) {
        onFrame(mc, 1f / 20f)
    }

    /**
     * Per rendered frame. Runs gate-crossing detection (segment between the
     * previous frame's eye position and now), advances timing, records ghost
     * samples while flying, and drives the ghost replay clock.
     */
    fun onFrame(mc: Minecraft, dt: Float) {
        val player = mc.player
        if (player == null) {
            lastPos = null
            ghostPos = null
            return
        }
        val eye = player.getEyePosition()
        val prev = lastPos

        if (prev != null && track.gates.isNotEmpty()) {
            detectCrossing(prev, eye)
        }
        lastPos = eye

        // Recording (only while a timed lap is active and the craft is flying).
        val flying = FpvClient.flight.ready && FpvClient.armed
        if (lapActive && flying && race()?.ghostEnabled == true) {
            val t = (System.nanoTime() - recordStartNanos) / 1_000_000_000f
            recording.add(
                GhostSample(
                    t = t,
                    x = eye.x, y = eye.y, z = eye.z,
                    qx = FpvClient.flight.attitude.x(),
                    qy = FpvClient.flight.attitude.y(),
                    qz = FpvClient.flight.attitude.z(),
                    qw = FpvClient.flight.attitude.w(),
                    throttle = FpvClient.throttle,
                )
            )
        }

        // Ghost replay: sample the best-lap trail at the current lap time.
        updateGhostReplay()
    }

    private fun detectCrossing(a: Vec3, b: Vec3) {
        val gates = track.gates
        if (nextGateIndex < 0 || nextGateIndex >= gates.size) return
        val expected = gates[nextGateIndex]
        // Debounce: ignore the same gate for minLapDebounceMs after a crossing.
        val debounce = race()?.minLapDebounceMs ?: 2000L
        val now = System.nanoTime()
        if (nextGateIndex == lastCrossGate && (now - lastCrossNanos) / 1_000_000L < debounce) return

        if (expected.intersect(a, b) != null) {
            onGateCrossed(nextGateIndex)
        }
    }

    private fun onGateCrossed(idx: Int) {
        val now = System.nanoTime()
        lastCrossGate = idx
        lastCrossNanos = now
        val gates = track.gates

        if (idx == 0 && !lapActive) {
            // ---- Start the first timed lap. ----
            lapActive = true
            lapStartNanos = now
            prevGateCrossNanos = now
            recordStartNanos = now
            recording.clear()
            splitsMs = MutableList(gates.size) { 0L }
            nextGateIndex = if (gates.size > 1) 1 else 0
            showGateFlash(0, 0L)
            return
        }

        if (idx == 0) {
            // ---- Lap complete. ----
            val lapMs = (now - lapStartNanos) / 1_000_000L
            lapTimesMs.add(lapMs)
            val prevBest = track.bestLapMs
            val isBest = prevBest == 0L || lapMs < prevBest
            if (isBest) {
                track.bestLapMs = lapMs
                track.ghost = recording.toMutableList()
                TrackStore.save(track)
            }
            lapFlashText = formatLap(lapMs) + if (isBest) "  NEW BEST!" else ""
            lapFlashUntil = System.currentTimeMillis() + 2500L
            // Begin the next lap immediately.
            lapActive = true
            lapStartNanos = now
            prevGateCrossNanos = now
            recordStartNanos = now
            recording.clear()
            splitsMs = MutableList(gates.size) { 0L }
            nextGateIndex = if (gates.size > 1) 1 else 0
            return
        }

        // ---- Intermediate gate split. ----
        val splitMs = (now - prevGateCrossNanos) / 1_000_000L
        prevGateCrossNanos = now
        splitsMs[idx] = splitMs
        showGateFlash(idx, splitMs)
        nextGateIndex = (idx + 1) % gates.size
    }

    private fun showGateFlash(idx: Int, splitMs: Long) {
        gateFlashText = if (splitMs > 0L) "GATE $idx  +${formatMs(splitMs)}" else "GATE $idx"
        gateFlashUntil = System.currentTimeMillis() + 1200L
    }

    /** Reset current lap / timing; gates and best-lap data are kept. */
    fun reset() {
        lapActive = false
        nextGateIndex = 0
        lastCrossGate = -1
        lastCrossNanos = 0L
        splitsMs = MutableList(track.gates.size) { 0L }
        recording.clear()
        gateFlashText = ""; gateFlashUntil = 0L
        lapFlashText = ""; lapFlashUntil = 0L
        ghostPos = null
    }

    // ------------------------------------------------------------------
    // Ghost replay
    // ------------------------------------------------------------------
    private fun updateGhostReplay() {
        ghostPos = null
        ghostAttitude = null
        if (!lapActive) return
        if (race()?.ghostEnabled != true) return
        if (!FpvClient.flight.ready) return
        val ghost = track.ghost
        if (ghost.size < 2) return

        val elapsedSec = (System.nanoTime() - lapStartNanos) / 1_000_000_000f
        // Find bracketing samples.
        if (elapsedSec <= ghost.first().t) {
            setGhost(ghost.first())
            return
        }
        val last = ghost.last()
        if (elapsedSec >= last.t) {
            return // ghost finished; nothing to show
        }
        var lo = 0
        var hi = ghost.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (ghost[mid].t < elapsedSec) lo = mid else hi = mid
        }
        val a = ghost[lo]
        val b = ghost[hi]
        val span = (b.t - a.t).coerceAtLeast(1.0e-6f)
        val f = ((elapsedSec - a.t) / span).coerceIn(0f, 1f)
        val x = a.x + (b.x - a.x) * f
        val y = a.y + (b.y - a.y) * f
        val z = a.z + (b.z - a.z) * f
        ghostPos = Vec3(x, y, z)
        val q = Quaternionf(a.quaternion())
        q.slerp(b.quaternion(), f)
        ghostAttitude = q
    }

    private fun setGhost(s: GhostSample) {
        ghostPos = Vec3(s.x, s.y, s.z)
        ghostAttitude = s.quaternion()
    }

    // ------------------------------------------------------------------
    // World rendering (delegates to GateRenderer)
    // ------------------------------------------------------------------
    fun registerWorldRendering() {
        GateRenderer.register()
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------
    fun drawHud(ctx: GuiGraphics) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val sw = mc.window.guiScaledWidth
        val sh = mc.window.guiScaledHeight
        val cx = sw / 2

        // ---- right-side timing panel ----
        val px = sw - 104
        var py = 6
        ctx.drawString(font, "RACE", px, py, 0xFFFFFF, true); py += 11
        ctx.drawString(font, "LAP  ${formatMs(currentLapMs())}", px, py, 0x55FF55, true); py += 10
        ctx.drawString(font, "BEST ${formatMs(bestLapMs())}", px, py, 0xFFFF55, true); py += 10
        ctx.drawString(font, "LAST ${formatMs(lastLapMs())}", px, py, 0xAAAAAA, true); py += 10
        ctx.drawString(font, "AVG  ${formatMs(averageLapMs())}", px, py, 0xAAAAAA, true); py += 10

        // Next-gate guidance (below the panel).
        if (track.gates.isNotEmpty()) {
            val g = track.gates[nextGateIndex.coerceIn(0, track.gates.size - 1)]
            val eye = mc.player?.getEyePosition()
            if (eye != null) {
                val dist = g.center().distanceTo(eye)
                ctx.drawString(font, "NEXT GATE ${nextGateIndex()}  ${dist.toInt()}m", px, py, 0x55FFFF, true)
            }
        }

        // ---- Center flash: above the crosshair (cx,cy) so we don't overlap it ----
        if (gateFlashRemainingMs() > 0) {
            val cy = sh / 2
            ctx.drawCenteredString(font, gateFlashText, cx, cy - 34, 0xFFFFFF)
        }
        if (lapFlashRemainingMs() > 0) {
            val cy = sh / 2
            ctx.drawCenteredString(font, lapFlashText, cx, sh - 40, 0xFFFF55)
        }
    }

    // ------------------------------------------------------------------
    // Track editing (used by RaceScreen)
    // ------------------------------------------------------------------
    fun newTrack(name: String) {
        track = TrackDoc().apply { this.name = name }
        reset()
    }

    fun loadTrack(name: String): Boolean {
        val doc = TrackStore.load(name) ?: return false
        track = doc
        reset()
        return true
    }

    fun saveTrackAs(name: String): Boolean {
        track.name = TrackStore.safeName(name)
        return TrackStore.save(track)
    }

    fun deleteTrack(name: String): Boolean = TrackStore.delete(name)

    fun listTracks(): List<String> = TrackStore.listTracks()

    /** Add a gate at the player's current eye position + look direction. */
    fun addGateAtEye(mc: Minecraft) {
        val p = mc.player ?: return
        val eye = p.getEyePosition()
        val w = race()?.gateWidth ?: 3f
        val h = race()?.gateHeight ?: 3f
        track.gates.add(
            GateDef(
                x = eye.x, y = eye.y, z = eye.z,
                yawDeg = p.yRot, pitchDeg = p.xRot,
                width = w, height = h,
                index = track.gates.size,
            )
        )
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

    fun toggleGhost() {
        race()?.let { it.ghostEnabled = !it.ghostEnabled }
        cfg.save()
    }

    // ------------------------------------------------------------------
    // GUI entry
    // ------------------------------------------------------------------
    fun openScreen(mc: Minecraft) {
        mc.setScreen(RaceScreen(null))
    }

    // ------------------------------------------------------------------
    // formatting
    // ------------------------------------------------------------------
    private fun formatMs(ms: Long): String {
        if (ms <= 0L) return "--:--.-"
        val m = ms / 60000
        val s = (ms % 60000) / 1000.0
        return "%d:%05.2f".format(m, s)
    }

    private fun formatLap(ms: Long): String = "LAP ${formatMs(ms)}"
}
