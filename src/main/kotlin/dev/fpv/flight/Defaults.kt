/*
 * FPV Craft - MIT
 * Central design tokens: every tunable constant lives here instead of being
 * scattered through the input/flight/GUI code. Values that reproduce a
 * published factory default (actual-rates center/max) are labeled as such;
 * the rest are engineering starting values meant to be tuned in-game.
 */
package dev.fpv.flight

object Defaults {

    // ---- Raw axis / GLFW documented conventions ----
    /** GLFW documents joystick axes as normalized [-1, 1] centered at 0. */
    const val RAW_RANGE_MIN = -1f
    const val RAW_RANGE_MID = 0f
    const val RAW_RANGE_MAX = 1f

    /** Default per-channel center deadband (raw units). */
    const val CHANNEL_DEADZONE = 0.02f

    // ---- Axis learning ("bind by moving") ----
    /** Deviation from center above which an axis counts as moved. */
    const val AXIS_LEARN_THRESHOLD = 0.35f

    /** Deadband choices offered in the config screen. */
    val DEADZONE_CHOICES = floatArrayOf(0f, 0.01f, 0.02f, 0.05f, 0.10f)

    // ---- Calibration wizard ----
    /** Added to measured center jitter when deriving the deadband. */
    const val CALIB_JITTER_MARGIN = 0.01f

    /** Bounds for the derived deadband. */
    const val CALIB_DEADZONE_MIN = 0.005f
    const val CALIB_DEADZONE_MAX = 0.1f

    // ---- Actual rates (published factory defaults of the rate model) ----
    const val RATE_CENTER = 70f
    const val RATE_MAX = 670f
    const val RATE_EXPO = 0f

    // ---- Rate-type selector (ACTUAL / LEGACY / QUICK) ----
    const val RATES_TYPE_ACTUAL = "ACTUAL"
    const val RATES_TYPE_LEGACY = "LEGACY"
    const val RATES_TYPE_QUICK = "QUICK"

    // ---- Remote setback (rubber-band) detection (blocks / ms) ----
    const val SETBACK_THRESHOLD_BLOCKS = 2.0f
    const val SETBACK_COOLDOWN_MS = 800L

    // ---- FPV camera-tilt ramp: seconds to ramp from 0 to full tilt on arm ----
    const val CAMERA_TILT_RAMP_SEC = 0.4f

    // ---- Legacy (Betaflight rcRate/superRate) published configurator defaults ----
    const val LEGACY_RC_RATE = 2.0f
    const val LEGACY_SUPER_RATE = 0.7f

    // ---- Translational / fallback input (engineering starting values) ----
    const val MOUSE_SENSITIVITY = 28f
    const val IDLE_THROTTLE = 0.15f
    const val THRUST_POWER = 1.1f
    const val DRAG_K = 0.015f

    // ---- Self-leveling (engineering starting values) ----
    /** Published BF pid.c:150 angle_limit default = 60 deg. */
    const val ANGLE_MAX_DEG = 60f
    const val ANGLE_P = 4.0f
    const val ANGLE_D = 0.0f

    // ---- Horizon mode (published BF pid.c:542-561 calcHorizonLevelStrength) ----
    /** Above this bank/deck angle the horizon self-leveling strength hits 0 (deg). */
    const val HORIZON_LIMIT_DEG = 135f
    /** PT1 rise time-constant for the leveling strength (~BF horizonDelayMs 500ms). */
    const val HORIZON_SMOOTH_TAU_SEC = 0.5f

    /**
     * Airmode: minimum mixer authority fraction retained at low collective so
     * attitude control persists the instant the craft leaves the ground. 0 = the
     * fixed authority already used (no change); raise to bias low-throttle control.
     */
    const val AIRMODE_LOW_THROTTLE_AUTHORITY = 0.0f
    /** Below this normalized collective the airmode authority floor applies. */
    const val AIRMODE_ENGAGE_THROTTLE = 0.2f

    /** Reversible-3D throttle center deadband (normalized units). */
    const val THREE_D_THROTTLE_DEADBAND = 0.05f

    // ---- Switch channels ----
    /** Raw aux value above which a switch counts as high. */
    const val SWITCH_TRIGGER = 0.5f

    /** Default active range for a switch engaged in its high position. */
    const val SWITCH_ACTIVE_HIGH_LOW = 0.5f

    /** Half-width (normalized) of each discrete-switch position band. */
    const val SWITCH_POSITION_BAND = 0.5f

    /** Interval between repeated locate-beep cues while the BEEPER function is active. */
    const val BEEPER_INTERVAL_SEC = 0.5f

