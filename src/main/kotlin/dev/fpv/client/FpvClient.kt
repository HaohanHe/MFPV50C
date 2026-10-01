/*
 * FPV Craft - MIT
 * Client entrypoint: owns the config, the input manager and the flight core,
 * and routes one normalized channel frame to the flight core every rendered
 * frame. Pure client, no entities.
 */
package dev.fpv.client

import dev.fpv.client.gui.FpvConfigScreen
import dev.fpv.replay.CinematicExport
import dev.fpv.replay.FlightRecorder
import dev.fpv.replay.ReplayManager
import dev.fpv.replay.ReplayScreen
import dev.fpv.flight.BatteryModel
import dev.fpv.flight.BatteryStage
import dev.fpv.flight.Defaults
import dev.fpv.flight.FlightController
import dev.fpv.flight.FlightFunction
import dev.fpv.flight.FpvConfig
import dev.fpv.flight.LinkMonitor
import dev.fpv.flight.LinkState
import dev.fpv.flight.ModesController
import dev.fpv.flight.SafetyGuards
import dev.fpv.flight.TelemetryLogger
import dev.fpv.flight.TelemetrySample
import dev.fpv.flight.ThrottleCurve
import dev.fpv.flight.ThrottleLimiter
import dev.fpv.race.RaceManager
import net.fabricmc.loader.api.FabricLoader
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

    // ---- Remote setback (rubber-band) event bus ----
    // Written by the position-packet mixin (no MC types on the flight side),
    // consumed by LocalPlayerMixin each tick which owns the persistent logic.
    private var setbackDistance = 0.0
    private var setbackNanos = 0L

    /** Called by the client-packet mixin when a rubber-band snap is detected. */
    fun notifyServerSetback(distanceBlocks: Double) {
        setbackDistance = distanceBlocks
        setbackNanos = System.nanoTime()
    }

    /** Consume a pending setback event (debounced by cooldownMs); distance or null. */
    fun consumeServerSetback(cooldownMs: Long): Double? {
        if (setbackNanos == 0L) return null
        val elapsedMs = (System.nanoTime() - setbackNanos) / 1_000_000.0
        if (elapsedMs < cooldownMs) return null
        val d = setbackDistance
        setbackNanos = 0L
        return d
    }

    /**
     * Arming-check blocker codes for the current frame (Betaflight arming-disable
     * flags, client equivalent). Empty = arming allowed. The codes are only
     * enforced on the disarmed -> armed transition (we never silently refuse to
     * unlock without a reason; reasons are surfaced on the OSD / config screen).
     */
    @JvmField
    var armingBlockers: List<String> = emptyList()

    /** Keyboard arming additionally requires a low throttle (BF THROTTLE check). */
    private const val ARMING_THROTTLE_MAX = 0.02f

    /** Recompute arming blockers from the current input / battery / link state. */
    fun refreshArmingBlockers() {
        val b = mutableListOf<String>()
        if (config.useRadio && !input.radioActive) b += "NO_RX"
        if (config.useRadio && !config.isCalibrated()) b += "NOT_CALIBRATED"
        if (battery.stage == BatteryStage.CRITICAL) b += "BATTERY_CRITICAL"
        if (link.state == LinkState.FAILSAFE) b += "RX_FAILSAFE"
        armingBlockers = b
    }

    /** Human-readable labels for [armingBlockers] (for OSD / UI). */
    fun armingBlockerText(): List<String> = armingBlockers.map {
        when (it) {
            "NO_RX" -> "未检测到遥控器（键盘备用：按 V 开启 FPV）"
            "NOT_CALIBRATED" -> "遥控器未校准：设置 → 摇杆校准"
            "BATTERY_CRITICAL" -> "电池电量过低，禁止解锁"
            "RX_FAILSAFE" -> "链路失效（failsafe），禁止解锁"
            else -> it
        }
    }

    /**
     * Apply an arm REQUEST with the arming-check gate. Only the disarmed -> armed
     * transition is blocked; once armed the request is honored (other systems
     * handle in-flight failsafe / low-battery).
     */
    private fun applyArmRequest(wantArm: Boolean) {
        if (!wantArm) { armed = false; return }
        if (armed) { armed = true; return }
        armed = armingBlockers.isEmpty()
    }

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

    /** Data-driven channel -> function (Modes) router. */
    private val modes = ModesController()

    /** Modes router edge/level bookkeeping. */
    private var beeperAccSec = 0f
    private var headfreeWas = false

    /** Power-response filter state: effective thrust tracks commanded throttle. */
    private var powerFiltered = 0f

    /** Accumulated flight time while engaged, seconds (OSD + logger). */
    var flightTimeSec = 0f
        private set

    /** True while the LAND failsafe override holds a descent throttle. */
    var failsafeLand = false
        private set

    private lateinit var toggleKey: KeyMapping
    private lateinit var settingsKey: KeyMapping
    private lateinit var armKey: KeyMapping
    private lateinit var recordKey: KeyMapping
    private lateinit var replayKey: KeyMapping

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
        recordKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.fpv.record",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KeyMapping.Category.MISC,
            )
        )
        replayKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.fpv.replay",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
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
            while (recordKey.consumeClick()) {
                if (FlightRecorder.recording) FlightRecorder.stop() else FlightRecorder.start()
            }
            while (replayKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(ReplayScreen(null))
            }
            FlightRecorder.pollAuxTrigger(input.lastFrame())
            // Key arming is available only when no arm switch/button/mode binding exists.
            if (!config.armBindingAutomatic()) {
                while (armKey.consumeClick()) {
                    if (!armed) {
                        // BF-style checks: no blockers + throttle low before arming.
                        refreshArmingBlockers()
                        val throttleLow = input.lastFrame().throttle <= ARMING_THROTTLE_MAX
                        if (armingBlockers.isEmpty() && throttleLow) {
                            armed = true
                            playToggle(mc)
                        }
                    } else {
                        armed = false
                        playToggle(mc)
                    }
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

        // ---- Data-driven Modes routing ----
        // Recompute arming-check blockers (state from prior frame) before gating.
        refreshArmingBlockers()
        val bindings = config.activeProfile()?.modes ?: emptyList()
        val modeRes = modes.evaluate(channels, bindings)
        if (bindings.isNotEmpty()) {
            // ARM (PREARM gate + arming-check blockers applied).
            applyArmRequest(modeRes.armed)
            // Flight-mode select (ANGLE/HORIZON/ACRO); null = keep current.
            modeRes.desiredMode?.let { if (flight.ready) flight.requestMode(it) }
            // HEADFREE level -> drive the runtime head-free flag.
            if (modeRes.headfree != headfreeWas) {
                config.headfreeEnabled = modeRes.headfree
                headfreeWas = modeRes.headfree
            }
            // EDGE actions fire once per rising transition.
            repeat(modeRes.edgeCount(FlightFunction.HEADADJ)) { flight.triggerHeadingAdjust() }
            repeat(modeRes.edgeCount(FlightFunction.TURTLE)) { flight.turtleRight() }
            // BEEPER level: repeating audible locate cue.
            if (modeRes.beeper) {
                beeperAccSec += dt
                if (beeperAccSec >= Defaults.BEEPER_INTERVAL_SEC) {
                    beeperAccSec = 0f
                    playBeeper(mc)
                }
            } else {
                beeperAccSec = Defaults.BEEPER_INTERVAL_SEC
            }
            // Pilot-forced failsafe level: cut the flight (DROP-equivalent).
            if (modeRes.manualFailsafe) {
                armed = false
                flight.disengage()
            }
        } else {
            // Legacy arm sources: a bound HID button, then a level aux axis.
            val wantArm = when {
                config.armButtonIndex >= 0 -> {
                    val btns = input.buttons()
                    config.armButtonIndex in btns.indices &&
                        btns[config.armButtonIndex].toInt() != 0
                }
                config.armSwitchAxis >= 0 ->
                    channels.aux.getOrElse(config.armSwitchAxis) { 0f } >
                        Defaults.SWITCH_TRIGGER
                else -> armed
            }
            applyArmRequest(wantArm)
        }

        // ---- Failsafe / battery / telemetry run every frame, even with a GUI
        // open (flight.step itself stays frozen below). ----
        val radioPresent = config.useRadio && input.radioActive
        link.update(radioPresent, dt)
        handleFailsafe()

        // Clamp incoming channels so an out-of-range/non-finite value becomes
        // neutral before it reaches the rate math (defense in depth).
        channels.roll = SafetyGuards.channel(channels.roll)
        channels.pitch = SafetyGuards.channel(channels.pitch)
        channels.yaw = SafetyGuards.channel(channels.yaw)
        val thrLo = if (config.reversible3D) -1f else 0f
        channels.throttle = if (channels.throttle.isFinite())
            channels.throttle.coerceIn(thrLo, 1f) else 0f

        // Throttle curve + boost on the raw channel, then the limiter
        // (published scale/limit).
        throttle = throttleCurve.apply(channels.throttle, dt, config.reversible3D)
        throttle = ThrottleLimiter.apply(
            throttle, config.throttleLimit ?: dev.fpv.flight.ThrottleLimitConfig(),
            config.reversible3D,
        )
        if (failsafeLand) throttle = Defaults.FAILSAFE_LAND_THROTTLE

        // Instantaneous power: first-order throttle->thrust response. Higher
        // instantPower = shorter time constant = snappier power delivery.
        val af = config.activeAirframe()
        val tau = (0.08f / af.instantPower.coerceAtLeast(0.05f))
        val a = (dt / (tau + dt)).coerceIn(0f, 1f)
        powerFiltered += a * (throttle - powerFiltered)
        throttle = powerFiltered

        // Armed idle floor (normal mode only): never spool below minThrottle.
        if (flight.ready && armed && !config.reversible3D && !flight.crashRecovering) {
            throttle = throttle.coerceAtLeast(af.minThrottle)
        }

        battery.update(throttle, dt)
        // Battery sag -> rpm derate (vbat/nominal). Nominal pack = cells * full cell.
        run {
            val af = config.activeAirframe()
            val nomCells = af.effectiveCellCount(config.activeBattery().cellCount)
            val nominalV = nomCells * Defaults.VBAT_FULL_CELL
            flight.batteryDerate = if (nominalV > 0.1f) battery.vbat / nominalV else 1f
        }

        if (flight.ready) flightTimeSec += dt

        // Telemetry logger: open/close with the config flag, sample on a fixed period.
        if (config.telemetryEnabled) {
            val modVer = FabricLoader.getInstance().getModContainer("fpv")
                .map { it.metadata.version.friendlyString }.orElse("0.1.0")
            logger.open(modVer, FpvConfig.contentHash(config), config.activeProfileFingerprint)
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

        // Replay transport advances by real time (the offline exporter drives the
        // cursor itself, so we skip the real-time advance while it runs).
        if (!CinematicExport.exporting) ReplayManager.update(dt)

        // Flight-data recorder: only while actually flying, with no GUI open and
        // not already inside a replay session (never record a replay).
        if (flight.ready && mc.screen == null &&
            !ReplayManager.active && !CinematicExport.exporting
        ) {
            FlightRecorder.feed(mc, channels, dt)
        }

        val p = mc.player
        if (p == null || !flight.ready) return

        // Pause the drone while a GUI/inventory is open, but keep polling above.
        if (mc.screen != null) return

        // During an active replay, freeze live flight control; the virtual camera
        // is driven by ReplayManager instead.
        if (ReplayManager.active) return

        try {
            flight.step(channels, dt, throttle)
        } catch (e: Exception) {
            // Severe runtime fault: make sure it reaches the blackbox before unwinding.
            if (!logger.isOpen()) {
                val modVer = FabricLoader.getInstance().getModContainer("fpv")
                    .map { it.metadata.version.friendlyString }.orElse("0.1.0")
                logger.open(modVer, FpvConfig.contentHash(config), config.activeProfileFingerprint)
            }
            logger.crashMark("flight.step ${e.javaClass.simpleName}: ${e.message ?: ""}")
            throw e
        }
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

    /** One audible locate-beep cue (repeats while the BEEPER function is active). */
    private fun playBeeper(mc: Minecraft) {
        mc.soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.4f)
        )
    }
}
