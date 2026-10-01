/*
 * FPV Craft - MIT
 * Client-side flight state: attitude integration, Acro/Angle/Horizon modes,
 * reversible-3D thrust semantics. No entities, no server changes.
 *
 * Body axes follow the camera convention: right = +X, up = +Y, forward = -Z.
 * The integrated [attitude] is a world-space rotation the camera mixin applies
 * directly. Axis vectors for integration and rate measurement both come from
 * BodyAxis, a single source of truth.
 */
package dev.fpv.flight

import dev.fpv.input.StickChannels
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

class FlightController(val cfg: FpvConfig = FpvConfig()) {

    /** World-space body attitude. */
    val attitude = Quaternionf()

    /** Latest commanded body rates [pitch, roll, yaw], dps (for telemetry). */
    val setpointRates = FloatArray(3)

    /** Latest measured body rates [pitch, roll, yaw], dps (for telemetry). */
    val bodyRates = FloatArray(3)

    /**
     * Estimated ACTUAL body rates after the first-order inertia/angularDrag
     * tracking (dps). The attitude is integrated from this, not directly from
     * the commanded rates, so inertia/angularDrag genuinely shape tracking.
     */
    private val trackedRates = FloatArray(3)

    private val spRoll = Pt3(cfg.setpointCutoffHz)
    private val spPitch = Pt3(cfg.setpointCutoffHz)
    private val spYaw = Pt3(cfg.setpointCutoffHz)
    private val headfree = HeadfreeTransform()
    private var headfreeActive = false

    /** Optional inner PID rate loop (TPA / I-term relax / anti-gravity / FF). */
    private val pidLoop = RatePidController(cfg)

    /** Real (P-D) rigid-body plant, used when cfg.physicsRealism == "REAL". */
    private val realDynamics = RealDynamics(cfg, pidLoop)

    /** Persistent prop-wash model: its oscillation phase must accumulate across frames. */
    private val propwash = PropwashModel()

    /**
     * Battery voltage derate 0..1 (vbat/vNominal), fed by the client each frame.
     * High throttle sags the pack -> lower rpm -> less thrust/reaction torque.
     * Headless callers can leave this at 1.0.
     */
    @JvmField
    var batteryDerate: Float = 1f

    // ---- Translational state feeding the condition-driven prop-wash model ----
    /** Latest vertical velocity (blocks/tick; negative = descending), set by the mixin. */
    @JvmField
    var transVy: Float = 0f

    /** Latest horizontal airspeed (blocks/tick), set by the mixin. */
    @JvmField
    var transHoriz: Float = 0f

    /** Prop-wash thrust multiplier (1 - drop*strength), latest step; mixin folds it into derate. */
    @JvmField
    var propwashThrustScale: Float = 1f

    /** Called each tick by the translation mixin so prop-wash sees the flight condition. */
    fun setTranslationState(vy: Float, horizSpeed: Float) {
        transVy = vy
        transHoriz = horizSpeed
    }

    /** Crash detector + leveling suggestion. */
    private val crash = CrashRecovery()

    /** True while crash recovery holds a leveling override (throttle is cut). */
    var crashRecovering = false
        private set

    private var headAdjustWasHigh = false

    var ready: Boolean = false
        private set

    /** Currently active mode (AUX switch cycles it at runtime). */
    var currentMode: FlightMode = FlightMode.ACRO
        private set

    /** OSD telemetry: commanded target inclination in self-level modes. */
    @JvmField
    var targetRollDeg = 0f

    @JvmField
    var targetPitchDeg = 0f

    private val angle = AngleController()
    private var modeSwitchWasHigh = false

    /** True while the data-driven modes router selects the flight mode. */
    private var modeExternallySelected = false

    /** Simulation clock, seconds (drives propwash oscillation phase). */
    private var simTime = 0f

