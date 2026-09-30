/*
 * FPV Craft - MIT
 * Evaluates the data-driven channel -> function routing table each frame and
 * turns the results into concrete flight state. Pure logic: given the channel
 * frame and the binding rows it derives which functions are active. LEVEL
 * functions report state; EDGE functions (head-adjust / turtle) fire once per
 * rising transition and are consumed by the caller.
 *
 * Backward compatibility: when the active device has no routing rows the
 * caller keeps using the legacy single-index fields, so old configs behave
 * unchanged.
 */
package dev.fpv.flight

import dev.fpv.input.StickChannels

/** Snapshot of evaluated modes for one frame. */
class ModesResult(
    /** ARM after the PREARM gate (true = armed). */
    val armed: Boolean,
    /** Flight-mode override requested by switches, null = keep current. */
    val desiredMode: FlightMode?,
    val headfree: Boolean,
    val beeper: Boolean,
    val manualFailsafe: Boolean,
    /** Rising-edge events since the last frame. */
    private val edges: Map<FlightFunction, Int>,
) {
    fun edgeCount(f: FlightFunction): Int = edges[f] ?: 0
}

class ModesController {

    private var prevActive = HashMap<FlightFunction, Boolean>()

    /**
     * @param bindings routing rows (device profile); empty yields a neutral
     *                 result that changes nothing.
     */
    fun evaluate(ch: StickChannels, bindings: List<ModeBinding>): ModesResult {
        // OR-aggregate every binding by function.
        val active = HashMap<FlightFunction, Boolean>()
        for (b in bindings) {
            val f = FlightFunction.byId(b.function) ?: continue
            val now = isActive(b, ch)
            active[f] = (active[f] ?: false) || now
        }

        // Rising edges for EDGE-shaped functions.
        val edges = HashMap<FlightFunction, Int>()
        for (f in FlightFunction.entries) {
            val now = active[f] ?: false
            val was = prevActive[f] ?: false
            if (f.activation == FlightFunction.Activation.EDGE && now && !was)
                edges[f] = (edges[f] ?: 0) + 1
        }
        prevActive = active

        val armRaw = active[FlightFunction.ARM] ?: false
        val prearmConfigured = bindings.any { it.function == FlightFunction.PREARM.id }
        val prearmOk = active[FlightFunction.PREARM] ?: false
        val armed = armRaw && (!prearmConfigured || prearmOk)

        // First active mode in a fixed, deterministic priority.
        val desiredMode = when {
            active[FlightFunction.ANGLE] ?: false -> FlightMode.ANGLE
            active[FlightFunction.HORIZON] ?: false -> FlightMode.HORIZON
            active[FlightFunction.ACRO] ?: false -> FlightMode.ACRO
            else -> null
        }

        return ModesResult(
            armed = armed,
            desiredMode = desiredMode,
            headfree = active[FlightFunction.HEADFREE] ?: false,
            beeper = active[FlightFunction.BEEPER] ?: false,
            manualFailsafe = active[FlightFunction.FAILSAFE] ?: false,
            edges = edges,
        )
    }

    /** Clear edge/level history (on device change / disengage). */
    fun reset() = prevActive.clear()

    private fun isActive(b: ModeBinding, ch: StickChannels): Boolean {
        val value = sourceValue(b, ch)
        val inRange = value >= b.activeLow && value <= b.activeHigh
        return inRange != b.negated
    }

    /** Resolve a binding source to a normalized value (-1..1, buttons 0/1). */
    private fun sourceValue(b: ModeBinding, ch: StickChannels): Float = when (b.sourceKind) {
        "AUX" -> ch.auxChannels.firstOrNull { it.name == b.sourceName }?.value ?: 0f
        "RAW_AXIS" -> ch.aux.getOrElse(b.sourceIndex) { 0f }
        "BUTTON" -> if (b.sourceIndex in ch.rawButtons.indices &&
            ch.rawButtons[b.sourceIndex].toInt() != 0
        ) 1f else 0f
        "HAT" -> {
            val h = ch.rawHats.getOrElse(b.sourceIndex) { 0 }.toInt()
            val match = if (b.hatDirection != 0) (h and b.hatDirection) != 0
            else h != 0
            if (match) 1f else 0f
        }
        else -> 0f
    }
}
