/*
 * FPV Craft - MIT
 * Data-driven OSD element registry + pure formatting layer. This file is
 * deliberately Minecraft-free: it owns the canonical element specs, the frozen
 * telemetry snapshot, unit conversion and the per-element text renderer. Adding a
 * new OSD element is a one-line entry in [OsdElements.REGISTRY] plus (for brand-new
 * visuals) a renderer branch in FpvOsd -- no edits to the layout editor or the
 * persistence layer.
 */
package dev.fpv.flight

/** Unit system for OSD readouts. Global (FpvConfig.osdUnit), overridable per element. */
enum class OsdUnit {
    METRIC, IMPERIAL;

    companion object {
        const val MPS_TO_KMH = 3.6f
        const val KMH_TO_MPH = 0.621371f
        const val M_TO_FT = 3.28084f

        fun parse(s: String?): OsdUnit = if (s == "IMPERIAL") IMPERIAL else METRIC

        /** Horizontal speed in the chosen unit's display unit (km/h or mph). */
        fun speedDisplay(mps: Float, u: OsdUnit): Float = when (u) {
            IMPERIAL -> mps * MPS_TO_KMH * KMH_TO_MPH
            METRIC -> mps * MPS_TO_KMH
        }

        /** Vertical distance in the chosen unit's display unit (m or ft). */
        fun distanceDisplay(m: Float, u: OsdUnit): Float = when (u) {
            IMPERIAL -> m * M_TO_FT
            METRIC -> m
        }
    }
}

/** Renderer type an element uses; FpvOsd dispatches on this. */
enum class OsdRenderer { TEXT, BAR, LADDER, GAUGE, ICON, HORIZON, BANNER }

/** Measured quantity; drives unit conversion + default colour. */
enum class OsdQty {
    SPEED, DISTANCE, VERT_SPEED, VOLTAGE, CURRENT, CHARGE, POWER,
    PERCENT, THROTTLE, ANGLE, TIME, LQ, MODE, TEXT, NONE,
}

/** Default OSD text colour category (mapped to ARGB in FpvOsd). */
enum class OsdColor { GREEN, YELLOW, RED, CYAN, GRAY }

/**
 * One OSD element's immutable spec. The live, per-user position/enabled/unit
 * override lives in `OsdElement` (persisted); this is the canonical definition.
 */
data class OsdElementSpec(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val renderer: OsdRenderer,
    val qty: OsdQty,
    val centerAnchored: Boolean,
    val defaultEnabled: Boolean,
    val defaultX: Int,
    val defaultY: Int,
    val color: OsdColor = OsdColor.GREEN,
)