    // ---- Immersion layer: optics / signal / audio (research report-immersion §4) ----
    /** Mild wide-lens barrel coefficient (report: -0.22, range -0.4..0.0). */
    const val OPTICS_BARREL_K1 = -0.22f
    const val OPTICS_BARREL_K2 = 0.0f
    /** Vignette strength (report: 0.35). */
    const val OPTICS_VIGNETTE = 0.35f
    /** Static edge chromatic aberration (report: 0.004). */
    const val OPTICS_CA = 0.004f
    /** Home distance (blocks) where LQ drops to ~50%. */
    const val GLITCH_HALF_DIST = 120f
    /** Extra random fuzz on top of deterministic distance loss. */
    const val GLITCH_NOISE = 0.05f
    /** Motor whine pitch at idle / full throttle (report: 0.8 / ~2.5x). */
    const val WHINE_BASE_PITCH = 0.8f
    const val WHINE_FULL_PITCH = 2.5f

    // ---- Setpoint (RC) smoothing ----
    /** Published setpoint-smoother floor; engineering starting cutoff (Hz). */
    const val SETPOINT_SMOOTHING_ENABLED = true
    const val SETPOINT_CUTOFF_HZ = 15f

    // ---- Failsafe link monitor ----
    /** Published MAX_INVALID_PULSE_TIME_MS: hold a lost link for this long before acting. */
    const val FAILSAFE_HOLD_MS = 300L
    /** Published failsafe_recovery_delay: present must stay healthy this long before re-arming. */
    const val FAILSAFE_RECOVERY_DELAY_MS = 500L
    /** Published failsafe_procedure default. */
    const val FAILSAFE_PROCEDURE_DROP = "DROP"
    const val FAILSAFE_PROCEDURE_LAND = "LAND"
    /** LAND procedure: descend at this normalized throttle (engineering starting value). */
    const val FAILSAFE_LAND_THROTTLE = 0.35f

    // ---- Virtual battery model ----
    const val CELL_COUNT_DEFAULT = 4
    const val PACK_CAPACITY_MAH_DEFAULT = 0 // 0 = estimate percent from voltage only
    /** Published vbatwarningcellvoltage / vbatmincellvoltage (per-cell volts). */
    const val VBAT_WARNING_CELL = 3.50f
    const val VBAT_CRITICAL_CELL = 3.30f
    /** Published vbatfullcellvoltage / vbatmaxcellvoltage. */
    const val VBAT_FULL_CELL = 4.10f
    const val VBAT_MAX_CELL = 4.30f
    /** Open-circuit recovery time constant when throttle relaxes (seconds). */
    const val BATTERY_RECOVERY_TAU = 1.5f
    /** Current draw at full throttle, amps (engineering starting value). */
    const val CURRENT_AT_FULL_THROTTLE_A = 30f
    /** Per-cell internal resistance, ohms (pack R = cells * this; open data:
     *  good LiPo ~3-5 mOhm/cell, giving 4S 12-20 mOhm / 6S 18-30 mOhm). */
    const val CELL_INTERNAL_R_OHM = 0.004f

    // ---- Throttle curve (two-segment quadratic bezier) ----
    /** Published thrMid8 / thrExpo8 defaults (percent). */
    const val THR_MID_PCT = 50f
    const val THR_EXPO_PCT = 0f
    /** LUT resolution for the throttle curve. */
    const val THR_LUT_SIZE = 33

    // ---- Throttle boost (high-pass transient) ----
    /** Engineering equivalent of published throttle_boost=5 (scale) / cutoff 15Hz.
     *  The numeric gain is a starting value and must be tuned in-game. */
    const val THROTTLE_BOOST_ENABLED = true
    const val THROTTLE_BOOST_GAIN = 0.05f
    const val THROTTLE_BOOST_CUTOFF_HZ = 15f

    // ---- Reversible-3D neutral offset (normalized; published neutral3d = 1460us = center) ----
    const val NEUTRAL_3D = 0f

    // ---- Headfree ----
    const val HEADFREE_ENABLED = false

    // ---- Telemetry logger (blackbox-style) ----
    const val TELEMETRY_ENABLED = false
    /** Fixed sample period in seconds (render-rate independent). */
    const val TELEMETRY_PERIOD_S = 0.01f

    // ---- Translational motion multiplayer gating ----
    const val TRANSLATION_ENHANCE = true
    const val ALLOW_TRANSLATION_MULTIPLAYER = false

    // ---- Inner PID rate loop ----
    /** Enabled by default so TPA / I-term / anti-gravity / FF are live; can be
     *  disabled for direct (ideal) rate integration. */
    const val PID_ENABLED = true

    // ---- Physics realism switch (P-D) ----
    /** "REAL" = motor first-order lag + rigid-body inertia angular plant;
     *  "ARCADE" = legacy near-instant tracked-rate path. Data-driven + GUI-switchable. */
    const val PHYSICS_REALISM = "REAL"

