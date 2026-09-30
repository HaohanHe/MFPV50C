/*
 * FPV Craft - MIT
 * Prop-wash / washout disturbance as a Minecraft-free, headless-testable pure model.
 *
 * Real multirotor washout occurs when the craft descends into, or settles inside, its
 * own recirculating downwash at LOW horizontal airspeed and HIGH power: the disk loses
 * clean inflow, the blades stall unevenly and the airframe shimmies at 15-40 Hz while
 * thrust momentarily drops. A craft in clean fast forward flight has fresh inflow and
 * never sees it. So the disturbance strength is CONDITION-DRIVEN:
 *
 *   descentF = clamp(descendRate / descentBpt)        // falling through own wash
 *   slowF    = clamp(1 - horizSpeed / slowBpt)         // settled, no clean inflow
 *   thrF     = clamp((throttle - 0.4) / 0.5)           // enough power to blow wash
 *   strength = enabled ? descentF * slowF * thrF : 0
 *
 * The model outputs roll/pitch gyro noise (two sines inside 15-40 Hz) scaled by
 * strength and a proportional thrust drop. All coefficients are tunable and the whole
 * effect can be switched off. Clean-room engineering values pending real-machine feel.
 */
package dev.fpv.flight

import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.sin

class PropwashModel(val af: AirframeProfile) {

    private fun unit(x: Float) = x.coerceIn(0f, 1f)

    /** Normalized washout strength 0..1, last step. */
    var strength: Float = 0f
        private set

    /** Current thrust multiplier (1 - drop*strength). */
    var thrustScale: Float = 1f
        private set

    private var t = 0.0

    /**
     * @param vy         vertical velocity (blocks/tick; negative = descending)
     * @param horizSpeed horizontal airspeed (blocks/tick)
     * @param throttle   0..1
     * @param dt         seconds
     * @return gyro disturbance dps on [pitch, roll, yaw]
     */
    fun step(vy: Float, horizSpeed: Float, throttle: Float, dt: Float): Vector3f {
        if (!af.propwashEnabled || dt <= 0f) {
            strength = 0f; thrustScale = 1f
            return Vector3f(0f, 0f, 0f)
        }
        t += dt
        val descend = -vy.coerceAtMost(0f)
        val descentF = unit(descend / af.propwashDescentBpt)
        val slowF = unit(1f - horizSpeed / af.propwashSlowBpt)
        val thrF = unit((throttle - 0.4f) / 0.5f)
        strength = descentF * slowF * thrF
        thrustScale = 1f - af.propwashThrustDrop * strength

        val fR = af.propwashFreqLowHz.toDouble()
        val fP = af.propwashFreqHighHz.toDouble()
        val amp = af.propwashAmpDps * strength
        // pitch = index 0, roll = index 1 (BodyAxis order).
        val pitch = (amp * sin(2.0 * PI * fP * t)).toFloat()
        val roll = (amp * sin(2.0 * PI * fR * t + 1.1)).toFloat()
        return Vector3f(pitch, roll, 0f)
    }
}
