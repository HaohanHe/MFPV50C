/*
 * FPV Craft - MIT
 * F9URules: read-only rule templates / factory defaults transcribed from the
 * researched F9U rule set (FAI Sporting Code Section 4 Vol F9 Class F9U, TWG 2025,
 * CN2026 nationals, CN2025 open). Every numeric fact carries its source clause in a
 * comment. Course/shape sizes the rules do NOT specify are NOT invented here:
 * gates are parameterised opening boxes (minimum 1.5 m clear) and resizable in the
 * editor. Clean-room: values only, no GPL text.
 *
 * Scale note for the editor: 1 m ~= 1 block, so gate metres == block counts.
 */
package dev.fpv.race

object F9URules {

    // ---- A. competition structure ----
    /** Standard heat = 3 consecutive laps. [FAI2025 k)C.6.3; TWG 6.3; CN2026 5.25.6] */
    const val REQUIRED_LAPS = 3

    /** Per-heat flight time once the clock starts. [FAI2025/TWG 180s; CN2026 qual max 240s] */
    const val TIME_LIMIT_SEC = 180

    /** Optional longer domestic qualification cap (exposed in config). */
    const val TIME_LIMIT_SEC_CN_QUAL = 240

    /** Closed loop only (open loop removed by 2025 proposal). */
    const val CLOSED_LOOP = true

    /** Start line to finish line minimum centreline distance, metres. [FAI C.2] */
    const val START_TO_FINISH_MIN_M = 250

    // ---- B. gate / obstacle minimum apertures ----
    /** To-cross gate / tunnel minimum clear diameter, metres. [FAI Annex C.1 §4.1] */
    const val GATE_MIN_CLEAR_DIAMETER_M = 1.5

    /** To-avoid obstacle minimum free-space diameter, metres. [FAI Annex C.1 §4.2] */
    const val AVOID_MIN_FREE_DIAMETER_M = 2.5

    /** Obstacle must be clearly visible on a standard FPV link at this range, m. [FAI Annex C.1 §4] */
    const val GATE_VISIBILITY_M = 30

    // ---- Gate templates (user picks one when adding a gate) ----
    /**
     * FAI crossing-gate template: opening box parameterised at the 1.5 m minimum
     * clear aperture; the user may enlarge it. Shape RECTANGLE.
     */
    val TEMPLATE_FAI_CROSSING = GateTemplate(
        id = "FAI_CROSS",
        label = "FAI 穿越门 (min 1.5m)",
        shape = GateShape.RECTANGLE,
        defaultWidthM = 1.5f,
        defaultHeightM = 1.5f,
        minWidthM = 1.5f,
        minHeightM = 1.5f,
        source = "FAI Annex C.1 §4.1 (min clear diameter 1.5 m)",
    )

    /**
     * FAI avoid-obstacle template: free space 2.5 m around the (solid) obstacle;
     * rendered as a small marker the pilot flies around. Shape RING marker.
     */
    val TEMPLATE_FAI_AVOID = GateTemplate(
        id = "FAI_AVOID",
        label = "FAI 避障 (free 2.5m)",
        shape = GateShape.RING,
        defaultWidthM = 2.5f,
        defaultHeightM = 2.5f,
        minWidthM = 2.5f,
        minHeightM = 2.5f,
        source = "FAI Annex C.1 §4.2 (min free diameter 2.5 m)",
    )

    /**
     * Domestic arch gate: width 1.6-2.0 x height 1.3-1.5 m; default = midpoint
     * ~1.8 x 1.4. [CN2025 open 3.5.5.1]
     */
    val TEMPLATE_CN_ARCH = GateTemplate(
        id = "CN_ARCH",
        label = "国内拱门 1.8x1.4",
        shape = GateShape.ARCH,
        defaultWidthM = 1.8f,
        defaultHeightM = 1.4f,
        minWidthM = 1.6f,
        minHeightM = 1.3f,
        source = "CN2025 open 3.5.5.1 (w1.6-2.0 x h1.3-1.5)",
    )

    val TEMPLATES = listOf(TEMPLATE_FAI_CROSSING, TEMPLATE_FAI_AVOID, TEMPLATE_CN_ARCH)

    // ---- C. timing ----
    /** Timing resolution, seconds. [CN2026 5.25.5(3)] */
    const val TIMING_RESOLUTION_SEC = 0.01

    // ---- E/F. penalties ----
    /** Abandon / incomplete task penalty, seconds. [CN2025 open 3.5.4] */
    const val ABANDON_PENALTY_SEC = 30

    // ---- Evidence gaps (do NOT invent a fixed gate catalog) ----
    // 方块门/旗门/圆环/隧道 固定尺寸目录在 6 份原文中不存在；门型仅按
    // RECTANGLE/ARCH/RING 渲染与命中，开口 w/h 始终可编辑，不写死目录。
}

/** A selectable gate template from [F9URules]. */
data class GateTemplate(
    val id: String,
    val label: String,
    val shape: GateShape,
    val defaultWidthM: Float,
    val defaultHeightM: Float,
    val minWidthM: Float,
    val minHeightM: Float,
    val source: String,
)

/** Gate opening shape (render + hit-test only; apertures are resizable). */
enum class GateShape {
    /** Rectangular crossing gate (FAI minimum 1.5 m clear). */
    RECTANGLE,

    /** Domestic arch gate (rectangle opening + arched top visual). */
    ARCH,

    /** Avoid-obstacle / ring marker: fly around, free space 2.5 m. */
    RING,
}
