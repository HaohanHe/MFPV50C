/*
 * FPV Craft - MIT
 * The set of functions a pilot AUX control (named channel / raw axis / button /
 * hat) can be routed to - the data-driven equivalent of a flight-controller
 * "Modes" tab. A function is never hard-wired to a channel: ModeBinding rows map
 * channel references onto these functions and are persisted per device.
 *
 * Two activation shapes exist:
 *  - LEVEL: the function is active while the control sits in its active range
 *           (ARM, flight-mode selects, head-free, prearm, beeper, manual failsafe).
 *  - EDGE:  an action fires once on each rising transition (head-adjust, turtle).
 */
package dev.fpv.flight

enum class FlightFunction(
    /** Stable id persisted in ModeBinding. */
    val id: String,
    /** How the function is activated. */
    val activation: Activation,
) {
    /** Motors/flight permitted while active. Gated by PREARM when a PREARM binding exists. */
    ARM("ARM", Activation.LEVEL),

    /** Safety gate: ARM is accepted only while PREARM is active. */
    PREARM("PREARM", Activation.LEVEL),

    /** Self-leveling (Angle) flight mode while active. */
    ANGLE("ANGLE", Activation.LEVEL),

    /** Horizon (mixed) flight mode while active. */
    HORIZON("HORIZON", Activation.LEVEL),

    /** Acro (rate) flight mode while active. */
    ACRO("ACRO", Activation.LEVEL),

    /** Heading-lock (head-free) while active. */
    HEADFREE("HEADFREE", Activation.LEVEL),

    /** Re-center the head-free reference heading on the active (rising) edge. */
    HEADADJ("HEADADJ", Activation.EDGE),

    /** Find-after-crash righting (anti-turtle) on the active edge. */
    TURTLE("TURTLE", Activation.EDGE),

    /** Audible locate/beep while active. */
    BEEPER("BEEPER", Activation.LEVEL),

    /** Pilot-forced failsafe while active (runs the configured failsafe). */
    FAILSAFE("FAILSAFE", Activation.LEVEL);

    enum class Activation { LEVEL, EDGE }

    companion object {
        fun byId(id: String): FlightFunction? = entries.firstOrNull { it.id == id }
    }
}
