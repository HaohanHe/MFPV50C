/*
 * FPV Craft - MIT
 *
 * Continuous motor whine (module 3). Loops the short synthesized motor_whine.ogg; tick() sets
 * pitch from the current normalized motor speed (idle base -> full = fullMul, 3D uses |m|) and
 * volume; at zero throttle it fades out and stops. The pitch->speed mapping is the same pure
 * MotorTone logic headless [38] pins down.
 */
package dev.fpv.client

import dev.fpv.flight.MotorTone
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource

class MotorWhineSound(
    event: SoundEvent,
    private val basePitch: Float,
    private val fullMul: Float,
) : AbstractTickableSoundInstance(event, SoundSource.AMBIENT, RandomSource.create()) {

    @Volatile private var speed = 0f

    init {
        looping = true
        volume = 0f
        pitch = basePitch
        relative = true
    }

    /** Called each frame with normalized motor speed m in [-1,1]. */
    fun updateSpeed(m: Float) { speed = m }

    override fun tick() {
        val vol = if (speed <= 0.02f) 0f else 0.35f + 0.4f * Math.abs(speed)
        volume = vol
        pitch = MotorTone.pitchForSpeed(speed, basePitch, fullMul)
        if (speed <= 0.01f) stop()
    }
}
