/*
 * FPV Craft - MIT
 * Built-in fallback: mouse-look -> roll/pitch, A/D -> yaw, W/S -> throttle.
 * Used when no USB radio is selected/attached. Kept independent of the radio
 * backend so the flight core only ever sees StickChannels.
 */
package dev.fpv.input

import dev.fpv.flight.FpvConfig
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

class KeyboardMouseProvider(private val cfg: FpvConfig) : InputProvider {

    override val name: String = "键盘 / 鼠标"

    private var lastCX = 0.0
    private var lastCY = 0.0
    private var haveCursor = false

    override fun poll(dt: Float): StickChannels {
        val mc = Minecraft.getInstance()
        val ch = StickChannels(present = true, sourceName = name)

        // Mouse look only while actually in-game with a captured cursor.
        var roll = 0f
        var pitch = 0f
        val inWorld = mc.player != null && mc.screen == null
        if (inWorld) {
            val cx = DoubleArray(1)
            val cy = DoubleArray(1)
            GLFW.glfwGetCursorPos(mc.window.handle(), cx, cy)
            if (haveCursor) {
                val dx = cx[0] - lastCX
                val dy = cy[0] - lastCY
                val denom = cfg.mouseSensitivity * dt * 60f
                roll = (dx / denom).toFloat().coerceIn(-1f, 1f)
                pitch = (dy / denom).toFloat().coerceIn(-1f, 1f)
            }
            lastCX = cx[0]
            lastCY = cy[0]
            haveCursor = true
        } else {
            haveCursor = false
        }

        var yaw = 0f
        if (mc.options.keyRight.isDown) yaw += 1f
        if (mc.options.keyLeft.isDown) yaw -= 1f

        val throttle = if (cfg.reversible3D) {
            // Reversible: W = +forward thrust, S = -reverse thrust, rest = 0.
            when {
                mc.options.keyUp.isDown -> 1f
                mc.options.keyDown.isDown -> -1f
                else -> 0f
            }
        } else {
            when {
                mc.options.keyUp.isDown -> 1f
                mc.options.keyDown.isDown -> 0f
                else -> cfg.idleThrottle
            }
        }

        ch.roll = roll
        ch.pitch = pitch
        ch.yaw = yaw
        ch.throttle = throttle
        return ch
    }
}
