/*
 * FPV Craft - MIT
 *
 * Real (P-D) rotational plant: motor first-order lag + rigid-body inertia.
 * Clean-room rigid-body equations of motion, modeled on the well-known
 * quadrotor model used in gym-pybullet-drones / drone-models (MIT). The
 * Betaflight-style PID -> motor differential path is a clean-room rewrite of
 * the published structure (no GPL source text copied).
 *
 * Pipeline per frame:
 *   setpoint dps [pitch,roll,yaw]
 *     -> inner PID (or simple P) on the measured gyro -> per-axis differential [-1,1]
 *     -> Quad-X mixer (MixerTables) -> 4 normalized motor commands u in [idle,1]
 *     -> motor first-order lag  tau*dm/dt = -m + sqrt(u)   (m = rpm/rpmMax)
 *     -> per-motor thrust   T_i = maxThrustPerMotorN * m_i^2 * batteryDerate
 *        per-motor reaction torque  R_i = reactionTorquePerMotorNm * m_i^2 * derate
 *     -> moments from lever arms:  tauX = -sum z_i T_i
 *                                  tauZ =  sum x_i T_i
 *                                  tauY = -sum spin_i R_i   (spin = Quad-X yaw column)
 *     -> rigid body:  I*w_dot = tau - w x (I w) - damping*w
 *     -> integrate physical body angular rate -> dps [pitch,roll,yaw]
 *
 * This class is deliberately Minecraft-free so it can be exercised headless.
 * The owning FlightController integrates the returned dps into the attitude
 * quaternion (shared with the ARCADE path).
 *
 * Body-frame convention matches BodyAxis: right=+X, up=+Y, forward=-Z.
 * Physical angular rate vector w=(wx,wy,wz) relates to the logical dps as
 *   pitch = -wx*RAD2DEG, roll = -wz*RAD2DEG, yaw = -wy*RAD2DEG.
 */
package dev.fpv.flight

import kotlin.math.PI
import kotlin.math.sqrt

class RealDynamics(
    private val cfg: FpvConfig,
    private val pid: RatePidController,
) {
    /** Normalized motor speed m = rpm/rpmMax for [MixerTables.QUAD_X] order. */
    private val motor = FloatArray(4)

    /** Physical body angular velocity, rad/s (wx=pitch-axis, wy=yaw-axis, wz=roll-axis). */
    private var wx = 0f
    private var wy = 0f
    private var wz = 0f

    fun reset() {
        motor.fill(0f)
        wx = 0f; wy = 0f; wz = 0f
    }

    /** Latest actual body rates, dps [pitch,roll,yaw] (read out after [step]). */
    val ratesDps: FloatArray
        get() = floatArrayOf(
            (-wx).toRadToDeg(),
            (-wz).toRadToDeg(),
            (-wy).toRadToDeg(),
        )

    private fun Float.toRadToDeg(): Float = (this * 180.0 / PI).toFloat()

    /**
     * Advance the plant one frame.
     *
     * @param sp      setpoint dps [pitch, roll, yaw] (after rate map / smoothing)
     * @param thr     collective throttle 0..1 (after curve/boost/idle floor)
     * @param dt      frame seconds
     * @param batteryDerate 0..1 multiplier on achievable rpm (vbat/vnominal);
     *                       sag lowers max thrust and reaction torque
     * @return actual body rates dps [pitch, roll, yaw]
     */
    fun step(sp: FloatArray, thr: Float, dt: Float, batteryDerate: Float): FloatArray {
        val af = cfg.activeAirframe()
        val h = dt.coerceIn(1e-4f, 0.1f)
        val der = batteryDerate.coerceIn(0.2f, 1f)

        // Measured gyro dps = our own integrated physical rate (real FC gyro).
        val measPitch = (-wx).toRadToDeg()
        val measRoll = (-wz).toRadToDeg()
        val measYaw = (-wy).toRadToDeg()
        val meas = floatArrayOf(measPitch, measRoll, measYaw)

        // 1. Per-axis differential demand [-1,1].
        val d = FloatArray(3)
        val pidOn = cfg.pid?.enabled == true
        for (axis in 0..2) {
            d[axis] = if (pidOn) {
                pid.runDifferential(axis, sp[axis], meas[axis], thr, h)
            } else {
                ((sp[axis] - meas[axis]) * af.simpleRatePGain).coerceIn(-1f, 1f)
            }
        }

        // 2. Quad-X mixer: motor = collective + per-axis authority * differential.
        //    Roll/pitch use thrust differential (small swing); yaw uses reaction
        //    torque and needs a larger motor-fraction swing.
        val pitchAuth = af.pitchAuthority.coerceIn(0f, 0.8f)
        val rollAuth = af.rollAuthority.coerceIn(0f, 0.8f)
        val yawAuth = af.yawAuthority.coerceIn(0f, 0.9f)
        val u = FloatArray(4)
        for (i in 0..3) {
            val row = MixerTables.QUAD_X[i]
            val cmd = thr +
                pitchAuth * d[BodyAxis.PITCH.index] * row[MixerTables.PITCH] +
                rollAuth * d[BodyAxis.ROLL.index] * row[MixerTables.ROLL] +
                yawAuth * d[BodyAxis.YAW.index] * row[MixerTables.YAW]
            u[i] = cmd.coerceIn(af.minThrottle, 1f)
        }

        // 3. Motor first-order lag: m tracks sqrt(u).
        val tau = af.motorTauSec.coerceAtLeast(1e-3f)
        val k = (h / (tau + h)).coerceIn(0f, 1f)
        for (i in 0..3) {
            val target = sqrt(u[i].coerceIn(0f, 1f))
            motor[i] += k * (target - motor[i])
            if (!motor[i].isFinite()) motor[i] = 0f
        }

        // 4. Moments.
        val darm = af.armLength / sqrt(2f)
        var tauX = 0f
        var tauY = 0f
        var tauZ = 0f
        for (i in 0..3) {
            val row = MixerTables.QUAD_X[i]
            val m2 = motor[i] * motor[i]
            val thrust = af.maxThrustPerMotorN * m2 * der
            // right motors i=0,1 have x=+darm; rear motors i=0,2 have z=+darm.
            val xi = if (i == 0 || i == 1) darm else -darm
            val zi = if (i == 0 || i == 2) darm else -darm
            tauX += -zi * thrust
            tauZ += xi * thrust
            // Reaction (counter-)torque about +Y; spin sign = yaw mixer column.
            tauY += -row[MixerTables.YAW] * af.reactionTorquePerMotorNm * m2 * der
        }

        // 5. Rigid body: I*w_dot = tau - w x (I w) - b*w.
        val Ix = af.inertiaXX.coerceAtLeast(1e-6f)
        val Iy = af.inertiaYY.coerceAtLeast(1e-6f)
        val Iz = af.inertiaZZ.coerceAtLeast(1e-6f)
        val bx = af.rotDampXX
        val by = af.rotDampYY
        val bz = af.rotDampZZ
        val dwx = (tauX - (Iz - Iy) * wy * wz - bx * wx) / Ix
        val dwy = (tauY - (Ix - Iz) * wz * wx - by * wy) / Iy
        val dwz = (tauZ - (Iy - Ix) * wx * wy - bz * wz) / Iz
        wx += dwx * h
        wy += dwy * h
        wz += dwz * h

        // Sanitize: a non-finite plant state must never reach the caller.
        if (!wx.isFinite() || !wy.isFinite() || !wz.isFinite()) {
            wx = 0f; wy = 0f; wz = 0f
        }
        return ratesDps
    }
}
