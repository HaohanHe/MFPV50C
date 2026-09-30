/*
 * FPV Craft - MIT
 * Data-driven airframe (machine) profile. Every field below is consumed by
 * the real client physics (translation mixin / flight controller / camera /
 * throttle curve) -- a field that nothing reads is a bug.
 *
 * Body axes follow the camera convention used everywhere in this mod:
 *   right = +X (pitch axis), up = +Y (yaw axis), forward = -Z (roll axis).
 * Hence:
 *   inertiaXX = moment of inertia about the PITCH axis
 *   inertiaYY = moment of inertia about the YAW axis
 *   inertiaZZ = moment of inertia about the ROLL axis
 *
 * UI parameter layout follows the common "drone setup" screen shape
 * (hardware / behavior / advanced-physics), but every numeric default here is
 * a clean-room engineering starting value -- NOT transcribed from any
 * closed-source product. Derived performance numbers live in AirframeDerivation.
 */
package dev.fpv.flight

class AirframeProfile {

    /** Display name (also the activation key). */
    var name: String = "Freestyle"

    /** Free-text provenance / tuning notes. */
    var comment: String = ""

    // ---- Mass ----
    /** All-up mass, kg. Consumed: translational a = thrust/mass; drag decel scale. */
    var massKg: Float = 0.65f

    // ---- Moments of inertia, kg·m² ----
    /** Pitch-axis (body X) moment of inertia. Consumed by rotational tracking tau. */
    var inertiaXX: Float = 0.0022f
    /** Yaw-axis (body Y) moment of inertia. */
    var inertiaYY: Float = 0.0035f
    /** Roll-axis (body Z) moment of inertia. */
    var inertiaZZ: Float = 0.0018f

    // ---- Propulsion ----
    /** Motor count (quad = 4). */
    var motorCount: Int = 4

    /**
     * Max static thrust per motor at full throttle, Newtons.
     * Consumed: total full-throttle force = motorCount * this * thrustLaw(1).
     */
    var maxThrustPerMotorN: Float = 35.0f

    /**
     * Thrust law: total per-motor thrust fraction =
     *   thrustLinear * t + thrustQuad * t^2   (t = normalized throttle 0..1).
     */
    var thrustLinear: Float = 1.0f
    var thrustQuad: Float = 0.0f

    // ---- Hardware (display + derivation) ----
    /** Prop size, inches (F9U class limit 6"). Consumed by AirframeDerivation (D). */
    var propInch: Float = 5.1f

    /** Propeller pitch, inches. Feeds the thrust-derivation Ct estimate. */
    var propPitch: Float = 4.6f

    /** Motor Kv (RPM/V). Consumed by AirframeDerivation for no-load rpm. */
    var motorKv: Int = 1900

    /** Legacy Kv alias kept for old JSON; new profiles write motorKv. */
    var kvRef: Int = 0

    /**
     * Series cells (S). <=0 = inherit the shared BatteryConfig.cellCount.
     * Consumed: BatteryModel pack voltage + derivation Vnom.
     */
    var cellCountS: Int = 0

    /**
     * FPV camera tilt, degrees (positive = camera looks up / nose-down bias).
     * Applied as a permanent pitch offset between the drone attitude and the
     * rendered camera (CameraMixin), so the pilot sees a tilted FPV view.
     */
    var cameraTiltDeg: Float = 25f

    /**
     * Minimum armed throttle (idle spool), normalized 0..1. While flying/armed
     * in normal mode the throttle output never drops below this.
     */
    var minThrottle: Float = 0.055f

    /**
     * Hover throttle 0..1; 0.0 = auto-solve mg = totalThrustN(t) (see
     * [autoHoverThrottle]). Level attitude + this collective -> net vertical
     * force ~0. Consumed by translational hover physics + GUI readout.
     */
    var hoverThrottle: Float = 0.0f

    // ---- Multi-point throttle shaping (applied in ThrottleCurve) ----
    /** Gain multiplier at raw throttle 0. */
    var thrLow: Float = 0.95f
    /** Gain multiplier at raw throttle 0.5. */
    var thrMid: Float = 0.95f
    /** Gain multiplier at raw throttle 1. */
    var thrHigh: Float = 1.05f