    /**
     * Enter FPV mode and seed the attitude from the player's current look
     * direction. Matches the camera rotation form:
     * rotationYXZ(PI - yaw*rad, -pitch*rad, 0).
     */
    fun engage(yawDeg: Float, pitchDeg: Float) {
        attitude.rotationYXZ(
            PI.toFloat() - toRad(yawDeg),
            -toRad(pitchDeg),
            0f,
        )
        angle.measuredDps.fill(0f)
        trackedRates.fill(0f)
        angle.rebaseline(attitude)
        currentMode = cfg.flightMode
        modeSwitchWasHigh = false
        headAdjustWasHigh = false
        crashRecovering = false
        pidLoop.reset()
        realDynamics.reset()
        crash.reset()
        // Clear setpoint filters so engage/mode-switch never replays old lag.
        spRoll.reset(); spPitch.reset(); spYaw.reset()
        headfreeActive = false
        headfree.unlock()
        ready = true
    }

    fun disengage() {
        ready = false
        modeExternallySelected = false
    }

    /** Failsafe LAND: force self-leveling and re-baseline the level plane. */
    fun forceAngleMode() {
        currentMode = FlightMode.ANGLE
        angle.rebaseline(attitude)
    }

    /**
     * Apply a flight mode requested by the data-driven modes router. Changing
     * mode re-baselines the self-level plane (no attitude jump) and resets the
     * PID/crash state, mirroring the legacy AUX mode-cycle behavior.
     */
    fun requestMode(mode: FlightMode) {
        modeExternallySelected = true
        if (mode == currentMode) return
        currentMode = mode
        angle.rebaseline(attitude)
        pidLoop.reset()
        crash.reset()
    }

    /** Re-latch the head-free reference heading to the current yaw (Modes HEADADJ). */
    fun triggerHeadingAdjust() {
        if (!ready) return
        headfree.lock(currentYawDeg())
        headfreeActive = true
    }

    /**
     * Anti-turtle (F9U G): pilot-activated, software-only righting after a crash.
     * Applies the shortest rotation that maps the current body-up vector back to
     * world-up (no touch, no pre-planned path). When fully inverted the shortest
     * arc is degenerate, so flip around the current forward axis instead.
     */
    fun turtleRight() {
        if (!ready) return
        val worldUp = Vector3f(0f, 1f, 0f)
        val bodyUp = Vector3f(0f, 1f, 0f).rotate(attitude)
        val dot = bodyUp.dot(worldUp)
        if (dot > 0.995f) return // already upright
        val flip: Quaternionf = if (dot < -0.995f) {
            // Fully inverted: 180 degrees around current forward (-Z body).
            val fwd = Vector3f(0f, 0f, -1f).rotate(attitude)
            Quaternionf().fromAxisAngleRad(fwd.normalize(), Math.PI.toFloat())
        } else {
            val axis = bodyUp.cross(worldUp, Vector3f()).normalize()
            val angle = Math.acos(dot.toDouble()).coerceIn(-1.0, 1.0)
            Quaternionf().fromAxisAngleRad(axis, angle.toFloat())
        }
        attitude.mul(flip).normalize()
        angle.rebaseline(attitude)
    }

