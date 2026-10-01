/*
 * FPV Craft - MIT
 *
 * Module 3 audio model (clean-room; no GPL beep arrays copied). Maps normalized motor speed
 * to a whine pitch multiplier, and classifies one-shot beep events by battery / link state.
 * The actual SoundInstance playback is MC-side and needs a real machine; this pure logic is
 * what headless pins down.
 */
package dev.fpv.flight

object MotorTone {

    /**
     * Whine pitch multiplier for a normalized motor speed m in 0..1 (3D signed uses |m|).
     * Pitch grows monotonically with speed: idle=base, full=fullMul.
     */
    fun pitchForSpeed(m: Float, basePitch: Float, fullMul: Float): Float {
        val s = kotlin.math.abs(m.coerceIn(-1f, 1f))
        return basePitch * (1f + (fullMul - 1f) * s)
    }

    /** Beep event classification from battery cell voltage / link state. */
    enum class Beep { NONE, ARM, DISARM, BAT_LOW, BAT_CRIT, RX_LOST }

    /**
     * Decide the beep that should fire given the state transitions.
     * @param cellV current per-cell voltage
     * @param armed just-armed / just-disarm flags
     * @param rxFail link lost
     */
    fun event(cellV: Float, justArmed: Boolean, justDisarmed: Boolean, rxFail: Boolean): Beep = when {
        rxFail -> Beep.RX_LOST
        justArmed -> Beep.ARM
        justDisarmed -> Beep.DISARM
        cellV < 3.3f -> Beep.BAT_CRIT
        cellV < 3.6f -> Beep.BAT_LOW
        else -> Beep.NONE
    }
}
