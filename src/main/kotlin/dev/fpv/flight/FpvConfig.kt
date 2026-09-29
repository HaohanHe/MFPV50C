/*
 * FPV Craft - MIT
 * All tunable parameters, persisted as JSON in the Fabric config directory.
 * Uses Gson (bundled with Minecraft); clean-room values only.
 */
package dev.fpv.flight

import com.google.gson.GsonBuilder
import dev.fpv.client.osd.OsdElement
import dev.fpv.client.osd.OsdLayout
import dev.fpv.input.SlotCalib
import dev.fpv.input.StickSlot
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/** Per-axis actual-rates parameters (published rate-model configurator units). */
data class AxisRates(
    /** Center sensitivity, deg/s (published default 70). */
    var center: Float = Defaults.RATE_CENTER,
    /** Max rate at full stick, deg/s (published default 670). */
    var max: Float = Defaults.RATE_MAX,
    /** Expo 0..1 (published default 0). */
    var expo: Float = Defaults.RATE_EXPO,
)

/** Virtual-pack battery parameters (clean-room; no real divider hardware here). */
class BatteryConfig {
    /** Number of series cells. */
    var cellCount: Int = Defaults.CELL_COUNT_DEFAULT

    /** Pack capacity in mAh; 0 = estimate percentage purely from voltage. */
    var packCapacityMah: Int = Defaults.PACK_CAPACITY_MAH_DEFAULT

    /** Per-cell voltage that raises the OSD warning (published 3.50V). */
    var warningCellV: Float = Defaults.VBAT_WARNING_CELL

    /** Per-cell voltage that raises the OSD critical (published 3.30V). */
    var criticalCellV: Float = Defaults.VBAT_CRITICAL_CELL
}

/** RX-link failsafe parameters. */
class FailsafeConfig {
    /** "DROP" = disarm on link loss; "LAND" = auto Angle descent. */
    var procedure: String = Defaults.FAILSAFE_PROCEDURE_DROP

    /** How long a lost link is held before acting (published 300ms). */
    var holdMs: Long = Defaults.FAILSAFE_HOLD_MS
}

/** Per-axis PID / feed-forward gains in published configurator units. */
class AxisPid(p: Float, i: Float, d: Float, f: Float) {
    var p: Float = p
    var i: Float = i
    var d: Float = d
    var f: Float = f
}

/**
 * Rate-controller (PID) configuration. Defaults reproduce the published
 * factory PID/FF gains and the published TPA / I-term-relax / anti-gravity
 * structure and defaults; clean-room values only.
 */
class PidConfig {
    /** Master switch for the inner PID rate loop; off = commanded rates integrate directly. */
    var enabled = Defaults.PID_ENABLED

    var roll = AxisPid(45f, 80f, 30f, 120f)
    var pitch = AxisPid(47f, 84f, 34f, 125f)
    var yaw = AxisPid(45f, 80f, 0f, 120f)

    /** Published setpoint weight (multiplier on the stick-derived setpoint). */
    var setpointWeight = 1f

    // TPA: attenuate gains as throttle rises.
    /** "D" (published default), "PD", or "OFF". */
    var tpaMode = "D"
    /** Published tpa_rate (65/1000). */
    var tpaRate = 0.65f
    /** Published tpa_breakpoint 1350us expressed as normalized throttle 0.35. */
    var tpaBreakpoint = 0.35f

    // I-term relax.
    /** "RP" (published default), "RPY", or "OFF". */
    var itermRelax = "RP"
    var itermRelaxCutoffHz = 15f
    /** "SETPOINT" (published default) or "GYRO". */
    var itermRelaxType = "SETPOINT"

    // Anti-gravity.
    var antiGravityGain = 80f
    var antiGravityCutoffHz = 5f

    /** Published integrator limit in internal units. */
    var itermLimit = 400f

    /** D-term low-pass dynamic range, Hz (published defaults). */
    var dtermLpfMinHz = 75f
    var dtermLpfMaxHz = 150f

    /** Crash recovery (published default OFF; fully functional when enabled). */
    var crashRecovery = false
}

