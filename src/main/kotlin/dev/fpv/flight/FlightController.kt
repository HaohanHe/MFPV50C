/*
 * FPV Craft - MIT
 * Client-side flight state: attitude integration, Acro/Angle/Horizon modes,
 * reversible-3D thrust semantics. No entities, no server changes.
 *
 * Body axes follow the camera convention: right = +X, up = +Y, forward = -Z.
 * The integrated [attitude] is a world-space rotation the camera mixin applies
 * directly.
 */
package dev.fpv.flight

import dev.fpv.input.StickChannels
import org.joml.Quaternionf
import kotlin.math.PI

class FlightController(val cfg: FpvConfig = FpvConfig()) {

    /** World-space body attitude. */
    val attitude = Quaternionf()

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

    /**
     * Enter FPV mode and seed the attitude from the player's current look
     * direction. Must match Camera.setRotation bytecode:
     * rotationYXZ(PI - yaw*rad, -pitch*rad, 0).
     */
    fun engage(yawDeg: Float, pitchDeg: Float) {
        attitude.rotationYXZ(
            PI.toFloat() - toRad(yawDeg),
            -toRad(pitchDeg),
            0f,
        )
        angle.measuredDps[0] = 0f
        angle.measuredDps[1] = 0f
        angle.measuredDps[2] = 0f
        angle.rebaseline(attitude)
        currentMode = cfg.flightMode
        modeSwitchWasHigh = false
        ready = true
    }

    fun disengage() {
        ready = false
    }

    /**
     * Integrate one frame.
     *
     * @param ch normalized control channels (roll/pitch/yaw -1..1, throttle per mode)
     * @param dt frame time in seconds
     */
    fun step(ch: StickChannels, dt: Float) {
        if (!ready) return

        handleModeSwitch(ch)

        val roll = ch.roll.coerceIn(-1f, 1f)
        val pitch = ch.pitch.coerceIn(-1f, 1f)
        val yaw = ch.yaw.coerceIn(-1f, 1f)

        // Commanded body rates [pitchNoseDownDps, rollRightDps, yawRightDps].
        val cmd = FloatArray(3)
        when (currentMode) {
            FlightMode.ACRO -> {
                cmd[0] = Rates.actual(pitch, cfg.pitch.center, cfg.pitch.max, cfg.pitch.expo)
                cmd[1] = Rates.actual(roll, cfg.roll.center, cfg.roll.max, cfg.roll.expo)
                cmd[2] = Rates.actual(yaw, cfg.yaw.center, cfg.yaw.max, cfg.yaw.expo)
            }
            FlightMode.ANGLE -> {
                val r = angle.angleRates(ch, attitude, dt, cfg)
                cmd[0] = r[0]; cmd[1] = r[1]; cmd[2] = r[2]
            }
            FlightMode.HORIZON -> {
                val r = angle.horizonRates(ch, attitude, dt, cfg)
                cmd[0] = r[0]; cmd[1] = r[1]; cmd[2] = r[2]
            }
        }

        targetRollDeg = angle.targetRollDeg
        targetPitchDeg = angle.targetPitchDeg

        // Body-frame post-multiply integration (axis signs verified in ACRO).
        val oldAtt = Quaternionf(attitude)
        val deltaQ = axisAngle(toRad(cmd[0] * dt), 1f, 0f, 0f)
            .mul(axisAngle(toRad(cmd[1] * dt), 0f, 0f, -1f))
            .mul(axisAngle(toRad(cmd[2] * dt), 0f, -1f, 0f))
        attitude.mul(deltaQ).normalize()

        // Measure actual motion -> next frame's D-term.
        val m = AttitudeMath.bodyRatesDps(oldAtt, attitude, dt)
        angle.measuredDps[0] = m[0]
        angle.measuredDps[1] = m[1]
        angle.measuredDps[2] = m[2]
    }

    /**
     * Optional AUX-driven mode cycle. Rising edge on the channel selected by
     * [FpvConfig.modeSwitchAxis] advances ACRO -> ANGLE -> HORIZON -> ACRO and
     * re-baselines the self-level plane (no attitude jump).
     */
    private fun handleModeSwitch(ch: StickChannels) {
        val idx = cfg.modeSwitchAxis
        if (idx < 0) {
            currentMode = cfg.flightMode
            return
        }
        val v = ch.aux.getOrElse(idx) { 0f }
        val high = v > 0.5f
        if (high && !modeSwitchWasHigh) {
            currentMode = when (currentMode) {
                FlightMode.ACRO -> FlightMode.ANGLE
                FlightMode.ANGLE -> FlightMode.HORIZON
                FlightMode.HORIZON -> FlightMode.ACRO
            }
            angle.rebaseline(attitude)
        }
        modeSwitchWasHigh = high
    }

    private fun axisAngle(angle: Float, x: Float, y: Float, z: Float): Quaternionf =
        Quaternionf().rotateAxis(angle, x, y, z)

    private fun toRad(deg: Float): Float = (deg * PI / 180.0).toFloat()
}