/** Frozen, read-only telemetry snapshot the OSD formats from. Filled by FpvOsd. */
data class OsdTelemetry @JvmOverloads constructor(
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val targetRollDeg: Float = 0f,
    val targetPitchDeg: Float = 0f,
    // motion / position (virtual model mapping: ground speed = body ground speed,
    // coords = world X/Z, altitude = Y, vario = vertical speed, home = unlock point)
    val groundSpeedMps: Float = 0f,
    val altitudeM: Float = 0f,
    val varioMps: Float = 0f,
    val worldX: Float = 0f,
    val worldZ: Float = 0f,
    val homeX: Float = 0f,
    val homeZ: Float = 0f,
    // battery model
    val vbat: Float = 0f,
    val perCell: Float = 0f,
    val percent: Float = 0f,
    val currentA: Float = 0f,
    val mAhDrawn: Float = 0f,
    // rotor model (ESC rpm = motor model; per-motor % = mixer output)
    val motorRpm: FloatArray = FloatArray(0),
    val mixerOut: FloatArray = FloatArray(0),
    // link / control / mode
    val lq: Float = 100f,
    val throttle: Float = 0f,
    val reversible3D: Boolean = false,
    val headfree: Boolean = false,
    val mode: String = "ACRO",
    val armed: Boolean = false,
    val flightTimeSec: Float = 0f,
    val batteryStage: BatteryStage = BatteryStage.OK,
    val linkFailsafe: Boolean = false,
    val linkHold: Boolean = false,
    // ---- appended (all defaulted; preserves existing positional construction) ----
    val headingDeg: Float = 0f,            // 0..359 world yaw
    val hasHome: Boolean = false,
    val homeBearingDeg: Float = 0f,         // compass bearing to home
    val homeDistM: Float = 0f,
    var lapCurrentMs: Long = 0L,
    var lapBestMs: Long = 0L,
    val remainingTimeSec: Float = 0f,       // estimate (percent / discharge rate)
    val efficiency: Float = 0f,            // km/h per A (model-derived)
    val craftName: String = "",
    val pilotName: String = "",
) {
    /** Electrical power W = V * A (virtual battery model). */
    val watts: Float get() = vbat * currentA

    // Test-friendly / Java-friendly copy helpers (return a modified snapshot).
    fun withVario(v: Float): OsdTelemetry = copy(varioMps = v)
    fun withHeading(h: Float): OsdTelemetry = copy(headingDeg = h)
    fun withHome(bearing: Float, dist: Float): OsdTelemetry =
        copy(hasHome = true, homeBearingDeg = bearing, homeDistM = dist)
    fun withNoHome(): OsdTelemetry = copy(hasHome = false)
    fun withRemaining(sec: Float): OsdTelemetry = copy(remainingTimeSec = sec)
    fun withEfficiency(e: Float): OsdTelemetry = copy(efficiency = e)
}

/** Canonical registry of every OSD element. Add new elements here only. */
object OsdElements {

    // Stable ids (kept identical to the persisted layout so old configs still load).
    const val CROSSHAIR = "crosshair"
    const val ARTIFICIAL_HORIZON = "artificial_horizon"
    const val HORIZON_SIDEBARS = "horizon_sidebars"
    const val SPEED = "speed"
    const val THROTTLE = "throttle"
    const val MODE = "mode"
    const val TARGET = "target"
    const val BATTERY = "battery"
    const val LQ = "lq"
    const val FLIGHT_TIMER = "flight_timer"
    const val ATTITUDE = "attitude"
    const val CURRENT = "current"
    const val MAH_DRAWN = "mah_drawn"
    const val POWER = "power"               // electrical power W
    const val CENTER_WARNING = "center_warning"

    // ---- B2 batch (real telemetry; model-derived ones flagged in label/desc) ----
    const val VARIO = "vario"               // vertical speed m/s(ft/s) + arrow
    const val HEADING = "heading"           // 0-359 + compass letter
    const val ALTITUDE = "altitude"         // height m(ft) = world Y
    const val SPEED_BAR = "speed_bar"       // Uncrashed-style speed side bar
    const val ALTITUDE_BAR = "altitude_bar" // Uncrashed-style altitude side bar
    const val MOTOR_DIAG = "motor_diag"     // 4 per-motor % (signed in 3D)
    const val REMAINING_TIME = "remaining_time" // battery % / discharge rate estimate
    const val ESC_RPM = "esc_rpm"           // model-derived rpm
    const val COMPASS_BAR = "compass_bar"   // heading ladder
    const val HOME = "home"                 // home bearing arrow + distance
    const val EFFICIENCY = "efficiency"     // km/h per A (model-derived)
    const val TIMER2 = "timer2"             // second / total timer
    const val LAP_TIME = "lap_time"         // current + best lap (race)
    const val CRAFT_NAME = "craft_name"     // static text

    /** Degrees-per-second -> m/s helpers live on [OsdUnit]; compass rose letters. */
    fun compassLetter(deg: Float): String = when (((deg % 360f) + 360f) % 360f) {
        in 337.5f..360f, in 0f..22.5f -> "N"
        in 22.5f..67.5f -> "NE"
        in 67.5f..112.5f -> "E"
        in 112.5f..157.5f -> "SE"
        in 157.5f..202.5f -> "S"
        in 202.5f..247.5f -> "SW"
        in 247.5f..292.5f -> "W"
        else -> "NW"
    }

