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
    /** Center sensitivity, deg/s (published default 70). Used by ACTUAL/QUICK. */
    var center: Float = Defaults.RATE_CENTER,
    /** Max rate at full stick, deg/s (published default 670). Used by ACTUAL/QUICK. */
    var max: Float = Defaults.RATE_MAX,
    /** Expo 0..1 (published default 0). Used by all three rate types. */
    var expo: Float = Defaults.RATE_EXPO,
    /** Legacy-only rcRate 0.1..2.5 (published default 2.0). Ignored by ACTUAL/QUICK. */
    var rcRate: Float = Defaults.LEGACY_RC_RATE,
    /** Legacy-only superRate 0..1 (published default 0.7). Ignored by ACTUAL/QUICK. */
    var superRate: Float = Defaults.LEGACY_SUPER_RATE,
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

    /** Per-cell internal resistance, ohms (sag = I * cells * this). */
    var internalResistancePerCellOhm: Float = Defaults.CELL_INTERNAL_R_OHM

    fun copy(): BatteryConfig = BatteryConfig().also {
        it.cellCount = cellCount
        it.packCapacityMah = packCapacityMah
        it.warningCellV = warningCellV
        it.criticalCellV = criticalCellV
        it.internalResistancePerCellOhm = internalResistancePerCellOhm
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

/**
 * Remote (vanilla server / Paper / Velocity) flight-compatibility layer.
 *
 * On a real server we cannot freely rewrite velocity (anti-cheat / rubber-band), so we
 * DO NOT cancel travel and DO NOT inject off-vanilla velocity. Instead we map the FPV
 * attitude onto the vanilla player: the nose direction drives vanilla yaw/pitch look,
 * roll becomes a *coordinated-turn* yaw/pitch bias, and throttle is realized with
 * fireworks rockets. Anti-kick tolerates setbacks. This whole block is a compatibility
 * mapping layer, NOT true fixed-wing coordinated flight.
 *
 * Defaults target REMOTE servers: on; single-player / creative keep full physics and
 * are exempt from the soft speed limit.
 */
class ServerCompatConfig {
    /** Master switch. Remote servers default on; single-player may leave it off. */
    var compatEnabled: Boolean = true

    /** Roll -> extra-yaw coordinated-turn bias baked into the vanilla look. */
    var coordinatedTurn: Boolean = true

    /** Gain mapping roll amount (deg) -> extra yaw (deg). Compatibility layer only. */
    var coordTurnGain: Float = 0.5f

    /** Pitch compensation (deg per deg of roll) to offset banked-turn altitude loss. */
    var coordPitchCompensation: Float = 0.3f

    /** Realize throttle with fireworks rockets when fall-flying. */
    var fireworkEnabled: Boolean = true

    /** Throttle (0..1) above which a firework boost is requested. */
    var fireworkThrottleThreshold: Float = 0.25f

    /** Minimum ticks between two firework uses (anti-spam). */
    var fireworkMinIntervalTicks: Int = 20

    /** Rubber-band / setback reaction: accept server position, back off boost/turn. */
    var antiKick: Boolean = true

    /**
     * Absolute-position correction larger than this many blocks between the client
     * prediction and the server packet is classified as a rubber-band setback
     * (normal small corrections / single-player never trip it).
     */
    var setbackThresholdBlocks: Float = Defaults.SETBACK_THRESHOLD_BLOCKS

    /** Minimum ms between two setback reactions (debounce). */
    var setbackCooldownMs: Long = Defaults.SETBACK_COOLDOWN_MS

    /** Cap horizontal speed (remote only; creative / single-player never limited). */
    var softSpeedLimit: Boolean = false

    /** Soft horizontal speed cap, blocks/tick (only when softSpeedLimit && antiKick). */
    var softSpeedLimitBpt: Float = 0.6f
}

/** Recording + playback knobs for the data-driven replay system (dev.fpv.replay). */
class ReplayConfig {
    /** Master switch: when false, the recorder never arms (no samples buffered). */
    var recordingEnabled: Boolean = true

    /** "FIXED" = decimate to [sampleRateHz]; "FRAME" = write every rendered frame. */
    var recordingMode: String = "FIXED"

    /** Fixed sampling rate when recordingMode = FIXED (Hz, 25..240). */
    var sampleRateHz: Float = 120f

    /**
     * Ring-buffer cap on held samples before a stop (bounds memory on long runs;
     * oldest samples dropped). ~4 min at 120 Hz.
     */
    var maxSamples: Int = 30000

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

    /**
     * User-customizable ffmpeg command template. Tokens are substituted:
     * %WIDTH% %HEIGHT% %FPS% %PIXELFMT% %FILENAME%. Default keeps the proven
     * rgb24->libx264/yuv420p CRF18 pipeline. The output filename is sanitized to a
     * whitelist before substitution, so a weird replay name cannot inject flags.
     */
    var ffmpegTemplate: String = "-y -f rawvideo -pix_fmt rgb24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -an -c:v libx264 -preset medium -crf 18 -pix_fmt %PIXELFMT% %FILENAME%"

    /** Draw the recorded RC joystick overlay in a corner of exported frames (default off). */
    var rcOverlay: Boolean = false

    /** Chase camera follow distance, blocks. */
    var chaseDistance: Float = 2.5f
}

/**
 * First-person immersion layer (module 1 optics / module 2 signal / module 3 audio).
 * All coefficients are data-driven; the shaders/audio read these at runtime. Everything is
 * mild-by-default and independently switchable; off == identity / silent.
 */
class ImmersionConfig {
    // ---- module 1: optics (barrel / vignette / chromatic aberration) ----
    /** Master switch for the post-process optics pass. Off == identity (no remap). */
    var opticsEnabled: Boolean = true
    /** Barrel distortion coefficient; negative = wide-angle wide lens feel. -0.4..0.0. */
    var barrelK1: Float = Defaults.OPTICS_BARREL_K1
    /** Secondary radial coefficient; 0 for a mild single-term wide lens. */
    var barrelK2: Float = Defaults.OPTICS_BARREL_K2
    /** Vignette strength 0..1 (edge darkening). */
    var vignette: Float = Defaults.OPTICS_VIGNETTE
    /** Static chromatic-aberration offset at the frame edge. */
    var chromaticAberration: Float = Defaults.OPTICS_CA

    // ---- module 2: signal / video glitch (LQ driven) ----
    /** Master switch for the signal-interference pass. Off == clean picture. */
    var glitchEnabled: Boolean = false
    /** Home distance (blocks) at which LQ has fallen to ~50% (clean near, glitch far). */
    var glitchHalfDistBlocks: Float = Defaults.GLITCH_HALF_DIST
    /** Extra random fuzz added on top of the deterministic distance loss. */
    var glitchNoise: Float = Defaults.GLITCH_NOISE

    // ---- module 3: audio ----
    /** Master switch for motor whine + beeps. */
    var audioEnabled: Boolean = true
    /** Motor whine base pitch at idle. */
    var motorWhineBasePitch: Float = Defaults.WHINE_BASE_PITCH
    /** Motor whine pitch multiplier at full throttle. */
    var motorWhineFullPitch: Float = Defaults.WHINE_FULL_PITCH
    /** Master beep volume 0..1. */
    var beepVolume: Float = 0.6f
}

class FpvConfig {
    /** Schema version of the persisted file (used to select migration / detect old saves). */
    var schemaVersion = Defaults.CONFIG_SCHEMA_VERSION

    /** Master switch for FPV mode. */
    var enabled = true

    // Acro rates
    var roll = AxisRates()
    var pitch = AxisRates()
    var yaw = AxisRates()

    /**
     * Stick-to-rate model: ACTUAL (published actual rates, default), LEGACY
     * (Betaflight rcRate/superRate) or QUICK (RaceFlight-style). Consumed by the
     * ACRO setpoint map; ANGLE/HORIZON wrap the same map via AngleController.
     */
    var rateType: String = Defaults.RATES_TYPE_ACTUAL

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

    /** Remote-server compatibility layer (look mapping / fireworks / anti-kick). */
    var serverCompat: ServerCompatConfig? = ServerCompatConfig()
    var replay: ReplayConfig? = ReplayConfig()
    var export: ExportConfig? = ExportConfig()
    var immersion: ImmersionConfig? = ImmersionConfig()

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

    /** Global OSD unit system: "METRIC" or "IMPERIAL" (per-element override on OsdElement). */
    var osdUnit: String = Defaults.OSD_UNIT_DEFAULT

    /** OSD static text labels. */
    var craftName: String = Defaults.CRAFT_NAME_DEFAULT
    var pilotName: String = Defaults.PILOT_NAME_DEFAULT

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

    /**
     * Rotational physics model: "REAL" (motor first-order lag + rigid-body
     * inertia plant, see dev.fpv.flight.RealDynamics) or "ARCADE" (the legacy
     * near-instant tracked-rate path). Data-driven, persisted, GUI-switchable.
     */
    var physicsRealism: String = Defaults.PHYSICS_REALISM

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

    /**
     * Full-stick yaw rate (deg/s) the REAL plant physically reaches at hover on
     * the active airframe. Exposed so the config GUI can annotate that the
     * nominal yaw.rate max may exceed what the mixer/reaction-torque can deliver;
     * not a hard limit.
     */
    fun yawPhysicalMaxDps(): Float =
        activeAirframe().yawPhysicalMaxDps(activeAirframe().effectiveHoverThrottle())

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
    fun migrate() {
        if (slotCalib.size != StickSlot.entries.size) slotCalib = defaultSlots()
        // Older configs predate the nested battery/failsafe blocks; fill defaults.
        if (battery == null) battery = BatteryConfig()
        if (failsafe == null) failsafe = FailsafeConfig()
        if (pid == null) pid = PidConfig()
        if (throttleLimit == null) throttleLimit = ThrottleLimitConfig()
        if (race == null) race = RaceConfig()
        if (serverCompat == null) serverCompat = ServerCompatConfig()
        if (replay == null) replay = ReplayConfig()
        if (export == null) export = ExportConfig()
        if (osdElements.isEmpty()) osdElements = OsdLayout.defaultLayout()
        // Older configs predate data-driven airframes.
        if (airframes.isEmpty()) airframes = defaultAirframes()
        if (airframes.none { it.name == activeAirframeName })
            activeAirframeName = airframes.first().name
        // auxChannels and device profiles have non-null initializers; Gson
        // leaves them empty for older configs predating the data-driven lists.

        // One-time migration (schema 1 -> 2): an older profile created for a
        // KNOWN device before the arm/modes seeding existed kept armButton=-1 and
        // no ARM routing row, so arm fell back to the legacy SA axis while SA was
        // ALSO the legacy mode-cycle axis (arming jumped into Angle). Re-apply the
        // known-device seed ONLY for that old-default fingerprint, and decouple
        // the legacy top-level indices, without touching explicit user edits.
        var repairedKnown = false
        for (p in profiles) {
            val known = KnownDevices.match(p.modelName) ?: continue
            val hasArmRow = p.modes.any { it.function == FlightFunction.ARM.id }
            if (p.armButton < 0 && !hasArmRow) {
                KnownDevices.apply(p, known)
                repairedKnown = true
            }
        }
        if (repairedKnown) {
            armSwitchAxis = -1
            modeSwitchAxis = -1
            profiles.firstOrNull { KnownDevices.match(it.modelName) != null }?.let {
                if (it.armButton >= 0) armButtonIndex = it.armButton
            }
        }

        schemaVersion = Defaults.CONFIG_SCHEMA_VERSION
    }

    // ---- Persistence ----
    /** Non-persisted notice from load/restore (config fell back / file quarantined). */
    @Transient
    var loadNotice: String = ""

    fun save() {
        // Keep the active profile's hand-mode/arm in sync with the live setting.
        activeProfile()?.let {
            it.handMode = handMode
            it.armButton = armButtonIndex
        }
        val p = configPath()
        // Back up the previous version before overwriting, then atomic write so a
        // crash mid-save can never leave a half-written fpvcraft.json.
        ConfigSafety.rotateBackup(FabricLoader.getInstance().configDir)
        AtomicFiles.writeText(p, gson.toJson(this))
    }

    /** True when the loaded config has the minimum required structure/values. */
    fun isSane(): Boolean {
        if (airframes.isEmpty() || osdElements.isEmpty()) return false
        if (airframes.none { it.name == activeAirframeName }) return false
        val af = activeAirframe()
        if (!(af.massKg.isFinite() && af.massKg > 0f)) return false
        if (!(af.maxThrustPerMotorN.isFinite() && af.maxThrustPerMotorN > 0f)) return false
        listOf(roll, pitch, yaw).forEach {
            if (!(it.max.isFinite() && it.center.isFinite())) return false
        }
        return true
    }

    /** Reset this live config to built-in safe defaults and persist. */
    fun resetToDefaults() {
        adoptFrom(FpvConfig())
        loadNotice = "已恢复为默认配置"
        save()
    }

    /** Restore the backup at [index] (0 = newest); returns false if it was unusable. */
    fun restoreBackup(index: Int): Boolean {
        val root = FabricLoader.getInstance().configDir
        val b = ConfigSafety.listBackups(root).getOrNull(index) ?: return false
        val c = ConfigSafety.tryParse(b) ?: return false
        adoptFrom(c)
        loadNotice = "已恢复到备份 ${b.fileName}"
        save()
        return true
    }

    /** Backup labels newest-first for the config UI (timestamp portion). */
    fun backupLabels(): List<String> =
        ConfigSafety.listBackups(FabricLoader.getInstance().configDir)
            .map { it.fileName.toString().removePrefix("fpvcraft-").removeSuffix(".json") }

    /**
     * Overwrite every persisted field of THIS instance with [other]'s state.
     * Data-driven via reflection so newly added fields are covered automatically;
     * readers (flight/render) pick up the values next frame -- no restart needed.
     */
    private fun adoptFrom(other: FpvConfig) {
        val fresh = snapshot(other)
        var cls: Class<*>? = FpvConfig::class.java
        while (cls != null) {
            for (f in cls.declaredFields) {
                val mod = f.modifiers
                if (java.lang.reflect.Modifier.isStatic(mod) || f.isSynthetic) continue
                f.isAccessible = true
                f.set(this, f.get(fresh))
            }
            cls = cls.superclass
        }
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
                comment = ("Balanced 5in freestyle (4S). Mass/inertia/thrust grounded in open data " +
                    "(Flightmare m=0.73-0.76; 5in 4S static pull ~1.1-1.2 kg/motor); angular drag scaled " +
                    "to keep tau~5.9ms so the feel matches the legacy build. See research-v2/phys-const.")
                massKg = 0.65f
                inertiaXX = 0.0025f; inertiaYY = 0.0030f; inertiaZZ = 0.0045f
                motorCount = 4; maxThrustPerMotorN = 12.0f
                thrustLinear = 0.65f; thrustQuad = 0.35f
                propInch = 5.1f; propPitch = 4.6f; motorKv = 1900
                cameraTiltDeg = 25f; minThrottle = 0.055f
                thrLow = 0.95f; thrMid = 0.95f; thrHigh = 1.05f
                gravity = 9.81f; instantPower = 0.65f
                airDrag = 0.40f; airGrip = 0.85f; relativeAirspeed = 1.00f
                propwashEnabled = false; pidBehavior = "PERFECT"
                linearDrag = 0.0f; quadraticDrag = 0.015f
                angularDragXX = 0.50f; angularDragYY = 0.60f; angularDragZZ = 0.90f
                cgOffsetX = 0f; cgOffsetY = 0f; cgOffsetZ = 0f
            }
            val f9u = AirframeProfile().apply {
                name = "F9U reference 6in"
                comment = ("Within F9U hardware limits: mass<=1.0kg [FAI C.1.1 / TWG 2.1], " +
                    "prop<=6in [FAI Annex C.1 / H.propeller_max_inches], up to 6S @<=4.25V/cell " +
                    "[FAI C.1.2 / TWG 2.2]. Thrust/inertia/drag are CLASS ESTIMATES, not rulebook " +
                    "numbers -- to be tuned on a real model.")
                massKg = 0.95f
                inertiaXX = 0.0040f; inertiaYY = 0.0050f; inertiaZZ = 0.0070f
                motorCount = 4; maxThrustPerMotorN = 18.0f
                thrustLinear = 1.0f; thrustQuad = 0.05f
                propInch = 6.0f; propPitch = 5.0f; motorKv = 1900; cellCountS = 6
                cameraTiltDeg = 30f; minThrottle = 0.06f
                thrLow = 0.95f; thrMid = 0.95f; thrHigh = 1.05f
                gravity = 9.82f; instantPower = 0.70f
                airDrag = 0.45f; airGrip = 0.85f; relativeAirspeed = 1.00f
                propwashEnabled = false; pidBehavior = "racing"
                linearDrag = 0.0f; quadraticDrag = 0.018f
                angularDragXX = 0.67f; angularDragYY = 0.83f; angularDragZZ = 1.17f
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

        /** Round-trip [other] through Gson into a fresh, migrated instance. */
        private fun snapshot(other: FpvConfig): FpvConfig =
            gson.fromJson(gson.toJson(other), FpvConfig::class.java).also { it.migrate() }

        /** SHA-256 of [c]'s serialized form (blackbox config fingerprint). */
        fun contentHash(c: FpvConfig): String = Hashes.sha256Hex(gson.toJson(c))

        /** Robust load delegated to the headless-testable [ConfigSafety] engine. */
        fun load(): FpvConfig {
            val r = ConfigSafety.robustLoad(FabricLoader.getInstance().configDir)
            r.config.loadNotice = r.notice
            return r.config
        }
    }
}
