/*
 * FPV Craft - MIT
 *
 * Optional inner-loop PID rate controller, clean-room modeled on the published
 * Betaflight rate-controller structure (P-on-error, I-term relax, anti-gravity,
 * D on the gyro with a dynamic low-pass, setpoint feed-forward, TPA). No
 * third-party source text is reproduced; only the published parameter names,
 * defaults and well-known sign conventions are used.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * ENGINEERING UNIT MAPPING (READ ME — virtual rate machine, tune in-game)
 * ────────────────────────────────────────────────────────────────────────────
 * Betaflight's raw loop emits a *motor command delta*: common-mode throttle
 * plus a differential mix, and the plant itself has rotor inertia and
 * aerodynamic drag — a commanded rate lags the applied torque, and holding a
 * steady rate costs a nonzero differential.  This mod's virtual plant is
 * different: FlightController integrates the returned dps into attitude
 * instantly (measured ≈ previous frame's command; no inertia, no drag).
 *
 * We keep Betaflight's *structure, gain symbols and published defaults* in an
 * internal "pid unit" space, then convert to dps with an engineering mapping
 * chosen for this unity-gain virtual plant:
 *
 *   P_u = errDps * p * PTERM_SCALE        PTERM_SCALE = 0.032029 (published)
 *   I_u += errDps * i * ITERM_SCALE * dt * relaxFactor * antiGravBoost
 *                                         ITERM_SCALE = 0.244381 (published)
 *   D_u = -d(measured)/dt * d * DTERM_SCALE, through a dynamic PT1 LPF
 *                                         DTERM_SCALE = 0.000529 (published)
 *   F_u = d(setpoint)/dt * f * FF_SCALE
 *   sum_u   = clamp(P_u + I_u + D_u + F_u, ±sumLimit)   (500 RP / 400 yaw, published)
 *   outDps  = weightedSetpoint + SUM_TO_DPS * sum_u
 *
 * The weighted setpoint passes through as the equilibrium command — that is
 * exactly what the P0 ideal-rate loop did.  P/I/D/F are stabilization
 * *corrections* sized so that a 100 dps error yields only a small (tens of
 * dps) correction.  SUM_TO_DPS, FF_SCALE and RELAX_K below are engineering
 * starting values for a virtual rate machine; they are NOT Betaflight default
 * motor outputs and MUST be tuned in-game.  The published PTERM/ITERM/DTERM
 * scales only reproduce the *relative* P:I:D weight shape; their absolute
 * dps meaning does not transfer to this plant.
 * ────────────────────────────────────────────────────────────────────────────
 */
package dev.fpv.flight

import kotlin.math.abs
import kotlin.math.max

/**
 * Per-axis independent rate PID tracker. One instance owns pitch/roll/yaw state
 * (integrator memory, previous measured / previous setpoint, D-term filter,
 * setpoint low-pass for I-term relax). Call [run] once per axis per frame with
 * a positive dt; call [reset] on arm, disarm and every mode switch.
 *
 * Axis index convention: 0 = pitch, 1 = roll, 2 = yaw (matches BodyAxis).
 * All math is Float; non-finite inputs degrade to zero rather than propagate.
 */
class RatePidController(private val cfg: FpvConfig) {

    // ── Published gain→internal-unit scales (relative weight shape only) ─────
    private companion object {
        const val PTERM_SCALE = 0.032029f
        const val ITERM_SCALE = 0.244381f
        const val DTERM_SCALE = 0.000529f

        // Engineering mapping for the virtual plant (see header): tunable.
        const val FF_SCALE = 0.001f
        const val SUM_TO_DPS = 0.2f
        const val SUM_LIMIT_RP = 500f          // published pidSumLimit
        const val SUM_LIMIT_YAW = 400f         // published yaw pidSumLimit
        const val OUTPUT_CLAMP_DPS = 2000f     // engineering sanity clamp
        const val D_REF_RATE_DPS = 500f        // setpoint where D LPF is fully open
        const val RELAX_HPF_DPS = 40f         // published BF itermRelax hpf cutoff (dps)
        const val ANTI_GRAV_MAX = 10f          // engineering boost ceiling
    }

    private class AxisState {
        var iTerm = 0f
        var prevMeasured = 0f
        var prevSetpoint = 0f
        val dLp = Pt1(100f)
        val setpointLp = Pt1(15f)   // feeds I-term relax (SETPOINT type)
        val measuredLp = Pt1(15f)   // feeds I-term relax (GYRO type)
        var hasHistory = false
    }

