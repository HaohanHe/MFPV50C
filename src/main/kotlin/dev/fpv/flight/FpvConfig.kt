/*
 * FPV Craft - MIT
 * All tunable parameters, persisted as JSON in the Fabric config directory.
 * Uses Gson (bundled with Minecraft); clean-room values only.
 */
package dev.fpv.flight

import com.google.gson.GsonBuilder
import dev.fpv.client.osd.OsdElement
import dev.fpv.client.osd.OsdLayout
import dev.fpv.input.AuxChannel
import dev.fpv.input.DeviceProfile
import dev.fpv.input.KnownDevices
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

    fun copy(): BatteryConfig = BatteryConfig().also {
        it.cellCount = cellCount
        it.packCapacityMah = packCapacityMah
        it.warningCellV = warningCellV
        it.criticalCellV = criticalCellV
    }
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
    /**
     * Master switch for ALL race logic (gate detection / lap timing /
     * time limit / landing-zone validation / ghost / gate rendering / HUD).
     * Default false: first flight is plain single-player freestyle (ACRO),
     * nothing race-related activates and no race calibration is required.
     * Race data structures stay fully intact; turn this on to race.
     */
    var raceEnabled: Boolean = false

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

/** Recording + playback knobs for the data-driven replay system (dev.fpv.replay). */
class ReplayConfig {
    /** "FIXED" = decimate to [sampleRateHz]; "FRAME" = write every rendered frame. */
    var recordingMode: String = "FIXED"

    /** Fixed sampling rate when recordingMode = FIXED (Hz, 25..240). */
    var sampleRateHz: Float = 120f

    /** Raw AUX axis that toggles recording (rising edge); -1 = keyboard/GUI only. */
    var recordAuxAxis: Int = -1

    /** Last transport speed chosen in the replay screen (0.25..4). */
    var lastSpeed: Float = 1f

    /** "FPV", "CHASE" or "FREE". */
    var lastView: String = "FPV"
}

/** Offline cinematic export knobs (dev.fpv.replay.CinematicExport). */
class ExportConfig {
    /** "16:9", "9:16", "2.35:1", "2.39:1", "21:9". */
    var aspect: String = "2.39:1"

    /** "720p", "1080p", "1440p", "2160p" canvas height family. */
    var resolution: String = "1080p"

    /** Output frames per second. */
    var fps: Int = 24

    /** Motion-blur sub-samples per output frame (4..8; 180-degree shutter). */
    var motionBlurSamples: Int = 4

    /** Shutter angle in degrees; 180 = classic half-frame exposure. */
    var shutterAngleDeg: Float = 180f

    /** Bake black bars to the target aspect in the offscreen buffer. */
    var letterbox: Boolean = true

    /** "mp4" (libx264/yuv420p) or "mkv". */
    var container: String = "mp4"

    /** Empty = look up "ffmpeg" on PATH; otherwise an absolute executable path. */
    var ffmpegPath: String = ""

    /** Chase camera follow distance, blocks. */
    var chaseDistance: Float = 2.5f
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

    /** Raw HID button used as the arm switch; -1 = off (takes precedence over axis). */
    var armButtonIndex = -1

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
    var replay: ReplayConfig? = ReplayConfig()
    var export: ExportConfig? = ExportConfig()

    // ---- Airframe (machine) profiles: data-driven physics ----
    /**
     * All tunable machine profiles. The active one is consumed by the real
     * translational + rotational physics; see AirframeProfile for the field
     * -> consumer map. Built-ins: a balanced freestyle starting point and an
     * F9U-class reference profile constrained by the F9U hardware rules.
     */
    var airframes: MutableList<AirframeProfile> = defaultAirframes()

    /** Name of the profile currently driving physics. */
    var activeAirframeName: String = airframes.first().name

    /**
     * Persisted OSD layout (enabled flags + pixel positions). The OSD editor
     * mutates this list and saves; only center-anchored elements ignore x/y.
     */
    var osdElements: MutableList<OsdElement> = OsdLayout.defaultLayout()

    /** Raw aux axis for heading-adjust (re-center headfree heading); -1 = off. */
    var headAdjustAxis = -1

    /**
     * Data-driven AUX channels beyond the 4 gimbals (sliders/dials/knobs and
     * grouped multi-position switches). Grows at runtime as the USB device
     * exposes more axes/buttons; persisted so user names/bindings survive.
     */
    var auxChannels: MutableList<AuxChannel> = mutableListOf()

    // ---- Per-device control-mapping profiles ----
    /** All persisted transmitter profiles (one per device fingerprint). */
    var profiles: MutableList<DeviceProfile> = mutableListOf()