/** Throttle limiter: OFF (default), SCALE (published scale) or CLIP (published limit). */
class ThrottleLimitConfig {
    var type = "OFF"
    var percent = 100f
}

/** Racing core parameters (pure client, singleplayer-oriented).
 *  F9U competition-format knobs transcribed from the researched rule set; see
 *  dev.fpv.race.F9URules for source clauses. New fields default via Gson; old
 *  configs are repaired by FpvConfig.migrate(). */
class RaceConfig {
    /** Default gate opening size, blocks (1 m ~= 1 block). */
    var gateWidth = 3f
    var gateHeight = 3f

    /** Minimum lap time used to debounce spurious gate re-crossings, ms. */
    var minLapDebounceMs = 2000L

    /** Replay the local best-lap ghost (Nemesis-style) while flying. */
    var ghostEnabled = true

    // ---- F9U competition format (data-driven; clean-room defaults) ----
    /** Required consecutive laps per completed round. [F9URules REQUIRED_LAPS] */
    var requiredLaps = 3

    /** Per-heat flight time limit once the clock starts, seconds. */
    var timeLimitSec = 180

    /** "TIMING_GATE": clock starts when the timing gate is crossed; "START_SIGNAL": immediately. */
    var timingTrigger = "TIMING_GATE"

    /** "AVG_BEST_3_LAPS" or "BEST_FULL_ROUND". */
    var rankingMethod = "AVG_BEST_3_LAPS"

    /** Abandon / incomplete-task penalty, seconds. */
    var penaltySec = 30

    /** Require return to the landing zone for a valid result. */
    var requireLandingZone = true

    /** Enable per-gate sector splits. */
    var sectorsEnabled = true
}

class FpvConfig {
    /** Master switch for FPV mode. */
    var enabled = true

    // Acro rates
    var roll = AxisRates()
    var pitch = AxisRates()
    var yaw = AxisRates()

    /** Mouse delta (pixels per frame) -> stick deflection gain (fallback input). */
    var mouseSensitivity = Defaults.MOUSE_SENSITIVITY

    /** Throttle when no forward key held (fallback input). */
    var idleThrottle = Defaults.IDLE_THROTTLE

    // Translational physics (applied client-side)
    /** Forward thrust acceleration at full throttle. */
    var thrustPower = Defaults.THRUST_POWER

    /** Quadratic drag coefficient: decel = dragK * v^2. */
    var dragK = Defaults.DRAG_K

    // ---- Input layer ----
    /**
     * Prefer a USB radio. A radio is the primary input; when it is absent the
     * input manager transparently falls back to keyboard/mouse.
     */
    var useRadio = true

    /** GLFW joystick id; -1 = first present device. */
    var joystickId = -1

    /** Transmitter hand layout 1..4, default Mode 2. */
    var handMode = 2

    /**
     * Calibration per physical stick slot, indexed by StickSlot.ordinal.
     * Every slot starts unbound (axisIndex = -1); only calibration or explicit
     * user selection writes a binding - no axis order is ever assumed.
     */
    var slotCalib: MutableList<SlotCalib> = defaultSlots()

    // ---- Flight modes ----
    var flightMode: FlightMode = FlightMode.ACRO

    /** Reversible-3D: motors can reverse, throttle center = zero. */
    var reversible3D = false

    /** Maximum self-level inclination, degrees. */
    var angleMaxDeg = Defaults.ANGLE_MAX_DEG

    /** Angle-mode proportional gain (engineering starting value). */
    var angleP = Defaults.ANGLE_P

    /**
     * Angle-mode damping gain on measured body rate (engineering starting
     * value; 0 = pure proportional).
     */
    var angleD = Defaults.ANGLE_D

    /** Throttle center deadband in reversible-3D mode (normalized units). */
    var threeDThrottleDeadband = Defaults.THREE_D_THROTTLE_DEADBAND

    /** Raw aux axis used to cycle flight modes; -1 = off. */
    var modeSwitchAxis = -1

    /** Raw aux axis used as the arm switch; -1 = off. */
    var armSwitchAxis = -1