    /** Motor (rotor) first-order lag time constant, seconds. Real brushless+ESC
     *  response is tens of ms; 0.03 is an engineering starting value to tune. */
    const val MOTOR_TAU_SEC = 0.03f

    /** CG-to-motor arm (half-diagonal) length, metres. 5" class ~0.10-0.11 m.
     *  Engineering starting value, not a sourced number. */
    const val ARM_LENGTH_M = 0.105f

    /** Reaction torque per motor at full rpm, N·m (yaw authority). km·rpmMax^2.
     *  For a 5" disk (~12 N full thrust) the torque/thrust ratio is ~0.012,
     *  i.e. ~0.15 N·m per motor at full rpm. */
    const val REACTION_TORQUE_PER_MOTOR_NM = 0.15f

    /** Normalised motor speed (rpm/rpmMax) at nominal voltage; 4S ~22-26k rpm. */
    const val RPM_MAX_PER_MOTOR = 24000f

    /** How far (in motor fraction) a full PID differential shifts a motor command.
     *  Keeps the hover motor command within [0,1] under full differential. Tuned
     *  headless so a rate step has ~50-90ms rise with a single mild overshoot. */
    const val DIFFERENTIAL_AUTHORITY = 0.22f

    /** Per-axis mixer differential authority (data-driven replacement for the
     *  single DIFFERENTIAL_AUTHORITY). Roll/pitch are thrust-differential; yaw is
     *  reaction-torque differential and needs a larger motor-fraction swing.
     *  Tuned headless for ~60-100ms rise, one mild overshoot, full-stick ~670 dps
     *  (roll/pitch) and ~400 dps (yaw). */
    const val ROLL_DIFFERENTIAL_AUTHORITY = 0.25f
    const val PITCH_DIFFERENTIAL_AUTHORITY = 0.27f
    const val YAW_DIFFERENTIAL_AUTHORITY = 0.55f

    /** Rigid-body viscous rotational damping (N·m·s), engineering starting values. */
    const val ROTDAMP_XX = 0.070f
    const val ROTDAMP_YY = 0.040f
    const val ROTDAMP_ZZ = 0.055f

    /** Body-frame quadratic airframe drag (per-tick coefficients), independent of
     *  rotor speed so it still acts in a zero-throttle glide. Anisotropic: the
     *  prop disk resists vertical motion most, forward flight next, lateral least.
     *  Engineering starting values, tunable. */
    const val FRAME_DRAG_SIDE = 0.012f   // body X, lateral
    const val FRAME_DRAG_FWD = 0.018f    // body Z, forward/back
    const val FRAME_DRAG_VERT = 0.022f   // body Y, up/down

    /** Simple proportional rate gain used when the inner PID loop is disabled
     *  (1/deg per second of error); engineering starting value. */
    const val SIMPLE_RATE_P_GAIN = 0.004f

    // ---- OSD unit system ----
    /** Default global OSD units: "METRIC" (km/h, m, mAh) or "IMPERIAL" (mph, ft). */
    const val OSD_UNIT_DEFAULT = "METRIC"

    /** Static OSD text labels. */
    const val CRAFT_NAME_DEFAULT = ""
    const val PILOT_NAME_DEFAULT = ""

    /** Model-derived ESC max rpm (full normalized motor = this rpm; OSD ESC_RPM). */
    const val ESC_MAX_RPM = 30000f

    // ---- OSD artificial horizon / pitch ladder (synthetic instrument) ----
    /** Vertical pixels per degree of pitch for the horizon group. */
    const val OSD_PITCH_PX_PER_DEG = 2.0f
    /** Pitch-reference bars beyond this many local pixels are hidden. */
    const val OSD_PITCH_LADDER_RANGE_PX = 70f
    /** Half-length of a pitch-ladder tick, pixels. */
    const val OSD_PITCH_TICK_HALF = 10
    /** Absolute pitch angles (degrees) at which reference ticks are drawn (both signs). */
    val OSD_PITCH_LADDER_DEG = intArrayOf(15, 30)

    // ---- Config safety net ----
    /** Schema version stamped into fpvcraft.json (bump when migration changes meaning). */
    const val CONFIG_SCHEMA_VERSION = 2
    /** Number of rotated config backups to keep. */
    const val CONFIG_BACKUP_KEEP = 10

    // ===================================================================
    // Remote (vanilla-server) firework boost envelope -- derived from the
    // 1.21.11 client bytecode (see FireworkEnvelope.kt for the exact method
    // references). These are NOT guesses: every number below is anchored to a
    // constant read out of the obfuscated jar with javap.
    // ===================================================================
    /**
     * Ticks from a useItem() call until the attached rocket applies its first
     * per-tick velocity relax. The rocket entity applies the relax on its very
     * first FireworkRocketEntity.tick() (life==0 branch, before life++), so
     * once the entity exists the onset is one tick. On a remote server there is
     * additionally a one-way packet/round-trip latency; we model the entity-side
     * onset only (the network part is not reproducible headless).
     * Source: FireworkRocketEntity.tick() offsets 47-174 (relax) then 420-432 (life++).
     */
    const val FW_ONSET_TICKS = 1

