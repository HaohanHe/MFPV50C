/*
 * FPV Craft - MIT
 * Input abstraction: every device backend turns raw device state into logical
 * StickChannels. The flight core depends only on this interface.
 */
package dev.fpv.input

/**
 * A device backend (USB radio via GLFW, or the built-in keyboard/mouse fallback).
 * Implementations are polled once per rendered frame on the render thread.
 */
interface InputProvider {
    /** Human-readable backend name. */
    val name: String

    /** Read and normalize the current channel state. */
    fun poll(dt: Float): StickChannels
}

/** Snapshot of a connected joystick for device-selection UIs. */
data class JoystickInfo(
    @JvmField val id: Int,
    @JvmField val name: String,
    @JvmField val guid: String,
    @JvmField val axisCount: Int,
    @JvmField val buttonCount: Int,
    @JvmField val isGamepad: Boolean,
)
