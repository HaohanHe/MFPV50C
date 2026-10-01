/*
 * FPV Craft - MIT
 *
 * One-shot event beeps (module 3). ARM/DISARM are transition beeps; continuous alarms
 * (RX_LOST / BAT_LOW / BAT_CRIT) go through the throttled, disarmed-gated BeepScheduler so they
 * never play per-frame and stay silent while disarmed. Rhythm is self-timed, no GPL beep array.
 */
package dev.fpv.client

import dev.fpv.flight.BeepScheduler
import dev.fpv.flight.MotorTone
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

object FpvBeeper {
    private var lastBeepAtMs = 0L
    private var wasArmed = false
    private var lastRepeatMs = 0L

    /** Call every frame with the current state; fires the beep that belongs to this transition. */
    fun update(mc: Minecraft, cellV: Float, armed: Boolean, rxFail: Boolean, enabled: Boolean) {
        val now = System.currentTimeMillis()
        if (!enabled) { wasArmed = armed; return }
        val justArmed = armed && !wasArmed
        val justDisarmed = !armed && wasArmed
        wasArmed = armed

        // Transition beeps (always, even disarmed).
        if (justArmed) { beep(mc, SoundEvents.NOTE_BLOCK_HARP, 1.0f); scheduleSecond(mc, SoundEvents.NOTE_BLOCK_HARP, 1.0f, 150L) }
        else if (justDisarmed) beep(mc, SoundEvents.NOTE_BLOCK_HARP, 0.6f)

        // Continuous alarms: throttled + disarmed-gated.
        val alarm = BeepScheduler.continuousAlarm(now, armed, rxFail, cellV, lastRepeatMs)
        when (alarm) {
            MotorTone.Beep.RX_LOST -> { beep(mc, SoundEvents.NOTE_BLOCK_BELL, 0.5f); lastRepeatMs = now }
            MotorTone.Beep.BAT_CRIT -> { beep(mc, SoundEvents.NOTE_BLOCK_BASS, 1.2f); lastRepeatMs = now }
            MotorTone.Beep.BAT_LOW -> { beep(mc, SoundEvents.NOTE_BLOCK_BASS, 0.8f); lastRepeatMs = now }
            else -> {}
        }
    }

    private fun beep(mc: Minecraft, sound: net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent>, pitch: Float) {
        mc.soundManager.play(SimpleSoundInstance.forUI(sound, pitch))
    }

    private fun scheduleSecond(mc: Minecraft, sound: net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent>, pitch: Float, delayMs: Long) {
        lastBeepAtMs = System.currentTimeMillis() + delayMs
        pending = { beep(mc, sound, pitch) }
    }

    // Pending deferred beep (e.g. the second half of the ARM double-tone).
    private var pending: (() -> Unit)? = null

    /** Drain any deferred beep; call from onFrame. */
    fun pump() {
        val p = pending ?: return
        if (System.currentTimeMillis() >= lastBeepAtMs) { pending = null; p() }
    }
}
