/*
 * FPV Craft - MIT
 * One-apply tuning profiles. Each profile is a data table (rates per axis,
 * flight mode, inclination limit, reversible flag) applied generically; the
 * numbers are engineering starting values, not third-party defaults, and are
 * meant to be refined in-game.
 */
package dev.fpv.flight

enum class TuningPreset(val id: String) {
    /** Fast rates, full acro, for racing practice. */
    RACING("racing"),

    /** Gentle rates with Angle self-leveling, for new pilots. */
    BEGINNER("beginner"),

    /** Slow, expo-heavy rates for smooth footage. */
    CINEMATIC("cinematic");

    private class Def(
        val mode: FlightMode,
        val reversible: Boolean,
        val angleMax: Float,
        val roll: Triple<Float, Float, Float>,
        val pitch: Triple<Float, Float, Float>,
        val yaw: Triple<Float, Float, Float>,
    )

    /** Write this profile's values into [cfg]. */
    fun apply(cfg: FpvConfig) {
        val d = DEFS[ordinal]
        cfg.flightMode = d.mode
        cfg.reversible3D = d.reversible
        cfg.angleMaxDeg = d.angleMax
        cfg.roll.set(d.roll)
        cfg.pitch.set(d.pitch)
        cfg.yaw.set(d.yaw)
    }

    private fun AxisRates.set(t: Triple<Float, Float, Float>) {
        center = t.first
        max = t.second
        expo = t.third
    }

    companion object {
        fun byId(id: String): TuningPreset? = entries.firstOrNull { it.id == id }

        // Triple = (center deg/s, max deg/s, expo).
        private val DEFS = listOf(
            Def(
                mode = FlightMode.ACRO, reversible = false, angleMax = 50f,
                roll = Triple(100f, 900f, 0.05f),
                pitch = Triple(100f, 900f, 0.05f),
                yaw = Triple(70f, 500f, 0.10f),
            ),
            Def(
                mode = FlightMode.ANGLE, reversible = false, angleMax = 35f,
                roll = Triple(50f, 360f, 0.20f),
                pitch = Triple(50f, 360f, 0.20f),
                yaw = Triple(45f, 300f, 0.20f),
            ),
            Def(
                mode = FlightMode.ACRO, reversible = false, angleMax = 50f,
                roll = Triple(40f, 300f, 0.30f),
                pitch = Triple(40f, 300f, 0.30f),
                yaw = Triple(30f, 240f, 0.30f),
            ),
        )
    }
}