    // ---- Setpoint (RC) smoothing ----
    var setpointSmoothingEnabled = Defaults.SETPOINT_SMOOTHING_ENABLED
    /** Third-order lag cutoff on roll/pitch/yaw sticks, Hz. */
    var setpointCutoffHz = Defaults.SETPOINT_CUTOFF_HZ

    // ---- Throttle curve + boost ----
    /** Throttle curve midpoint, percent (published thrMid8 = 50). */
    var thrMidPct = Defaults.THR_MID_PCT
    /** Throttle curve expo, percent (published thrExpo8 = 0). */
    var thrExpoPct = Defaults.THR_EXPO_PCT
    var throttleBoostEnabled = Defaults.THROTTLE_BOOST_ENABLED
    /** Boost gain; engineering equivalent of published throttle_boost=5. */
    var boostGain = Defaults.THROTTLE_BOOST_GAIN
    var boostCutoffHz = Defaults.THROTTLE_BOOST_CUTOFF_HZ

    // ---- Reversible-3D neutral offset (normalized; 0 = exact center) ----
    var neutral3d = Defaults.NEUTRAL_3D

    // ---- Headfree ----
    /** When on, roll/pitch sticks are interpreted relative to the heading latched at enable. */
    var headfreeEnabled = Defaults.HEADFREE_ENABLED

    // ---- Telemetry logger ----
    var telemetryEnabled = Defaults.TELEMETRY_ENABLED

    // ---- Nested subsystems (may be absent in older JSON -> repaired in migrate()). ----
    var battery: BatteryConfig? = BatteryConfig()
    var failsafe: FailsafeConfig? = FailsafeConfig()
    var pid: PidConfig? = PidConfig()
    var throttleLimit: ThrottleLimitConfig? = ThrottleLimitConfig()
    var race: RaceConfig? = RaceConfig()

    /**
     * Persisted OSD layout (enabled flags + pixel positions). The OSD editor
     * mutates this list and saves; only center-anchored elements ignore x/y.
     */
    var osdElements: MutableList<OsdElement> = OsdLayout.defaultLayout()

    /** Raw aux axis for heading-adjust (re-center headfree heading); -1 = off. */
    var headAdjustAxis = -1

    // ---- Translational motion multiplayer gating ----
    /** Master enable for client-side thrust/drag translation. */
    var translationEnhance = Defaults.TRANSLATION_ENHANCE
    /** Allow the translational physics on multiplayer servers (off by default: safety). */
    var allowTranslationMultiplayer = Defaults.ALLOW_TRANSLATION_MULTIPLAYER

    /** Calibration for the slot of logical [channel] under the current hand mode. */
    fun calibFor(channel: Int): SlotCalib =
        slotCalib[dev.fpv.input.HandLayout.slot(handMode, channel).ordinal]

    /** True when every physical slot has a raw axis binding. */
    fun isCalibrated(): Boolean = slotCalib.all { it.axisIndex >= 0 }

    /** Repair missing/short calibration data after loading older JSON. */
    private fun migrate() {
        if (slotCalib.size != StickSlot.entries.size) slotCalib = defaultSlots()
        // Older configs predate the nested battery/failsafe blocks; fill defaults.
        if (battery == null) battery = BatteryConfig()
        if (failsafe == null) failsafe = FailsafeConfig()
        if (pid == null) pid = PidConfig()
        if (throttleLimit == null) throttleLimit = ThrottleLimitConfig()
        if (race == null) race = RaceConfig()
        if (osdElements.isEmpty()) osdElements = OsdLayout.defaultLayout()
    }

    // ---- Persistence ----
    fun save() {
        Files.createDirectories(configPath().parent)
        Files.writeString(configPath(), gson.toJson(this))
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        private fun defaultSlots(): MutableList<SlotCalib> =
            StickSlot.entries.map { SlotCalib() }.toMutableList()

        private fun configPath(): Path =
            FabricLoader.getInstance().configDir.resolve("fpvcraft.json")

        fun load(): FpvConfig = try {
            val p = configPath()
            if (Files.exists(p))
                (gson.fromJson(Files.readString(p), FpvConfig::class.java) ?: FpvConfig())
                    .also { it.migrate() }
            else FpvConfig()
        } catch (e: Exception) {
            FpvConfig()
        }
    }
}
