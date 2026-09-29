/*
 * FPV Craft - MIT
 * RX-link monitor: a clean-room failsafe state machine driven purely by whether
 * a USB radio is currently enumerated (there is NO real telemetry link in this
 * mod). States:
 *
 *   NORMAL  -> radio present
 *   HOLD    -> radio just disappeared; stick to last-known state for holdMs
 *   FAILSAFE-> hold elapsed; run the configured procedure (one-shot trigger)
 *
 * Recovery requires [present] to stay true for recoveryDelayMs before NORMAL
 * resumes. The exposed LQ (link quality, 0..100) is a VIRTUAL value derived from
 * this state machine only - it must never be presented as real telemetry.
 */
package dev.fpv.flight

enum class LinkState { NORMAL, HOLD, FAILSAFE }

class LinkMonitor(
    private val holdMs: Long = Defaults.FAILSAFE_HOLD_MS,
    private val recoveryDelayMs: Long = Defaults.FAILSAFE_RECOVERY_DELAY_MS,
) {
    var state: LinkState = LinkState.NORMAL
        private set

    /** Virtual link quality, 0..100. NO real telemetry behind this number. */
    var lq: Int = 100
        private set

    /** Set true on the frame the machine enters FAILSAFE; consume once per event. */
    private var failsafeTriggered = false

    private var heldMs = 0L
    private var recoveryMs = 0L

    fun reset() {
        state = LinkState.NORMAL
        lq = 100
        heldMs = 0L
        recoveryMs = 0L
        failsafeTriggered = false
    }

    /** True exactly once on the transition NORMAL/HOLD -> FAILSAFE. */
    fun consumeTriggered(): Boolean {
        val t = failsafeTriggered
        failsafeTriggered = false
        return t
    }

    fun update(present: Boolean, dt: Float) {
        val dtMs = (dt * 1000f).toLong()
        when (state) {
            LinkState.NORMAL -> {
                if (present) {
                    heldMs = 0L
                    lq = 100
                } else {
                    state = LinkState.HOLD
                    heldMs = 0L
                }
            }
            LinkState.HOLD -> {
                if (present) {
                    // Brief glitch: stay in hold, recover immediately.
                    state = LinkState.NORMAL
                    heldMs = 0L
                } else {
                    heldMs += dtMs
                    // Decay LQ across the hold window, floor at 30.
                    lq = (100 - 70f * heldMs.coerceAtMost(holdMs) / holdMs).toInt().coerceIn(30, 100)
                    if (heldMs >= holdMs) {
                        state = LinkState.FAILSAFE
                        failsafeTriggered = true
                        lq = 10
                    }
                }
            }
            LinkState.FAILSAFE -> {
                lq = 10
                if (present) {
                    recoveryMs += dtMs
                    if (recoveryMs >= recoveryDelayMs) {
                        state = LinkState.NORMAL
                        recoveryMs = 0L
                        heldMs = 0L
                    }
                } else {
                    recoveryMs = 0L
                }
            }
        }
    }
}