    /**
     * Integrate one frame.
     *
     * @param ch normalized control channels (roll/pitch/yaw -1..1, throttle per mode)
     * @param dt frame time in seconds
     * @param throttleCmd curve/limit-processed throttle used by TPA/PID (defaults
     *        to the raw channel when not supplied)
     */
    fun step(ch: StickChannels, dt: Float, throttleCmd: Float = ch.throttle) {
        if (!ready) return

        handleModeSwitch(ch)
        handleHeadAdjust(ch)

        // Headfree: on rising enable, latch the current yaw; then rotate the
        // earth-frame roll/pitch vector into the body frame.
        var r = ch.roll
        var p = ch.pitch
        if (cfg.headfreeEnabled) {
            if (!headfreeActive) {
                headfree.lock(currentYawDeg())
                headfreeActive = true
            }
            val v = headfree.apply(r, p, currentYawDeg())
            r = v[0]; p = v[1]
        } else if (headfreeActive) {
            headfree.unlock()
            headfreeActive = false
        }

        // Third-order setpoint smoothing on roll/pitch/yaw before the rate map.
        if (cfg.setpointSmoothingEnabled) {
            spRoll.setCutoff(cfg.setpointCutoffHz)
            spPitch.setCutoff(cfg.setpointCutoffHz)
            spYaw.setCutoff(cfg.setpointCutoffHz)
            r = spRoll.update(r, dt)
            p = spPitch.update(p, dt)
            val sy = spYaw.update(ch.yaw, dt)
            // Commanded body rates, output order [pitch, roll, yaw].
            val cmd = FloatArray(3)
            when (currentMode) {
                FlightMode.ACRO -> {
                    cmd[BodyAxis.PITCH.index] =
                        Rates.actual(p, cfg.pitch.center, cfg.pitch.max, cfg.pitch.expo)
                    cmd[BodyAxis.ROLL.index] =
                        Rates.actual(r, cfg.roll.center, cfg.roll.max, cfg.roll.expo)
                    cmd[BodyAxis.YAW.index] =
                        Rates.actual(sy, cfg.yaw.center, cfg.yaw.max, cfg.yaw.expo)
                }
                FlightMode.ANGLE -> {
                    val eff = StickChannels(r, p, sy, ch.throttle, ch.aux, ch.present, ch.sourceName)
                    val ar = angle.angleRates(eff, attitude, dt, cfg)
                    cmd[0] = ar[0]; cmd[1] = ar[1]; cmd[2] = ar[2]
                }
                FlightMode.HORIZON -> {
                    val eff = StickChannels(r, p, sy, ch.throttle, ch.aux, ch.present, ch.sourceName)
                    val ar = angle.horizonRates(eff, attitude, dt, cfg)
                    cmd[0] = ar[0]; cmd[1] = ar[1]; cmd[2] = ar[2]
                }
            }
            integrate(cmd, dt, throttleCmd)
        } else {
            // Bypass: reset filters so re-enabling smoothing doesn't jump.
            spRoll.reset(r); spPitch.reset(p); spYaw.reset(ch.yaw)
            val cmd = FloatArray(3)
            when (currentMode) {
                FlightMode.ACRO -> {
                    cmd[BodyAxis.PITCH.index] =
                        Rates.actual(ch.pitch, cfg.pitch.center, cfg.pitch.max, cfg.pitch.expo)
                    cmd[BodyAxis.ROLL.index] =
                        Rates.actual(ch.roll, cfg.roll.center, cfg.roll.max, cfg.roll.expo)
                    cmd[BodyAxis.YAW.index] =
                        Rates.actual(ch.yaw, cfg.yaw.center, cfg.yaw.max, cfg.yaw.expo)
                }
                FlightMode.ANGLE -> {
                    val ar = angle.angleRates(ch, attitude, dt, cfg)
                    cmd[0] = ar[0]; cmd[1] = ar[1]; cmd[2] = ar[2]
                }
                FlightMode.HORIZON -> {
                    val ar = angle.horizonRates(ch, attitude, dt, cfg)
                    cmd[0] = ar[0]; cmd[1] = ar[1]; cmd[2] = ar[2]
                }
            }
            integrate(cmd, dt, throttleCmd)
        }
    }

