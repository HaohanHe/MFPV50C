/*
 * FPV Craft - MIT
 * Native USB-radio backend on top of GLFW's joystick API (bundled with the
 * game via LWJGL, zero extra dependency). Most FPV USB radios / ELRS / FrSky /
 * Spektrum receivers enumerate as generic HID joysticks, which GLFW exposes as
 * raw axes; they are NOT SDL gamepads, so the gamecontroller mapping path is
 * intentionally not used here.
 */
package dev.fpv.input

import dev.fpv.flight.FpvConfig
import org.lwjgl.glfw.GLFW

class GlfwJoystickProvider(private val cfg: FpvConfig) : InputProvider {

    override val name: String = "USB 遥控器 (GLFW)"

    /** Currently resolved joystick id, -1 when none present. */
    var jid: Int = -1
        private set

    var deviceName: String = ""
        private set

    var axisCount: Int = 0
        private set

    private var lastAxes: FloatArray = FloatArray(0)

    /** Resolve the device to use: the configured id, else the first present one. */
    private fun resolve(): Int {
        val want = cfg.joystickId
        var first = -1
        for (j in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST) {
            if (!GLFW.glfwJoystickPresent(j)) continue
            if (first < 0) first = j
            if (j == want) return j
        }
        return first
    }

    /** True when a USB device is currently attached (call after refresh). */
    fun present(): Boolean = resolve() >= 0

    /** Refresh and expose the raw axis vector of the active device (may be empty). */
    fun refreshRaw(): FloatArray {
        val j = resolve()
        jid = j
        if (j < 0) {
            deviceName = ""
            axisCount = 0
            lastAxes = FloatArray(0)
            return lastAxes
        }
        deviceName = GLFW.glfwGetJoystickName(j) ?: "Radio"
        val buf = GLFW.glfwGetJoystickAxes(j)
        val n = buf?.remaining() ?: 0
        axisCount = n
        lastAxes = if (buf == null || n == 0) FloatArray(0) else FloatArray(n) { buf[it] }
        return lastAxes
    }

    /** Raw value of axis [i] on the active device, 0 when out of range. */
    fun raw(i: Int): Float = if (i in lastAxes.indices) lastAxes[i] else 0f

    override fun poll(dt: Float): StickChannels {
        val ch = StickChannels()
        val axes = refreshRaw()
        if (jid < 0 || axes.isEmpty()) {
            ch.present = false
            ch.sourceName = "无设备"
            return ch
        }
        ch.present = true
        ch.sourceName = deviceName

        ch.roll = readChannel(axes, StickChannels.ROLL)
        ch.pitch = readChannel(axes, StickChannels.PITCH)
        ch.yaw = readChannel(axes, StickChannels.YAW)
        ch.throttle = readChannel(axes, StickChannels.THROTTLE)

        // Aux is indexed by RAW axis index: bound axes read 0, unbound axes carry
        // their raw value neutralized around the GLFW standard center. Unbound
        // axes are uncalibrated: their travel is not assumed, only the GLFW
        // documented center 0 and range [-1,1] are relied on (sufficient for
        // switch triggering, which thresholds at Defaults.SWITCH_TRIGGER).
        val aux = FloatArray(axes.size)
        val used = BooleanArray(axes.size)
        for (slot in StickSlot.entries) {
            val ai = cfg.slotCalib[slot.ordinal].axisIndex
            if (ai in axes.indices) used[ai] = true
        }
        for (i in axes.indices) {
            if (!used[i]) aux[i] = axes[i]
        }
        ch.aux = aux
        return ch
    }

    /**
     * Read one logical channel: resolve its physical slot from the current
     * hand layout, then normalize the slot's raw axis. An unbound slot yields
     * zero (it is never guessed).
     */
    private fun readChannel(axes: FloatArray, channel: Int): Float {
        val slot = HandLayout.slot(cfg.handMode, channel)
        val sc = cfg.slotCalib[slot.ordinal]
        if (sc.axisIndex !in axes.indices) return 0f
        val raw = axes[sc.axisIndex]
        return when {
            channel == StickChannels.THROTTLE && cfg.reversible3D ->
                ChannelNormalizer.throttle3d(raw, sc, cfg.threeDThrottleDeadband)
            channel == StickChannels.THROTTLE ->
                ChannelNormalizer.throttle(raw, sc)
            else ->
                ChannelNormalizer.centered(raw, sc)
        }
    }

    companion object {
        /** Enumerate every currently attached joystick for the picker UI. */
        fun listJoysticks(): List<JoystickInfo> {
            val out = ArrayList<JoystickInfo>()
            for (j in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST) {
                if (!GLFW.glfwJoystickPresent(j)) continue
                val axes = GLFW.glfwGetJoystickAxes(j)
                val btns = GLFW.glfwGetJoystickButtons(j)
                out += JoystickInfo(
                    id = j,
                    name = GLFW.glfwGetJoystickName(j) ?: "Joystick $j",
                    guid = GLFW.glfwGetJoystickGUID(j) ?: "",
                    axisCount = axes?.remaining() ?: 0,
                    buttonCount = btns?.remaining() ?: 0,
                    isGamepad = GLFW.glfwJoystickIsGamepad(j),
                )
            }
            return out
        }
    }
}