    /** Fingerprint of the currently attached device ("" = not yet attached). */
    var activeProfileFingerprint: String = ""

    /**
     * Attach the profile matching [fingerprint], creating one on first sight of
     * the device, and point the live gimbal/aux/hand-mode lists at it. Call when
     * the USB radio appears. No raw index is valid across devices, so mappings
     * never leak between transmitters.
     */
    fun attachProfileForDevice(fingerprint: String, deviceName: String): DeviceProfile {
        var p = profiles.firstOrNull { it.fingerprint == fingerprint }
        if (p == null) {
            p = DeviceProfile(fingerprint = fingerprint, modelName = deviceName, handMode = handMode)
            // Known transmitter: pre-fill its layout from the data table, once.
            KnownDevices.match(deviceName)?.let { KnownDevices.apply(p, it) }
            profiles.add(p)
        }
        // Backward compatibility: an older profile that armed via a raw button
        // but predates the modes table gets the equivalent ARM routing row.
        if (p.modes.isEmpty() && p.armButton >= 0) {
            p.modes += ModeBinding(
                function = FlightFunction.ARM.id,
                sourceKind = "BUTTON",
                sourceIndex = p.armButton,
                activeLow = Defaults.SWITCH_ACTIVE_HIGH_LOW,
                activeHigh = 1f,
            )
        }
        activeProfileFingerprint = fingerprint
        slotCalib = p.gimbal
        auxChannels = p.aux
        handMode = p.handMode
        armButtonIndex = p.armButton
        return p
    }

    /** The active device profile, or null when no device is attached. */
    fun activeProfile(): DeviceProfile? =
        profiles.firstOrNull { it.fingerprint == activeProfileFingerprint }

    /**
     * True when arm state is driven automatically (a modes-table ARM binding, a
     * raw arm button, or a raw arm axis). When false the keyboard arm key
     * toggles arm state.
     */
    fun armBindingAutomatic(): Boolean {
        val byModes = activeProfile()?.modes?.any {
            it.function == FlightFunction.ARM.id
        } ?: false
        return byModes || armButtonIndex >= 0 || armSwitchAxis >= 0
    }

    /** True when the current device has no completed move-to-bind yet. */
    fun needsBinding(): Boolean = slotCalib.none { it.learned }

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

    // ---- Airframe profile access ----
    /** The profile driving physics right now (falls back to the first one). */
    fun activeAirframe(): AirframeProfile =
        airframes.firstOrNull { it.name == activeAirframeName } ?: airframes.first()

    /** Battery to use: the active airframe's pack if it defines one, else the shared pack. */
    fun activeBattery(): BatteryConfig = activeAirframe().battery ?: (battery ?: BatteryConfig())

    /** Cycle to the next profile in the list (wraps). */
    fun cycleAirframe() {
        val i = airframes.indexOfFirst { it.name == activeAirframeName }
        if (airframes.isEmpty()) return
        activeAirframeName = airframes[(i + 1).mod(airframes.size)].name
        applyPidBehavior()
    }

    /**
     * Apply the active profile's pidBehavior as a one-shot rate preset.
     * "PERFECT"/"" leaves current rates untouched; otherwise it names a
     * TuningPreset (racing/beginner/cinematic) applied via the existing table.
     */
    fun applyPidBehavior() {
        val preset = TuningPreset.byId(activeAirframe().pidBehavior) ?: return
        preset.apply(this)
    }

    /** Create an empty profile copied from the active one and activate it. */
    fun copyActiveAirframe(): AirframeProfile {
        val src = activeAirframe()
        val n = airframes.count { it.name.startsWith(src.name) }
        val copy = src.copy().apply { name = src.name + " " + (if (n == 0) "copy" else "copy$n"); battery = src.battery?.copy() }
        airframes.add(copy)
        activeAirframeName = copy.name
        return copy
    }

    /** Create a fresh default-style profile. */
    fun newAirframe(): AirframeProfile {
        val p = AirframeProfile().apply { name = "Custom " + (airframes.size + 1) }
        airframes.add(p)
        activeAirframeName = p.name
        return p
    }

    /** Delete the active profile (never the last one). */
    fun deleteActiveAirframe() {
        if (airframes.size <= 1) return
        val i = airframes.indexOfFirst { it.name == activeAirframeName }
        if (i < 0) return
        airframes.removeAt(i)
        activeAirframeName = airframes.first().name
    }

