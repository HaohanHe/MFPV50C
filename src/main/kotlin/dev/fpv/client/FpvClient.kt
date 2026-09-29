/*
 * FPV Craft - MIT
 * Client entrypoint: owns the config, the input manager and the flight core,
 * and routes one normalized channel frame to the flight core every rendered
 * frame. Pure client, no entities.
 */
package dev.fpv.client

import dev.fpv.client.gui.FpvConfigScreen
import dev.fpv.flight.BatteryModel
import dev.fpv.flight.Defaults
import dev.fpv.flight.FlightController
import dev.fpv.flight.FpvConfig
import dev.fpv.flight.LinkMonitor
import dev.fpv.flight.LinkState
import dev.fpv.flight.TelemetryLogger
import dev.fpv.flight.TelemetrySample
import dev.fpv.flight.ThrottleCurve
import dev.fpv.flight.ThrottleLimiter
import dev.fpv.race.RaceManager
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
     * Arm state. The craft cannot engage while disarmed; disarming mid-flight
     * cuts the flight (returns to the vanilla glide/fall). When an arm switch
     * is configured its position is authoritative; otherwise the arm key
     * toggles state.
     */
    @JvmField
    var armed = false

    /** Failsafe / virtual-link-quality state machine. */
    @JvmField
    val link = LinkMonitor(
        holdMs = config.failsafe?.holdMs ?: Defaults.FAILSAFE_HOLD_MS,
    )

    /** Virtual battery pack model. */
    @JvmField
    val battery = BatteryModel(config)

    /** Blackbox-style session logger. */
    @JvmField
    val logger = TelemetryLogger()

    private val throttleCurve = ThrottleCurve(config)

    /** Accumulated flight time while engaged, seconds (OSD + logger). */
    var flightTimeSec = 0f
        private set

    /** True while the LAND failsafe override holds a descent throttle. */
    var failsafeLand = false
        private set

    private lateinit var toggleKey: KeyMapping
    private lateinit var settingsKey: KeyMapping
    private lateinit var armKey: KeyMapping

    private var lastNanos = 0L

    override fun onInitializeClient() {
        // Racing core: hook world rendering (gates + ghost) once.
        RaceManager.registerWorldRendering()

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
        armKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.fpv.arm",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
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
            // Key arming is available only when no arm switch is configured.
            if (config.armSwitchAxis < 0) {
                while (armKey.consumeClick()) {
                    armed = !armed
                    playToggle(mc)
                }
            }
            tick(mc)
            // Racing core detection runs at tick rate too (frame hook below
            // additionally prevents high-speed tunnelling through gates).
            RaceManager.onClientTick(mc)
        })
    }

    /** Per-tick: engage/disengage based on elytra, arm and calibration state. */
    private fun tick(mc: Minecraft) {
        val p = mc.player
        if (p == null) {
            flight.disengage()
            return
        }
        // Radio input requires calibration; the keyboard fallback does not.
        val inputReady = !config.useRadio || config.isCalibrated()
        val canFly = p.isFallFlying && config.enabled && armed && inputReady
        if (canFly) {
            if (!flight.ready) flight.engage(p.yRot, p.xRot)
        } else if (flight.ready) {
            flight.disengage()
        }
    }

    /**
     * Per-frame: poll input (always, so the config screen shows live axes),
     * follow the arm switch when configured, then integrate attitude only
     * while flying with no GUI open. Called from GameRendererMixin at the
     * renderLevel HEAD.
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

        // Arm switch: level-based (two-position switch), raw axis indexed.
        val armAxisIdx = config.armSwitchAxis
        if (armAxisIdx >= 0) {
            armed = channels.aux.getOrElse(armAxisIdx) { 0f } > Defaults.SWITCH_TRIGGER
        }

        // ---- Failsafe / battery / telemetry run every frame, even with a GUI
        // open (flight.step itself stays frozen below). ----
        val radioPresent = config.useRadio && input.radioActive
        link.update(radioPresent, dt)
        handleFailsafe()

        // Throttle curve + boost on the raw channel, then the limiter
        // (published scale/limit).
        throttle = throttleCurve.apply(channels.throttle, dt, config.reversible3D)
        throttle = ThrottleLimiter.apply(
            throttle, config.throttleLimit ?: dev.fpv.flight.ThrottleLimitConfig(),
            config.reversible3D,
        )
        if (failsafeLand) throttle = Defaults.FAILSAFE_LAND_THROTTLE

        battery.update(throttle, dt)

        if (flight.ready) flightTimeSec += dt

        // Telemetry logger: open/close with the config flag, sample on a fixed period.
        if (config.telemetryEnabled) {
            logger.open()
            logger.maybeWrite(
                dt,
                TelemetrySample(
                    time = flightTimeSec,
                    rc = floatArrayOf(channels.roll, channels.pitch, channels.yaw, throttle),
                    setpoint = flight.setpointRates.copyOf(),
                    bodyRates = flight.bodyRates.copyOf(),
                    vbat = battery.vbat,
                    mAh = battery.mAhDrawn,
                    lq = link.lq,
                    flightMode = flight.currentMode.id,
                    armed = armed,
                ),
            )
        } else if (logger.isOpen()) {
            logger.close()
        }

        val p = mc.player
        if (p == null || !flight.ready) return

        // Pause the drone while a GUI/inventory is open, but keep polling above.
        if (mc.screen != null) return

        flight.step(channels, dt, throttle)
        // Crash recovery holds a leveling override and cuts the throttle.
        if (flight.crashRecovering) throttle = 0f

        // Frame-accurate gate detection / ghost record & replay.
        RaceManager.onFrame(mc, dt)
    }

    /** Act on a failsafe transition; recover the override once the link is back. */
    private fun handleFailsafe() {
        if (link.consumeTriggered()) {
            val proc = config.failsafe?.procedure ?: Defaults.FAILSAFE_PROCEDURE_DROP
            if (proc == Defaults.FAILSAFE_PROCEDURE_LAND && flight.ready) {
                // Auto-Angle descent: keep flying, hold a descent throttle.
                flight.forceAngleMode()
                failsafeLand = true
            } else {
                // DROP: cut everything.
                armed = false
                flight.disengage()
                failsafeLand = false
            }
        }
        if (failsafeLand && link.state == LinkState.NORMAL) {
            failsafeLand = false
        }
    }

    private fun playToggle(mc: Minecraft) {
        mc.soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f)
        )
    }
}