    /**
     * Apply crash recovery and the optional PID rate loop, then run the
     * attitude integration + rate measurement for one frame.
     */
    private fun integrate(cmd: FloatArray, dt: Float, throttleCmd: Float) {
        // Never let a bad number propagate: sanitize the incoming attitude and
        // commands first (a NaN here would otherwise poison the integral below).
        SafetyGuards.sanitize(attitude, attitude)
        for (i in 0..2) if (!cmd[i].isFinite()) cmd[i] = 0f
        val thr = if (throttleCmd.isFinite()) throttleCmd else 0f
        // Attitude relative to level: [rollDeg positive=banked right,
        // pitchDeg positive=nose down] - feeds the crash detector. NOTE this is
        // the body/error convention (positive = banked right), opposite to the
        // on-screen horizon rotation (FpvOsd); do not unify them.
        val inv = Quaternionf(attitude).conjugate()
        val bodyUp = Vector3f(0f, 1f, 0f).rotate(inv)
        val bodyFwd = Vector3f(0f, 0f, -1f).rotate(inv)
        // A right bank tilts the world-up reference to body -X, so negate to
        // keep positive=right (verified: crash-recovery correction then opposes).
        val rollLevelDeg = Math.toDegrees(atan2(-bodyUp.x, bodyUp.y).toDouble()).toFloat()
        val pitchLevelDeg = Math.toDegrees(asin((-bodyFwd.y).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val levelAtt = floatArrayOf(rollLevelDeg, pitchLevelDeg, 0f)

        // Crash detection runs off the pre-PID commanded rates.
        val crashResult = crash.update(cmd, bodyRates, levelAtt, thr, dt)
        crashRecovering = crashResult.state == CrashResult.State.RECOVER
        if (crashRecovering) {
            // Leveling override: direct recovery rates, throttle cut by caller.
            cmd[0] = crashResult.suggestedPitchRateDps
            cmd[1] = crashResult.suggestedRollRateDps
            cmd[2] = 0f
        } else if (cfg.physicsRealism != "REAL" && cfg.pid?.enabled == true) {
            // Inner PID tracking loop per axis (measured = previous frame's rate).
            // In REAL mode the plant's own rate loop lives inside RealDynamics.
            for (i in 0..2) {
                cmd[i] = pidLoop.run(i, cmd[i], bodyRates[i], throttleCmd, dt)
            }
        }

        setpointRates[0] = cmd[0]; setpointRates[1] = cmd[1]; setpointRates[2] = cmd[2]
        targetRollDeg = angle.targetRollDeg
        targetPitchDeg = angle.targetPitchDeg

        if (cfg.physicsRealism == "REAL") {
            // ---- P-D rigid-body plant: motor lag + inertia integration ----
            // The plant owns its motor + gyro state and returns the actual body
            // rates; we then integrate those into the attitude quaternion below.
            val actual = realDynamics.step(cmd, thr, dt, batteryDerate)
            for (i in 0..2) trackedRates[i] = if (actual[i].isFinite()) actual[i] else 0f
        } else {
        // ---- Airframe inertia / angularDrag first-order body-rate tracking ----
        // Plant: I*dω/dt = b*(ωcmd - ω) => ω follows the command with time
        // constant tau = I / angularDrag. Default tau is ~5 ms (near-instant,
        // keeps the acro feel); larger I / smaller b -> heavier, damped,
        // laggier handling. bodyRates (below) is measured from this actual motion.
        val af = cfg.activeAirframe()
        for (b in BodyAxis.entries) {
            val tau = af.tauSec(b)
            val alpha = (dt / (tau + dt)).coerceIn(0f, 1f)
            trackedRates[b.index] += alpha * (cmd[b.index] - trackedRates[b.index])
        }
        // Stylised thrust-arm coupling from the CG offset. Thrust acts along
        // body -Z at offset r=(x,y,z): torque τ = r x F = (-y*T, x*T, 0).
        // Default cg offset is 0, so this is a no-op until the pilot moves it.
        // (Full 6-DOF moment arm / aerodynamic moments are a later,真机-tuned step.)
        val T = af.totalThrustN(thr)
        val tauX = -af.cgOffsetY * T
        val tauY = af.cgOffsetX * T
        for (b in BodyAxis.entries) {
            val axisTorque = b.axis.x * tauX + b.axis.y * tauY // roll axis torque component = 0
            val i = when (b) {
                BodyAxis.PITCH -> af.inertiaXX
                BodyAxis.YAW -> af.inertiaYY
                BodyAxis.ROLL -> af.inertiaZZ
            }
            trackedRates[b.index] += Math.toDegrees((axisTorque / i.coerceAtLeast(1e-6f)).toDouble()).toFloat() * dt
        }
        } // end ARCADE tracking branch

        // Condition-driven prop-wash (applies in BOTH physics modes): descending /
        // settled in the own downwash at low airspeed + high throttle -> 15-40 Hz
        // gyro shake + thrust drop; clean fast forward flight stays smooth.
        val washOut = propwash.step(cfg.activeAirframe(), transVy, transHoriz, thr, dt)
        trackedRates[0] += washOut.x()
        trackedRates[1] += washOut.y()
        propwashThrustScale = propwash.thrustScale

        // Body-frame post-multiply integration; axis vectors from BodyAxis.
        // Integrate the tracked (actual) rates, not the commanded ones. Guard
        // the rates once more so a NaN plant can never enter the quaternion.
        for (i in 0..2) if (!trackedRates[i].isFinite()) trackedRates[i] = 0f
        val oldAtt = Quaternionf(attitude)
        val deltaQ = Quaternionf()
        for (b in BodyAxis.entries) {
            val a = b.axis
            deltaQ.mul(Quaternionf().rotateAxis(toRad(trackedRates[b.index] * dt), a.x, a.y, a.z))
        }
        attitude.mul(deltaQ).normalize()
        SafetyGuards.sanitize(attitude, attitude)

        // Measure actual motion -> next frame's damping term.
        val m = AttitudeMath.bodyRatesDps(oldAtt, attitude, dt)
        angle.measuredDps[0] = m[0]
        angle.measuredDps[1] = m[1]
        angle.measuredDps[2] = m[2]
        bodyRates[0] = if (m[0].isFinite()) m[0] else 0f
        bodyRates[1] = if (m[1].isFinite()) m[1] else 0f
        bodyRates[2] = if (m[2].isFinite()) m[2] else 0f
    }

    /** Current heading in MC yaw degrees, matching AngleController.rebaseline. */
    private fun currentYawDeg(): Float {
        val e = Vector3f()
        attitude.getEulerAnglesYXZ(e)
        return ((PI - e.y) * 180.0 / PI).toFloat()
    }

    /**
     * Optional AUX-driven mode cycle. Rising edge on the raw axis selected by
     * [FpvConfig.modeSwitchAxis] advances ACRO -> ANGLE -> HORIZON -> ACRO and
     * re-baselines the self-level plane (no attitude jump).
     */
    private fun handleModeSwitch(ch: StickChannels) {
        // The data-driven modes router owns mode selection; don't reset it.
        if (modeExternallySelected) return
        val idx = cfg.modeSwitchAxis
        // Generic guard: a legacy mode-cycle axis must never be the same source as
        // the arm axis (one switch must not both arm and change mode). The
        // data-driven modes router is the supported way to bind mode separately.
        if (idx < 0 || idx == cfg.armSwitchAxis) {
            currentMode = cfg.flightMode
            return
        }
        val v = ch.aux.getOrElse(idx) { 0f }
        val high = v > Defaults.SWITCH_TRIGGER
        if (high && !modeSwitchWasHigh) {
            currentMode = when (currentMode) {
                FlightMode.ACRO -> FlightMode.ANGLE
                FlightMode.ANGLE -> FlightMode.HORIZON
                FlightMode.HORIZON -> FlightMode.ACRO
            }
            angle.rebaseline(attitude)
            pidLoop.reset()
            crash.reset()
        }
        modeSwitchWasHigh = high
    }

    /**
     * Optional heading-adjust: rising edge on the raw aux axis selected by
     * [FpvConfig.headAdjustAxis] re-latches the headfree reference heading to
     * the current yaw (only meaningful while headfree is available).
     */
    private fun handleHeadAdjust(ch: StickChannels) {
        val idx = cfg.headAdjustAxis
        if (idx < 0) {
            headAdjustWasHigh = false
            return
        }
        val v = ch.aux.getOrElse(idx) { 0f }
        val high = v > Defaults.SWITCH_TRIGGER
        if (high && !headAdjustWasHigh) {
            headfree.lock(currentYawDeg())
            headfreeActive = true
        }
        headAdjustWasHigh = high
    }

    private fun toRad(deg: Float): Float = (deg * PI / 180.0).toFloat()
}
