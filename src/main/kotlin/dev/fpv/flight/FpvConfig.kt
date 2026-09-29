/*
 * FPV Craft - MIT
 * All tunable parameters, persisted as JSON in the Fabric config directory.
 * Uses Gson (bundled with Minecraft); clean-room values only.
 */
package dev.fpv.flight

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/** Per-axis actual-rates parameters (Betaflight Configurator units). */
data class AxisRates(
    /** Center sensitivity, deg/s. */
    var center: Float = 70f,
    /** Max rate at full stick, deg/s. */
    var max: Float = 670f,
    /** Expo 0..1. */
    var expo: Float = 0f,
)

/** Raw-axis mapping and calibration for one logical channel. */
data class ChannelCalib(
    /** Raw joystick axis index, -1 = unmapped. */
    var axisIndex: Int = -1,
    var reversed: Boolean = false,
    var rawMin: Float = -1f,
    var rawMid: Float = 0f,
    var rawMax: Float = 1f,
    /** Center deadband in raw-axis units. */
    var deadzone: Float = 0.02f,
)

class FpvConfig {
    /** Master switch for FPV mode. */
    var enabled = true

    // Acro rates (actual-rates published defaults)
    var roll = AxisRates(center = 70f, max = 670f, expo = 0f)
    var pitch = AxisRates(center = 70f, max = 670f, expo = 0f)
    var yaw = AxisRates(center = 70f, max = 670f, expo = 0f)

    /** Mouse delta (pixels per frame) -> stick deflection gain. */
    var mouseSensitivity = 28f

    /** Throttle when no forward key held. */
    var idleThrottle = 0.15f

    // Translational physics (applied client-side)
    /** Forward thrust acceleration at full throttle. */
    var thrustPower = 1.1f

    /** Quadratic drag coefficient: decel = dragK * v^2. */
    var dragK = 0.015f

    // ---- Input layer ----
    /** Prefer a USB radio; false = keyboard/mouse fallback. */
    var useRadio = false

    /** GLFW joystick id; -1 = first present device. */
    var joystickId = -1

    /** Transmitter hand layout 1..4, default Mode 2. */
    var handMode = 2

    /**
     * Per-logical-channel calibration, indexed by
     * StickChannels.ROLL/PITCH/YAW/THROTTLE. Defaults assume a generic HID
     * joystick with the common 4-axis order (yaw, throttle, roll, pitch);
     * calibration and reverse switches correct per-device differences.
     */
    var channels = listOf(
        ChannelCalib(axisIndex = 2),                 // roll  -> raw axis 2
        ChannelCalib(axisIndex = 3, reversed = true),// pitch -> raw axis 3
        ChannelCalib(axisIndex = 0),                 // yaw   -> raw axis 0
        ChannelCalib(axisIndex = 1, reversed = true),// throttle -> raw axis 1
    )

    // ---- Flight modes ----
    var flightMode: FlightMode = FlightMode.ACRO

    /** Reversible-3D: motors can reverse, throttle center = zero. */
    var reversible3D = false

    /** Maximum self-level inclination, degrees (published common default 50). */
    var angleMaxDeg = 50f

    /**
     * Angle-mode proportional gain (engineering starting value; tuned in-game,
     * not a third-party default claim).
     */
    var angleP = 4.0f

    /** Enable horizon blending instead of hard angle. */
    var horizonMix = false

    /** Throttle center deadband in reversible-3D mode (normalized units). */
    var threeDThrottleDeadband = 0.05f

    /** Optional AUX channel index (raw joystick axis) used to cycle modes; -1 = off. */
    var modeSwitchAxis = -1

    // ---- Persistence ----
    fun save() {
        Files.createDirectories(configPath().parent)
        Files.writeString(configPath(), gson.toJson(this))
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        private fun configPath(): Path =
            FabricLoader.getInstance().configDir.resolve("fpvcraft.json")

        fun load(): FpvConfig = try {
            val p = configPath()
            if (Files.exists(p))
                gson.fromJson(Files.readString(p), FpvConfig::class.java) ?: FpvConfig()
            else FpvConfig()
        } catch (e: Exception) {
            FpvConfig()
        }
    }
}
