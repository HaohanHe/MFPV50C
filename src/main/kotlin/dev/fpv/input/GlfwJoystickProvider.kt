/*
 * FPV Craft - MIT
 * Native USB-radio backend on top of GLFW's joystick API (bundled with the
 * game via LWJGL, zero extra dependency). Most FPV USB radios / ELRS / FrSky /
 * Spektrum receivers enumerate as generic HID joysticks, which GLFW exposes as
 * raw axes; they are NOT SDL gamepads, so the gamecontroller mapping path is
 * intentionally not used here.
 */
package dev.fpv.input

import dev.fpv.flight.ChannelCalib
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

    /** True when a USB device is currently attached (call after refresh()). */
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

        val rc = cfg.channels[StickChannels.ROLL]
        val pc = cfg.channels[StickChannels.PITCH]
        val yc = cfg.channels[StickChannels.YAW]
        val tc = cfg.channels[StickChannels.THROTTLE]

        fun rawOf(c: ChannelCalib): Float = if (c.axisIndex in axes.indices) axes[c.axisIndex] else 0f

        ch.roll = ChannelNormalizer.centered(rawOf(rc), rc)
        ch.pitch = ChannelNormalizer.centered(rawOf(pc), pc)
        ch.yaw = ChannelNormalizer.centered(rawOf(yc), yc)
        ch.throttle = if (cfg.reversible3D)
            ChannelNormalizer.throttle3d(rawOf(tc), tc, cfg.threeDThrottleDeadband)
        else
            ChannelNormalizer.throttle(rawOf(tc), tc)

        // Aux = every raw axis not bound to one of the four main channels.
        val used = BooleanArray(axes.size)
        for (c in listOf(rc, pc, yc, tc)) if (c.axisIndex in axes.indices) used[c.axisIndex] = true
        val aux = ArrayList<Float>()
        for (i in axes.indices) {
            if (used[i]) continue
            aux += ChannelNormalizer.centered(axes[i], auxCalib(i))
        }
        ch.aux = aux.toFloatArray()
        return ch
    }

    private fun auxCalib(i: Int) = ChannelCalib(
        axisIndex = i, reversed = false, rawMin = -1f, rawMid = 0f, rawMax = 1f, deadzone = 0f,
    )

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
