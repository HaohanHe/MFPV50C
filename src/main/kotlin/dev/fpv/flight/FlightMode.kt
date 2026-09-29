/*
 * FPV Craft - MIT
 */
package dev.fpv.flight

/**
 * Pilot-assistance mode. Reversible-3D is an independent feature flag
 * ([FpvConfig.reversible3D]) that composes with any of these modes, matching
 * the published Betaflight model where 3D is a motor-output feature.
 */
enum class FlightMode(val id: String) {
    /** Rate mode: sticks command angular velocity, no self-leveling. */
    ACRO("acro"),

    /** Angle mode: sticks command attitude, hard inclination limit, auto-level. */
    ANGLE("angle"),

    /** Horizon mode: acro in the center, angle-style leveling near center. */
    HORIZON("horizon");

    companion object {
        fun byId(id: String): FlightMode = entries.firstOrNull { it.id == id } ?: ACRO
    }
}