    val REGISTRY: List<OsdElementSpec> = listOf(
        OsdElementSpec(CROSSHAIR, "准星", "Crosshair", OsdRenderer.ICON, OsdQty.NONE,
            centerAnchored = true, defaultEnabled = true, 0, 0, OsdColor.YELLOW),
        OsdElementSpec(ARTIFICIAL_HORIZON, "人工地平", "Horizon", OsdRenderer.HORIZON, OsdQty.NONE,
            centerAnchored = true, defaultEnabled = true, 0, 0, OsdColor.GREEN),
        OsdElementSpec(HORIZON_SIDEBARS, "侧杆", "Sidebars", OsdRenderer.ICON, OsdQty.NONE,
            centerAnchored = true, defaultEnabled = true, 0, 0, OsdColor.GREEN),
        OsdElementSpec(MODE, "模式", "Mode", OsdRenderer.BANNER, OsdQty.MODE,
            centerAnchored = true, defaultEnabled = true, 0, 8, OsdColor.GREEN),
        OsdElementSpec(CENTER_WARNING, "中央警告", "CenterWarn", OsdRenderer.BANNER, OsdQty.NONE,
            centerAnchored = true, defaultEnabled = true, 0, 0, OsdColor.RED),
        OsdElementSpec(SPEED, "速度", "Speed", OsdRenderer.TEXT, OsdQty.SPEED,
            centerAnchored = false, defaultEnabled = true, 8, 8, OsdColor.GREEN),
        OsdElementSpec(TARGET, "目标角", "Target", OsdRenderer.TEXT, OsdQty.ANGLE,
            centerAnchored = false, defaultEnabled = true, 8, 20, OsdColor.CYAN),
        OsdElementSpec(BATTERY, "电池", "Battery", OsdRenderer.TEXT, OsdQty.VOLTAGE,
            centerAnchored = false, defaultEnabled = true, 8, 32, OsdColor.GREEN),
        OsdElementSpec(LQ, "链路质量", "LinkQuality", OsdRenderer.TEXT, OsdQty.LQ,
            centerAnchored = false, defaultEnabled = true, 8, 44, OsdColor.GREEN),
        OsdElementSpec(FLIGHT_TIMER, "计时器", "Timer", OsdRenderer.TEXT, OsdQty.TIME,
            centerAnchored = false, defaultEnabled = true, 8, 56, OsdColor.GRAY),
        OsdElementSpec(THROTTLE, "油门", "Throttle", OsdRenderer.TEXT, OsdQty.THROTTLE,
            centerAnchored = false, defaultEnabled = true, 8, 68, OsdColor.GREEN),
        OsdElementSpec(ATTITUDE, "姿态角", "Attitude", OsdRenderer.TEXT, OsdQty.ANGLE,
            centerAnchored = false, defaultEnabled = false, 8, 80, OsdColor.CYAN),
        OsdElementSpec(CURRENT, "电流", "Current", OsdRenderer.TEXT, OsdQty.CURRENT,
            centerAnchored = false, defaultEnabled = false, 8, 92, OsdColor.GREEN),
        OsdElementSpec(MAH_DRAWN, "电量消耗", "MahDrawn", OsdRenderer.TEXT, OsdQty.CHARGE,
            centerAnchored = false, defaultEnabled = false, 8, 104, OsdColor.GREEN),
        OsdElementSpec(POWER, "功率", "Power", OsdRenderer.TEXT, OsdQty.POWER,
            centerAnchored = false, defaultEnabled = false, 8, 116, OsdColor.GREEN),
        // ---- B2 batch ----
        OsdElementSpec(VARIO, "垂直速度", "Vario", OsdRenderer.TEXT, OsdQty.VERT_SPEED,
            centerAnchored = false, defaultEnabled = false, 8, 128, OsdColor.CYAN),
        OsdElementSpec(HEADING, "航向", "Heading", OsdRenderer.TEXT, OsdQty.ANGLE,
            centerAnchored = false, defaultEnabled = false, 8, 140, OsdColor.CYAN),
        OsdElementSpec(ALTITUDE, "高度", "Altitude", OsdRenderer.TEXT, OsdQty.DISTANCE,
            centerAnchored = false, defaultEnabled = false, 8, 152, OsdColor.CYAN),
        OsdElementSpec(SPEED_BAR, "速度侧条", "SpeedBar", OsdRenderer.BAR, OsdQty.SPEED,
            centerAnchored = false, defaultEnabled = false, 4, 4, OsdColor.GREEN),
        OsdElementSpec(ALTITUDE_BAR, "高度侧条", "AltitudeBar", OsdRenderer.BAR, OsdQty.DISTANCE,
            centerAnchored = false, defaultEnabled = false, swSide(), 60, OsdColor.CYAN),
        OsdElementSpec(MOTOR_DIAG, "电机输出", "MotorDiag", OsdRenderer.BAR, OsdQty.PERCENT,
            centerAnchored = false, defaultEnabled = false, 8, 164, OsdColor.GREEN),
        OsdElementSpec(REMAINING_TIME, "剩余时间", "RemainTime", OsdRenderer.TEXT, OsdQty.TIME,
            centerAnchored = false, defaultEnabled = false, 8, 176, OsdColor.YELLOW),
        OsdElementSpec(ESC_RPM, "电调转速*", "EscRpm", OsdRenderer.TEXT, OsdQty.PERCENT,
            centerAnchored = false, defaultEnabled = false, 8, 188, OsdColor.GRAY),
        OsdElementSpec(COMPASS_BAR, "罗盘条", "CompassBar", OsdRenderer.LADDER, OsdQty.ANGLE,
            centerAnchored = false, defaultEnabled = false, 0, 20, OsdColor.CYAN),
        OsdElementSpec(HOME, "返航", "Home", OsdRenderer.TEXT, OsdQty.DISTANCE,
            centerAnchored = false, defaultEnabled = false, 8, 200, OsdColor.GREEN),
        OsdElementSpec(EFFICIENCY, "效率*", "Efficiency", OsdRenderer.TEXT, OsdQty.TEXT,
            centerAnchored = false, defaultEnabled = false, 8, 212, OsdColor.GRAY),
        OsdElementSpec(TIMER2, "计时2", "Timer2", OsdRenderer.TEXT, OsdQty.TIME,
            centerAnchored = false, defaultEnabled = false, 8, 224, OsdColor.GRAY),
        OsdElementSpec(LAP_TIME, "圈速", "LapTime", OsdRenderer.TEXT, OsdQty.TIME,
            centerAnchored = false, defaultEnabled = false, 8, 236, OsdColor.CYAN),
        OsdElementSpec(CRAFT_NAME, "机型名", "CraftName", OsdRenderer.TEXT, OsdQty.TEXT,
            centerAnchored = false, defaultEnabled = false, 8, 248, OsdColor.GRAY),
    )