    // ---- Behavior ----
    /**
     * Propwash / realistic washout oscillation. When on, a small high-frequency
     * disturbance is injected on the body-rate tracking; the PID loop sees it
     * as a plant disturbance and rejects it (so it interacts with the PID).
     * Engineering amplitude; default OFF.
     */
    var propwashEnabled: Boolean = false

    /**
     * PID behavior preset. "" / "PERFECT" = no rate override (use current rates);
     * otherwise names a TuningPreset.id (racing/beginner/cinematic) applied on
     * profile activation. Reuses the existing preset table -- no new gains.
     */
    var pidBehavior: String = "PERFECT"

    // ---- Advanced physics ----
    /** Gravitational acceleration, m/s². Consumed: translation gravity delta. */
    var gravity: Float = 9.81f

    /**
     * Instantaneous power / throttle-response coefficient. Higher = thrust
     * ramps faster toward the commanded throttle. Consumed as the time-constant
     * scale of the throttle->power first-order filter (FpvClient).
     */
    var instantPower: Float = 0.65f

    /**
     * Air drag knob. Mapped to the quadratic per-tick drag coefficient via the
     * documented scale AIR_DRAG_K; default 0.40 reproduces the legacy 0.015.
     */
    var airDrag: Float = 0.40f

    /**
     * Air grip / attitude hold: scales the angular damping (higher = the craft
     * holds attitude and tracks stick crisper). Consumed in the rate tracking.
     */
    var airGrip: Float = 0.85f

    /**
     * Relative-airspeed coefficient: multiplies aerodynamic drag (and is the
     * forward-speed term in the top-speed derivation). Default 1.0.
     */
    var relativeAirspeed: Float = 1.00f

    /** Physics pipeline revision label; "v2.0" selects the current path. */
    var physicsModelVersion: String = "v2.0"

    // ---- Translational drag low-level coefficients ----
    /** Linear (viscous) drag: decelVec += linearDrag * speed * vHat. */
    var linearDrag: Float = 0.0f

    /**
     * Raw quadratic drag per-tick coefficient. The physics mixes airDrag and
     * relativeAirspeed on top of this; kept as the low-level calibration base.
     */
    var quadraticDrag: Float = 0.015f

    // ---- Rotational drag (per-axis) ----
    /** Angular drag about the pitch axis (N·m·s); tau = I / (b * airGrip). */
    var angularDragXX: Float = 0.44f
    /** Angular drag about the yaw axis. */
    var angularDragYY: Float = 0.70f
    /** Angular drag about the roll axis. */
    var angularDragZZ: Float = 0.36f

    // ---- Real rotational plant (P-D: motor lag + rigid-body inertia) ----
    /**
     * Motor (rotor) first-order lag time constant, seconds. The normalized
     * motor speed m tracks sqrt(command) via tau*dm/dt = -m + sqrt(u):
     *   m += (dt/(tau+dt)) * (sqrt(u) - m). Engineering default 0.03 s; tune.
     */
    var motorTauSec: Float = Defaults.MOTOR_TAU_SEC

    /**
     * CG-to-motor arm length, metres (X diagonal). The per-motor lever arms
     * are +/- armLength/sqrt(2) on both the roll(x) and pitch(z) axes.
     * Consumed by RealDynamics to turn thrust differences into roll/pitch moments.
     */
    var armLength: Float = Defaults.ARM_LENGTH_M

    /**
     * Peak reaction (counter-torque) per motor at full rpm, N·m. This is
     * km * rpmMax^2; the per-motor reaction torque scales with m^2 and its
     * sign follows the Quad-X spin table (MixerTables yaw column). Consumed for yaw.
     */
    var reactionTorquePerMotorNm: Float = Defaults.REACTION_TORQUE_PER_MOTOR_NM

    /** No-load motor rpm at nominal pack voltage. Only used to express kf/km as
     * physical coefficients; the plant tracks normalized m = rpm/rpmMax. */
    var rpmMaxPerMotor: Float = Defaults.RPM_MAX_PER_MOTOR

    /**
     * Differential mixing authority: how far (normalized motor fraction) a full
     * +/-1 PID differential shifts an individual motor command. Chosen so the
     * hover command keeps headroom within [0,1].
     */
    var differentialAuthority: Float = Defaults.DIFFERENTIAL_AUTHORITY

    /** Fallback proportional rate gain (1/deg/s of error) when the inner PID
     * loop is disabled. Consumed by RealDynamics only. */
    var simpleRatePGain: Float = Defaults.SIMPLE_RATE_P_GAIN

