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

    // ---- Translational / fallback input (engineering starting values) ----
    const val MOUSE_SENSITIVITY = 28f
    const val IDLE_THROTTLE = 0.15f
    const val THRUST_POWER = 1.1f
    const val DRAG_K = 0.015f

    // ---- Self-leveling (engineering starting values) ----
    const val ANGLE_MAX_DEG = 50f
    const val ANGLE_P = 4.0f
    const val ANGLE_D = 0.0f

    /** Reversible-3D throttle center deadband (normalized units). */
    const val THREE_D_THROTTLE_DEADBAND = 0.05f

    // ---- Switch channels ----
    /** Raw aux value above which a switch counts as high. */
    const val SWITCH_TRIGGER = 0.5f

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
    /** Pack sag at full throttle, volts (engineering starting value, virtual pack). */
    const val BATTERY_SAG_V = 0.6f
    /** Open-circuit recovery time constant when throttle relaxes (seconds). */
    const val BATTERY_RECOVERY_TAU = 1.5f
    /** Current draw at full throttle, amps (engineering starting value). */
    const val CURRENT_AT_FULL_THROTTLE_A = 30f

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
}