    private val axes = Array(3) { AxisState() }
    private val throttleLp = Pt1(5f) // anti-gravity throttle transient detector
    private var throttleHistory = false

    /** Clear all per-axis state. Call on engage, disarm and flight-mode switch. */
    fun reset() {
        for (a in axes) {
            a.iTerm = 0f
            a.prevMeasured = 0f
            a.prevSetpoint = 0f
            a.hasHistory = false
            a.dLp.reset()
            a.setpointLp.reset()
            a.measuredLp.reset()
        }
        throttleLp.reset()
        throttleHistory = false
    }

    /**
     * Run one axis for one frame (ARCADE / virtual plant).
     *
     * @param axisIndex 0 = pitch, 1 = roll, 2 = yaw
     * @param setpointDps commanded body rate, deg/s (after rates mapping + smoothing)
     * @param measuredDps measured body rate, deg/s (previous frame's integrated rate)
     * @param throttle normalized throttle 0..1 (3D mode: -1..1)
     * @param dt frame time, seconds
     * @return final body rate to integrate, deg/s
     */
    fun run(
        axisIndex: Int,
        setpointDps: Float,
        measuredDps: Float,
        throttle: Float,
        dt: Float,
    ): Float {
        val weightedSetpoint = sanitize(setpointDps) * (cfg.pid?.setpointWeight ?: 1f)
        val sum = computeSum(axisIndex, setpointDps, measuredDps, throttle, dt)
        val out = weightedSetpoint + SUM_TO_DPS * sum
        return sanitize(out.coerceIn(-OUTPUT_CLAMP_DPS, OUTPUT_CLAMP_DPS))
    }

    /**
     * Run one axis for one frame (REAL plant, P-D). Returns the PID *sum*
     * normalized by its axis clamp, i.e. a demanded differential motor command
     * in [-1,1] that the Quad-X mixer spreads across the four motors. This is
     * the Betaflight-style "pid sum -> motor differential" path; there is no
     * dps feedthrough because the rigid-body plant itself integrates torque.
     */
    fun runDifferential(
        axisIndex: Int,
        setpointDps: Float,
        measuredDps: Float,
        throttle: Float,
        dt: Float,
    ): Float {
        val sum = computeSum(axisIndex, setpointDps, measuredDps, throttle, dt)
        val sumLimit = if (axisIndex == 2) SUM_LIMIT_YAW else SUM_LIMIT_RP
        return (sum / sumLimit).coerceIn(-1f, 1f)
    }

    /**
     * Shared inner loop: P on (weighted setpoint - gyro), I-term with relax +
     * anti-gravity, D on the gyro through a dynamic LPF, setpoint feed-forward,
     * TPA. Returns the clamped internal pid sum.
     */
    private fun computeSum(
        axisIndex: Int,
        setpointDps: Float,
        measuredDps: Float,
        throttle: Float,
        dt: Float,
    ): Float {
        val pidCfg = cfg.pid ?: return 0f
        val axis = axes[axisIndex.coerceIn(0, 2)]

        // Defensive sanitization: never let NaN/Inf poison the integrator.
        val sp = sanitize(setpointDps)
        val meas = sanitize(measuredDps)
        val thr = sanitize(throttle)
        val h = dt.coerceIn(1e-4f, 0.1f)

        val gains = when (axisIndex) {
            0 -> pidCfg.pitch
            1 -> pidCfg.roll
            else -> pidCfg.yaw
        }

        // 1. Setpoint weighting (published setpointWeight).
        val weightedSetpoint = sp * pidCfg.setpointWeight

        // 2. Error and P term.
        val error = weightedSetpoint - meas
        var pTerm = error * gains.p * PTERM_SCALE

        // 3. I term: relax gating + anti-gravity boost.
        val relaxFactor = itermRelaxFactor(axis, axisIndex, weightedSetpoint, meas, pidCfg, h)
        val antiGrav = antiGravityBoost(thr, pidCfg, h)
        axis.iTerm += error * gains.i * ITERM_SCALE * h * relaxFactor * antiGrav
        val iLimit = pidCfg.itermLimit
        if (axis.iTerm > iLimit) axis.iTerm = iLimit else if (axis.iTerm < -iLimit) axis.iTerm = -iLimit

        // 4. D term: NEGATIVE derivative of the measured (gyro) signal, then a
        //    dynamic PT1 LPF whose cutoff opens with |setpoint|.
        var dTerm = 0f
        if (axis.hasHistory && gains.d != 0f) {
            val dMeasured = (meas - axis.prevMeasured) / h
            dTerm = -dMeasured * gains.d * DTERM_SCALE
            val norm = (abs(weightedSetpoint) / D_REF_RATE_DPS).coerceIn(0f, 1f)
            axis.dLp.cutoffHz = pidCfg.dtermLpfMinHz +
                (pidCfg.dtermLpfMaxHz - pidCfg.dtermLpfMinHz) * norm
            dTerm = axis.dLp.update(dTerm, h)
        } else {
            axis.dLp.reset()
        }

        // 5. Feed-forward: derivative of the (weighted) setpoint.
        var fTerm = 0f
        if (axis.hasHistory && gains.f != 0f) {
            val dSetpoint = (weightedSetpoint - axis.prevSetpoint) / h
            fTerm = dSetpoint * gains.f * FF_SCALE
        }

        // 6. TPA: attenuate D (or P and D) above the throttle breakpoint.
        val tpa = tpaFactor(thr, pidCfg)
        when (pidCfg.tpaMode) {
            "PD" -> { pTerm *= tpa; dTerm *= tpa }
            "D" -> dTerm *= tpa
            else -> Unit // OFF
        }

        // 7. Sum and clamp in internal units.
        var sum = pTerm + axis.iTerm + dTerm + fTerm
        val sumLimit = if (axisIndex == 2) SUM_LIMIT_YAW else SUM_LIMIT_RP
        if (sum > sumLimit) sum = sumLimit else if (sum < -sumLimit) sum = -sumLimit

        axis.prevMeasured = meas
        axis.prevSetpoint = weightedSetpoint
        axis.hasHistory = true
        return sanitize(sum)
    }