    /** Right-side bar anchor x (guiScaled px); negative = off the right edge. */
    private fun swSide(): Int = -4

    fun byId(id: String): OsdElementSpec? = REGISTRY.firstOrNull { it.id == id }
}

/**
 * Pure text renderer: given an element spec, the frozen telemetry and the unit
 * system, produce the exact on-screen string (or null for elements that draw no
 * text). No Minecraft types here, so this is headless-testable.
 */
object OsdFormatter {

    /** @return null when the element draws no string; otherwise the formatted text. */
    fun text(spec: OsdElementSpec, tel: OsdTelemetry, unit: OsdUnit): String? {
        return when (spec.id) {
            OsdElements.SPEED -> {
                val v = OsdUnit.speedDisplay(tel.groundSpeedMps, unit)
                val u = if (unit == OsdUnit.IMPERIAL) "mph" else "km/h"
                String.format("%.0f %s", v, u)
            }
            OsdElements.TARGET ->
                String.format("TGT P %+4.0f R %+4.0f", tel.targetPitchDeg, tel.targetRollDeg)
            OsdElements.BATTERY ->
                String.format("BAT %.1fV (%.2f/c) %3.0f%%", tel.vbat, tel.perCell, tel.percent)
            OsdElements.LQ -> "LQ(v) ${tel.lq.toInt()}%"
            OsdElements.FLIGHT_TIMER -> {
                val s = tel.flightTimeSec.toInt()
                String.format("T+%d:%02d", s / 60, s % 60)
            }
            OsdElements.THROTTLE ->
                if (tel.reversible3D) String.format("THR %+3d%%", (tel.throttle * 100).toInt())
                else String.format("THR %3d%%", (tel.throttle * 100).toInt())
            OsdElements.ATTITUDE ->
                String.format("P %+3.0f R %+3.0f", tel.pitchDeg, tel.rollDeg)
            OsdElements.CURRENT -> String.format("CUR %4.1fA", tel.currentA)
            OsdElements.MAH_DRAWN -> String.format("MAH %4.0f", tel.mAhDrawn)
            OsdElements.POWER -> String.format("PWR %4.0fW", tel.watts)
            OsdElements.VARIO -> {
                val arrow = if (tel.varioMps > 0.5f) "▲" else if (tel.varioMps < -0.5f) "▼" else "|"
                val v = if (unit == OsdUnit.IMPERIAL) tel.varioMps * 196.85f else tel.varioMps
                val u = if (unit == OsdUnit.IMPERIAL) "f/s" else "m/s"
                String.format("V+%s %4.1f%s", arrow, v, u)
            }
            OsdElements.HEADING ->
                String.format("%03d%s", ((tel.headingDeg % 360f + 360f) % 360f).toInt(), OsdElements.compassLetter(tel.headingDeg))
            OsdElements.ALTITUDE -> {
                val v = OsdUnit.distanceDisplay(tel.altitudeM, unit)
                String.format("ALT %4.0f%s", v, if (unit == OsdUnit.IMPERIAL) "ft" else "m")
            }
            OsdElements.REMAINING_TIME -> {
                val s = tel.remainingTimeSec.toInt().coerceAtLeast(0)
                String.format("ETE %d:%02d", s / 60, s % 60)
            }
            OsdElements.ESC_RPM -> String.format("RPM %5.0f", tel.motorRpm.let { if (it.isEmpty()) 0f else it[0] })
            OsdElements.HOME ->
                if (!tel.hasHome) null
                else String.format("HOME %s %4.0f%s", OsdElements.compassLetter(tel.homeBearingDeg),
                    OsdUnit.distanceDisplay(tel.homeDistM, unit), if (unit == OsdUnit.IMPERIAL) "ft" else "m")
            OsdElements.EFFICIENCY ->
                String.format("EFF %.1f km/h·A", tel.efficiency)
            OsdElements.TIMER2 -> {
                val s = tel.flightTimeSec.toInt()
                String.format("T2 %d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            }
            OsdElements.LAP_TIME -> {
                if (tel.lapCurrentMs <= 0L) null
                else String.format("LAP %s", fmtMs(tel.lapCurrentMs))
            }
            OsdElements.CRAFT_NAME ->
                if (tel.craftName.isEmpty()) null else tel.craftName
            else -> null // ICON / HORIZON / BANNER / BAR / LADDER draw via specialised renderers
        }
    }

    /** mm:ss.s from milliseconds. */
    fun fmtMs(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return String.format("%d:%02d.%01d", (t / 60000), (t / 1000) % 60, (t % 1000) / 100)
    }

    /** Effective unit for an element: per-element override, else the global unit. */
    fun effectiveUnit(global: OsdUnit, override: String?): OsdUnit =
        OsdUnit.parse(override ?: global.name)
}
