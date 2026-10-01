/*
 * FPV Craft - MIT
 *
 * One-shot event beeps (module 3). Uses vanilla MC sounds via SimpleSoundInstance.forUI so no
 * new audio assets are required; the event *choice + rhythm* is the clean-room MotorTone logic
 * (headless [38]). ARM = short double beep, DISARM = single low beep, BAT_LOW / BAT_CRIT /
 * RX_LOST as classified. Rhythm is self-timed (frame-gated), no GPL beep array copied.
 */
package dev.fpv.client

import dev.fpv.flight.MotorTone
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

object FpvBeeper {
    private var lastBeepAtMs = 0L
    private var wasArmed = false

    /** Call every frame with the current state; fires the beep that belongs to this transition. */
    fun update(mc: Minecraft, cellV: Float, armed: Boolean, rxFail: Boolean, enabled: Boolean) {
        if (!enabled) { wasArmed = armed; return }
        val now = System.currentTimeMillis()
        val justArmed = armed && !wasArmed
        val justDisarmed = !armed && wasArmed
        wasArmed = armed

        val ev = MotorTone.event(cellV, justArmed, justDisarmed, rxFail)
        when (ev) {
            MotorTone.Beep.ARM -> { beep(mc, SoundEvents.NOTE_BLOCK_HARP, 1.0f); scheduleSecond(mc, SoundEvents.NOTE_BLOCK_HARP, 1.0f, 150L) }
            MotorTone.Beep.DISARM -> beep(mc, SoundEvents.NOTE_BLOCK_HARP, 0.6f)
            MotorTone.Beep.BAT_LOW -> scheduleRepeating(mc, SoundEvents.NOTE_BLOCK_BASS, 0.8f, 1500L)
            MotorTone.Beep.BAT_CRIT -> scheduleRepeating(mc, SoundEvents.NOTE_BLOCK_BASS, 1.2f, 800L)
            MotorTone.Beep.RX_LOST -> beep(mc, SoundEvents.NOTE_BLOCK_BELL, 0.5f)
            MotorTone.Beep.NONE -> {}
        }
    }

    private fun beep(mc: Minecraft, sound: net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent>, pitch: Float) {
        mc.soundManager.play(SimpleSoundInstance.forUI(sound, pitch))
    }

    private fun scheduleSecond(mc: Minecraft, sound: net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent>, pitch: Float, delayMs: Long) {
        lastBeepAtMs = System.currentTimeMillis() + delayMs
        pending = { beep(mc, sound, pitch) }
    }

    private fun scheduleRepeating(mc: Minecraft, sound: net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent>, pitch: Float, periodMs: Long) {
        if (now() - lastRepeatMs > periodMs) {
            beep(mc, sound, pitch)
            lastRepeatMs = now()
        }
    }

    // Pending deferred beep (e.g. the second half of the ARM double-tone).
    private var pending: (() -> Unit)? = null
    private var lastRepeatMs = 0L

    /** Drain any deferred beep; call from onFrame. */
    fun pump() {
        val p = pending ?: return
        if (System.currentTimeMillis() >= lastBeepAtMs) { pending = null; p() }
    }

    private fun now() = System.currentTimeMillis()
}