    /** Repair missing/short calibration data after loading older JSON. */
    private fun migrate() {
        if (slotCalib.size != StickSlot.entries.size) slotCalib = defaultSlots()
        // Older configs predate the nested battery/failsafe blocks; fill defaults.
        if (battery == null) battery = BatteryConfig()
        if (failsafe == null) failsafe = FailsafeConfig()
        if (pid == null) pid = PidConfig()
        if (throttleLimit == null) throttleLimit = ThrottleLimitConfig()
        if (race == null) race = RaceConfig()
        if (replay == null) replay = ReplayConfig()
        if (export == null) export = ExportConfig()
        if (osdElements.isEmpty()) osdElements = OsdLayout.defaultLayout()
        // Older configs predate data-driven airframes.
        if (airframes.isEmpty()) airframes = defaultAirframes()
        if (airframes.none { it.name == activeAirframeName })
            activeAirframeName = airframes.first().name
        // auxChannels and device profiles have non-null initializers; Gson
        // leaves them empty for older configs predating the data-driven lists.
    }

    // ---- Persistence ----
    fun save() {
        // Keep the active profile's hand-mode/arm in sync with the live setting.
        activeProfile()?.let {
            it.handMode = handMode
            it.armButton = armButtonIndex
        }
        Files.createDirectories(configPath().parent)
        Files.writeString(configPath(), gson.toJson(this))
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        private fun defaultSlots(): MutableList<SlotCalib> =
            StickSlot.entries.map { SlotCalib() }.toMutableList()

        /**
         * Built-in machine profiles. The first one is the default freestyle
         * ship (engineering starting values; calibrated so full-throttle thrust
         * accel + quadratic drag reproduce the legacy THRUST_POWER / DRAG_K
         * feel). The second is an "F9U reference" ship whose hard limits come
         * straight from the F9U rule book (see H_hardware in data-f9u-rules.json).
         */
        private fun defaultAirframes(): MutableList<AirframeProfile> {
            val freestyle = AirframeProfile().apply {
                name = "Freestyle 5in"
                comment = "Balanced freestyle starting point. Engineering values, not an OEM default; tuned so t=1 thrust accel ~= legacy THRUST_POWER=1.1 blocks/tick and dragK=0.015."
                massKg = 0.65f
                inertiaXX = 0.0022f; inertiaYY = 0.0035f; inertiaZZ = 0.0018f
                motorCount = 4; maxThrustPerMotorN = 35.0f
                thrustLinear = 1.0f; thrustQuad = 0.0f
                propInch = 5.1f; propPitch = 4.6f; motorKv = 1900
                cameraTiltDeg = 25f; minThrottle = 0.055f
                thrLow = 0.95f; thrMid = 0.95f; thrHigh = 1.05f
                gravity = 9.81f; instantPower = 0.65f
                airDrag = 0.40f; airGrip = 0.85f; relativeAirspeed = 1.00f
                propwashEnabled = false; pidBehavior = "PERFECT"
                linearDrag = 0.0f; quadraticDrag = 0.015f
                angularDragXX = 0.44f; angularDragYY = 0.70f; angularDragZZ = 0.36f
                cgOffsetX = 0f; cgOffsetY = 0f; cgOffsetZ = 0f
            }
            val f9u = AirframeProfile().apply {
                name = "F9U reference 6in"
                comment = ("Within F9U hardware limits: mass<=1.0kg [FAI C.1.1 / TWG 2.1], " +
                    "prop<=6in [FAI Annex C.1 / H.propeller_max_inches], up to 6S @<=4.25V/cell " +
                    "[FAI C.1.2 / TWG 2.2]. Thrust/inertia/drag are CLASS ESTIMATES, not rulebook " +
                    "numbers -- to be tuned on a real model.")
                massKg = 0.95f
                inertiaXX = 0.0030f; inertiaYY = 0.0045f; inertiaZZ = 0.0025f
                motorCount = 4; maxThrustPerMotorN = 30.0f
                thrustLinear = 1.0f; thrustQuad = 0.05f
                propInch = 6.0f; propPitch = 5.0f; motorKv = 1900; cellCountS = 6
                cameraTiltDeg = 30f; minThrottle = 0.06f
                thrLow = 0.95f; thrMid = 0.95f; thrHigh = 1.05f
                gravity = 9.82f; instantPower = 0.70f
                airDrag = 0.45f; airGrip = 0.85f; relativeAirspeed = 1.00f
                propwashEnabled = false; pidBehavior = "racing"
                linearDrag = 0.0f; quadraticDrag = 0.018f
                angularDragXX = 0.50f; angularDragYY = 0.75f; angularDragZZ = 0.42f
                cgOffsetX = 0f; cgOffsetY = 0f; cgOffsetZ = 0f
                battery = BatteryConfig().apply {
                    cellCount = 6            // F9U allows up to 6S
                    packCapacityMah = 1300
                    warningCellV = 3.50f
                    criticalCellV = 3.30f
                }
            }
            return mutableListOf(freestyle, f9u)
        }

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
