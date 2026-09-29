/*
 * FPV Craft - MIT
 * Client entrypoint: owns the config, the input manager and the flight core,
 * and routes one normalized channel frame to the flight core every rendered
 * frame. Pure client, no entities.
 */
package dev.fpv.client

import dev.fpv.client.gui.FpvConfigScreen
import dev.fpv.client.osd.FpvOsd
import dev.fpv.flight.FlightController
import dev.fpv.flight.FpvConfig
import dev.fpv.input.InputManager
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.KeyMapping
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import com.mojang.blaze3d.platform.InputConstants
import org.lwjgl.glfw.GLFW

object FpvClient : ClientModInitializer {

    val config = FpvConfig.load()
    val flight = FlightController(config)
    val input = InputManager(config)

    /** Current commanded throttle (normalized; read by movement mixin + OSD). */
    @JvmField
    var throttle = 0f

    /**
     * Placeholder ARM binding (raw radio axis index, -1 = off). The flight-core
     * arm/disarm feature is not implemented yet; the config screen binds and
     * shows it so the control exists without being a dead button.
     */
    @JvmField
    var armAxis = -1

    private lateinit var toggleKey: KeyMapping
    private lateinit var settingsKey: KeyMapping

    private var lastNanos = 0L

    override fun onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.fpv.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyMapping.Category.MISC,
            )
        )
        settingsKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.fpv.settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                KeyMapping.Category.MISC,
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { mc ->
            while (toggleKey.consumeClick()) {
                config.enabled = !config.enabled
                playToggle(mc)
            }
            while (settingsKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(FpvConfigScreen(null))
            }
            tick(mc)
        })
    }

    /** Per-tick: engage/disengage based on elytra state. */
    private fun tick(mc: Minecraft) {
        val p = mc.player
        if (p == null) {
            flight.disengage()
            return
        }
        val flying = p.isFallFlying
        if (flying && config.enabled) {
            if (!flight.ready) {
                flight.engage(p.yRot, p.xRot)
            }
        } else if (flight.ready) {
            flight.disengage()
        }
    }

    /**
     * Per-frame: poll input (always, so the config screen can show live axes),
     * then integrate attitude only while flying and no GUI blocks the view.
     * Called from GameRendererMixin at renderLevel HEAD.
     */
    fun onFrame(mc: Minecraft) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) {
            1f / 60f
        } else {
            ((now - lastNanos) / 1_000_000_000.0).toFloat()
        }.coerceIn(0.0005f, 0.1f)
        lastNanos = now

        // Always read input: the calibration/config screen needs live axes even
        // though the flight itself is paused while a screen is open.
        val channels = input.poll(dt)
        throttle = channels.throttle

        val p = mc.player
        if (p == null || !flight.ready) return

        // Pause the drone while a GUI/inventory is open, but keep polling above.
        if (mc.screen != null) return

        flight.step(channels, dt)
    }

    private fun playToggle(mc: Minecraft) {
        mc.soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f)
        )
    }
}