    // ---- Real rotational viscous damping (N·m·s), distinct from ARCADE tracking ----
    /**
     * Viscous rotational damping about the pitch axis (N·m·s) in the RIGID-BODY
     * plant:  Ix*wx_dot = ... - rotDampXX*wx. Real FPV airframes have small
     * aerodynamic rotor damping; these are engineering starting values, NOT the
     * ARCADE angularDragXX tracking coefficient.
     */
    var rotDampXX: Float = Defaults.ROTDAMP_XX
    /** Viscous rotational damping about the yaw axis. */
    var rotDampYY: Float = Defaults.ROTDAMP_YY
    /** Viscous rotational damping about the roll axis. */
    var rotDampZZ: Float = Defaults.ROTDAMP_ZZ

    // ---- Centre-of-gravity offset, metres (body frame) ----
    /** Thrust-line offset from CG; produces a pitch/yaw moment under power. */
    var cgOffsetX: Float = 0.0f
    var cgOffsetY: Float = 0.0f
    var cgOffsetZ: Float = 0.0f

    // ---- Battery (optional override; null = inherit FpvConfig.battery) ----
    var battery: BatteryConfig? = null

    /** Effective cell count: this airframe's S if set, else the shared pack. */
    fun effectiveCellCount(fallback: Int): Int = if (cellCountS > 0) cellCountS else fallback

    /** Total thrust at normalized throttle t (0..1), Newtons. */
    fun totalThrustN(t: Float): Float =
        motorCount * maxThrustPerMotorN * thrustLaw(t)

    private fun thrustLaw(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return (thrustLinear * x + thrustQuad * x * x).coerceAtLeast(0f)
    }

    // ---- Derived propulsion coefficients (physical units, for transparency) ----
    /** Thrust coefficient kf = maxThrustPerMotorN / rpmMax^2  (N·s^2/rad^2 -> N/rpm^2). */
    fun kf(): Float = maxThrustPerMotorN / (rpmMaxPerMotor * rpmMaxPerMotor).coerceAtLeast(1f)

    /** Reaction-torque coefficient km = reactionTorquePerMotorNm / rpmMax^2. */
    fun km(): Float = reactionTorquePerMotorNm / (rpmMaxPerMotor * rpmMaxPerMotor).coerceAtLeast(1f)

    /**
     * Hover throttle t_h (0..1): the normalized collective command at which
     * totalThrustN(t_h) == m*g on a level craft. Solves the thrust law
     *   thrustLinear*t + thrustQuad*t^2 = (m*g) / (motorCount*maxThrustPerMotorN)
     * clamped to [0,1]. Airmarked value 0.0 means "auto" (call this).
     */
    fun autoHoverThrottle(): Float {
        val tMax = motorCount * maxThrustPerMotorN
        if (tMax <= 1e-6f) return 0f
        val target = (massKg * gravity) / tMax
        // Solve thrustQuad*t^2 + thrustLinear*t - target = 0.
        if (thrustQuad <= 1e-6f) return (target / thrustLinear).coerceIn(0f, 1f)
        val disc = thrustLinear * thrustLinear + 4f * thrustQuad * target
        if (disc < 0f) return 0f
        val t = (-thrustLinear + Math.sqrt(disc.toDouble()).toFloat()) / (2f * thrustQuad)
        return t.coerceIn(0f, 1f)
    }

    /** Effective hover throttle: the configured value when >0, else the auto solve. */
    fun effectiveHoverThrottle(): Float =
        if (hoverThrottle > 0f) hoverThrottle.coerceIn(0f, 1f) else autoHoverThrottle()

    /** Rotational first-order time constant, seconds, for the given axis. */
    fun tauSec(axis: BodyAxis): Float = when (axis) {
        BodyAxis.PITCH -> inertiaXX / (angularDragXX * airGrip).coerceAtLeast(1e-6f)
        BodyAxis.YAW -> inertiaYY / (angularDragYY * airGrip).coerceAtLeast(1e-6f)
        BodyAxis.ROLL -> inertiaZZ / (angularDragZZ * airGrip).coerceAtLeast(1e-6f)
    }

    fun copy(): AirframeProfile =
        com.google.gson.Gson().fromJson(
            com.google.gson.Gson().toJson(this),
            AirframeProfile::class.java,
        )
}