    /**
     * Per-tick velocity-relax "stickiness" of ONE attached rocket:
     *   vel <- relax * vel + accel * look
     * Bytecode (FireworkRocketEntity.tick()): 0.5 * old + 0.85 * look, i.e.
     * relax=0.5, accel=0.85, steady-state target = accel/(1-relax) = 1.7 b/t.
     */
    const val FW_RELAX = 0.5
    const val FW_ACCEL = 0.85

    /**
     * Rocket flight "power" = 1 + flightDuration (Fireworks component). A default
     * crafted/held rocket (1 gunpowder) has flightDuration=1? No: the component
     * defaults to ABSENT, in which case power=1 (see FireworkRocketEntity ctor:
     * power starts at 1 and only += flightDuration() if the component exists).
     * We default to power=1 (plain rocket) for the envelope model.
     */
    const val FW_POWER_DEFAULT = 1

    /**
     * lifetime = 10*power + rand(6) + rand(7)  (bytecode ctor). For power=1:
     * 10 + [0,5] + [0,6] = 10..21 ticks, mean = 10+2.5+3 = 15.5. We use the
     * deterministic mean as the nominal "effective thrust duration" so the beat
     * is reproducible; the headless fleet can optionally inject the random spread.
     */
    const val FW_LIFETIME_BASE = 10
    const val FW_LIFETIME_RAND_A = 6
    const val FW_LIFETIME_RAND_B = 7

    /**
     * Beat overlap target. To avoid a thrust GAP we ignite the next rocket while
     * the previous one still burns: interval = lifetime / overlapFactor.
     * overlapFactor=1.5 => ~1.5 rockets active on average: enough overlap to keep
     * the relax continuous, but NOT so many that the velocity pin saturates hard
     * (which would turn any look ripple directly into a velocity ripple -> shake).
     * Must be >=1 (overlap) and <=~2.5 (avoid over-saturation).
     */
    const val FW_BEAT_OVERLAP = 1.5

    /**
     * After throttle drops below threshold, stop igniting new rockets immediately.
     * Already-lit rockets burn out naturally (<= lifetime ticks). This small
     * hysteresis band prevents the ignite/stop from flapping at the threshold.
     */
    const val FW_THROTTLE_HYSTERESIS = 0.03f

    // ---- Bank-driven coordinated turn (remote; real-roll equivalence) ----
    /**
     * Sustained yaw rate (deg/tick) generated by a held bank angle phi:
     *   omega = turnGain * tan(phi) / max(speed, refSpeed)
     * Theoretical steady coordinated turn omega = g*tan(phi)/V. turnGain folds
     * g (9.81) and the b/t->deg/tick unit conversion into one data-driven knob.
     * Pure roll (yaw stick = 0) must curve the trajectory; holding the bank keeps
     * the turn going. Source: standard aircraft bank-to-turn relation.
     */
    const val TURN_RATE_GAIN = 1.0f
    /** Reference forward speed (blocks/tick) the omega is normalized at. */
    const val TURN_REF_SPEED_BPT = 0.8f
    /** Clamp on the per-tick integrated turn yaw (deg) so a 90deg bank can't snap. */
    const val TURN_MAX_YAW_PER_TICK_DEG = 6f
    /** Pitch compensation (deg of nose-down) per deg of bank to offset turn altitude loss. */
    const val TURN_PITCH_COMP_DEG_PER_DEG = 0.30f

    // ---- Smooth soft speed limiter (remote anti-kick only; NEVER bang-bang) ----
    /**
     * Above this horizontal speed (blocks/tick) the firework relax gain rolls off
     * continuously. The limiter is a soft saturation curve, NOT an on/off switch:
     * gain = 1 below knee, then a smooth (cosine/linear) taper to 0 at the roof.
     * Disabled by default; only engages for remote anti-kick, never on local/creative.
     */
    const val SPEED_LIM_KNEE_BPT = 1.2f
    const val SPEED_LIM_ROOF_BPT = 1.7f

    // ---- Look (thrust direction) low-pass: decouple attitude ripple from thrust ----
    /**
     * First-order low-pass cutoff (Hz) applied to the commanded vanilla look
     * (yaw/pitch) BEFORE self.turn(). The firework relax points along the live
     * look every tick, so any attitude/pitch ripple would otherwise be amplified
     * directly into velocity. Smoothing the commanded look breaks that loop.
     * 0 = disabled (raw mapping). Data-driven.
     */
    const val LOOK_LPF_HZ = 12f
}
