/*
 * FPV Craft - MIT
 * Chooses between the USB-radio backend and the keyboard/mouse fallback, with
 * automatic fall-back when the radio is unplugged (hot-plug is detected by
 * polling present() every frame rather than installing GLFW callbacks, so the
 * game's own GLFW state is never disturbed).
 */
package dev.fpv.input

import dev.fpv.flight.FpvConfig

class InputManager(private val cfg: FpvConfig) {

    val radio = GlfwJoystickProvider(cfg)
    val keyboard = KeyboardMouseProvider(cfg)

    /** Latest merged channel frame, read by the OSD/GUI for live bars. */
    var last = StickChannels()
        private set

    /** True when the radio (not keyboard) produced the last frame. */
    var radioActive: Boolean = false
        private set

    fun poll(dt: Float): StickChannels {
        if (cfg.useRadio && radio.present()) {
            last = radio.poll(dt)
            radioActive = true
            return last
        }
        radioActive = false
        last = keyboard.poll(dt)
        return last
    }

    /** Latest normalized frame without re-polling (live bars in the GUI). */
    fun lastFrame(): StickChannels = last

    /** Raw axes of the USB radio for the config screen / calibration. */
    fun rawAxes(): FloatArray = if (cfg.useRadio) radio.refreshRaw() else FloatArray(0)

    fun radioAxisCount(): Int = radio.axisCount

    fun radioDeviceName(): String = radio.deviceName

    // ---- Raw source access for the wizard / monitor (read-only snapshots). ----
    fun axes(): FloatArray = radio.lastAxes
    fun buttons(): ByteArray = radio.lastButtons
    fun hats(): ByteArray = radio.lastHats
    fun buttonCount(): Int = radio.buttonCount
    fun hatCount(): Int = radio.hatCount
    fun fingerprint(): String = radio.fingerprint
}
