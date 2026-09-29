/*
 * FPV Craft - MIT
 * Serializable track document. Saved as JSON under
 * <gameDir>/fpv-tracks/<name>.json and directly shareable.
 */
package dev.fpv.race

/** On-disk track document. Gson-friendly public fields. */
class TrackDoc {
    /** Human-readable track name (also the file name). */
    var name: String = "untitled"

    /** Gate list in lap order; index 0 is start/finish. */
    var gates: MutableList<GateDef> = mutableListOf()

    /** Fallback opening size when a gate omits it (always persisted per-gate here). */
    var defaultWidth: Float = 3f
    var defaultHeight: Float = 3f

    /** Best lap in milliseconds (0 = no valid lap yet). */
    var bestLapMs: Long = 0L

    /** Recorded best-lap ghost; empty when none. */
    var ghost: MutableList<GhostSample> = mutableListOf()
}