    /**
     * I-term relax: freeze/attenuate integration while a fast setpoint (or
     * fast gyro) transient is moving. RP scope = roll+pitch only; RPY = all
     * axes; OFF = integrator always live. GYRO type keys off measured motion.
     */
    private fun itermRelaxFactor(
        axis: AxisState,
        axisIndex: Int,
        sp: Float,
        meas: Float,
        pidCfg: PidConfig,
        dt: Float,
    ): Float {
        val scope = pidCfg.itermRelax
        if (scope == "OFF") return 1f
        // RP does not relax yaw.
        if (scope == "RP" && axisIndex == 2) return 1f

        val spTransient = sp - axis.setpointLp.update(sp, dt)
        val measTransient = meas - axis.measuredLp.update(meas, dt)
        val input = if (pidCfg.itermRelaxType == "GYRO") measTransient else spTransient
        // Published BF pid.c:885-905: hard threshold, factor = max(0, 1 - hpf/40).
        // At/above RELAX_HPF_DPS dps the integrator is fully frozen (not asymptotic).
        val hpf = abs(input)
        val factor = 1f - hpf / RELAX_HPF_DPS
        return if (factor < 0f) 0f else factor
    }

    /**
     * Anti-gravity: a fast throttle transient (high-pass at 5 Hz) boosts the
     * integrator gain so I can keep up with punch-outs. Published gain 80.
     */
    private fun antiGravityBoost(throttle: Float, pidCfg: PidConfig, dt: Float): Float {
        if (pidCfg.antiGravityGain <= 0f || !throttleHistory) {
            throttleLp.update(throttle, dt)
            throttleHistory = true
            return 1f
        }
        val slow = throttleLp.update(throttle, dt)
        val transient = throttle - slow
        val boost = 1f + pidCfg.antiGravityGain * abs(transient)
        if (boost > ANTI_GRAV_MAX) return ANTI_GRAV_MAX
        return if (boost < 1f) 1f else boost
    }

    /**
     * TPA transfer: factor = 1 - tpaRate * max(throttle - tpaBreakpoint, 0),
     * floored at 0. Published defaults: D mode, 0.65, breakpoint 0.35.
     */
    private fun tpaFactor(throttle: Float, pidCfg: PidConfig): Float {
        if (pidCfg.tpaMode == "OFF" || pidCfg.tpaRate <= 0f) return 1f
        val above = max(throttle - pidCfg.tpaBreakpoint, 0f)
        val f = 1f - pidCfg.tpaRate * above
        return if (f < 0f) 0f else f
    }

    private fun sanitize(v: Float): Float = if (v.isFinite()) v else 0f
}
