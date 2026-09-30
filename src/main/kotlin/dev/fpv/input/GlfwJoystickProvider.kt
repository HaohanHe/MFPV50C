/*
 * FPV Craft - MIT
 * Native USB-radio backend on top of GLFW's joystick API (bundled with the
 * game via LWJGL, zero extra dependency). Reads ALL axes, ALL buttons and ALL
 * hats dynamically (counts come from GLFW at runtime). Bindings live in the
 * per-device DeviceProfile; no axis/button order is ever assumed.
 */
package dev.fpv.input

import dev.fpv.flight.FpvConfig
import org.lwjgl.glfw.GLFW
import kotlin.math.abs

class GlfwJoystickProvider(private val cfg: FpvConfig) : InputProvider {

    override val name: String = "USB 遥控器 (GLFW)"

    var jid: Int = -1
        private set
    var deviceName: String = ""
        private set
    var fingerprint: String = ""
        private set
    var axisCount: Int = 0
        private set
    var buttonCount: Int = 0
        private set
    var hatCount: Int = 0
        private set

    var lastAxes: FloatArray = FloatArray(0)
        private set
    var lastButtons: ByteArray = ByteArray(0)
        private set
    var lastHats: ByteArray = ByteArray(0)
        private set

    private var resolvedJid: Int = -1

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

    fun present(): Boolean = resolve() >= 0

    /** Refresh all raw source arrays and attach the matching device profile. */
    fun refreshRaw(): FloatArray {
        val j = resolve()
        resolvedJid = j
        if (j < 0) {
            deviceName = ""; fingerprint = ""
            axisCount = 0; buttonCount = 0; hatCount = 0
            lastAxes = FloatArray(0); lastButtons = ByteArray(0); lastHats = ByteArray(0)
            return lastAxes
        }
        deviceName = GLFW.glfwGetJoystickName(j) ?: "Radio"
        val guid = GLFW.glfwGetJoystickGUID(j) ?: ""
        fingerprint = DeviceProfiles.fingerprintOf(deviceName, guid)

        val ab = GLFW.glfwGetJoystickAxes(j)
        axisCount = ab?.remaining() ?: 0
        lastAxes = if (ab == null || axisCount == 0) FloatArray(0) else FloatArray(axisCount) { ab[it] }

        val bb = GLFW.glfwGetJoystickButtons(j)
        buttonCount = bb?.remaining() ?: 0
        lastButtons = if (bb == null || buttonCount == 0) ByteArray(0) else ByteArray(buttonCount) { bb[it] }

        val hb = GLFW.glfwGetJoystickHats(j)
        hatCount = hb?.remaining() ?: 0
        lastHats = if (hb == null || hatCount == 0) ByteArray(0) else ByteArray(hatCount) { hb[it] }

        // Attach (or create) the per-fingerprint profile; this is what makes raw
        // indices device-scoped.
        cfg.attachProfileForDevice(fingerprint, deviceName)
        return lastAxes
    }

    fun rawAxis(i: Int): Float = if (i in lastAxes.indices) lastAxes[i] else 0f
    fun rawButton(i: Int): Boolean = i in lastButtons.indices && lastButtons[i].toInt() != 0
    fun rawHat(i: Int): Int = if (i in lastHats.indices) lastHats[i].toInt() else 0

    override fun poll(dt: Float): StickChannels {
        val ch = StickChannels()
        val axes = refreshRaw()
        if (resolvedJid < 0 || axes.isEmpty()) {
            ch.present = false
            ch.sourceName = "无设备"
            return ch
        }
        ch.present = true
        ch.sourceName = deviceName

        ch.roll = evalLogical(StickChannels.ROLL)
        ch.pitch = evalLogical(StickChannels.PITCH)
        ch.yaw = evalLogical(StickChannels.YAW)
        ch.throttle = evalLogical(StickChannels.THROTTLE)

        // Legacy aux: indexed by RAW axis index (mode/arm/headadjust consumers).
        val aux = FloatArray(axes.size)
        val used = BooleanArray(axes.size)
        for (slot in StickSlot.entries) {
            val sc = cfg.slotCalib[slot.ordinal]
            if (sc.type == "AXIS" && sc.axisIndex in axes.indices) used[sc.axisIndex] = true
        }
        for (i in axes.indices) if (!used[i]) aux[i] = axes[i]
        ch.aux = aux

        ch.auxChannels = cfg.auxChannels.map { evalAux(it) }
        // Carry raw button/hat snapshots for data-driven mode routing.
        ch.rawButtons = lastButtons.copyOf()
        ch.rawHats = lastHats.copyOf()
        return ch
    }

    /** Evaluate one logical gimbal channel through its hand-mode physical slot. */
    private fun evalLogical(channel: Int): Float {
        val slot = HandLayout.slot(cfg.handMode, channel)
        val sc = cfg.slotCalib[slot.ordinal]
        if (sc.axisIndex < 0) return 0f
        val v = evalBinding(sc)
        return when {
            channel == StickChannels.THROTTLE && cfg.reversible3D ->
                ChannelNormalizer.throttle3d(rawAxis(sc.axisIndex), sc, cfg.threeDThrottleDeadband)
            channel == StickChannels.THROTTLE ->
                ChannelNormalizer.throttle(rawAxis(sc.axisIndex), sc)
            else -> v
        }
    }

    /** Normalize a bound control (axis / button / hat) to -1..1. */
    private fun evalBinding(sc: SlotCalib): Float {
        if (sc.axisIndex < 0) return 0f
        return when (sc.type) {
            "BUTTON" -> {
                val on = rawButton(sc.axisIndex)
                val v = if (on) 1f else 0f
                if (sc.reversed) -v else v
            }
            "HAT" -> {
                val h = rawHat(sc.axisIndex)
                if (sc.positions.isNotEmpty()) {
                    val pos = sc.positions.indexOfFirst { it.toInt() == h }
                    positionValue(sc, if (pos < 0) 0 else pos)
                } else {
                    val up = if (sc.reversed) -1f else 1f
                    if ((h and GLFW.GLFW_HAT_UP) != 0) up else 0f
                }
            }
            else -> ChannelNormalizer.centered(rawAxis(sc.axisIndex), sc)
        }
    }

    /** Map a discrete position index to -1..1 (or 0/1 for a 2-way button). */
    private fun positionValue(sc: SlotCalib, pos: Int): Float {
        val n = sc.positions.size
        if (n <= 1) return if (pos == 0) 1f else 0f
        val v = pos.toFloat() / (n - 1) * 2f - 1f
        return if (sc.reversed) -v else v
    }

    private fun evalAux(a: AuxChannel): AuxState {
        if (a.axisIndex < 0) return AuxState(a.name, 0f, -1, 0, a.kind, "unbound", false)
        val sc = SlotCalib(
            type = if (a.kind == "BUTTONS") "BUTTON" else "AXIS",
            axisIndex = a.axisIndex, reversed = a.reversed,
            rawMin = a.rawMin, rawMid = a.rawMid, rawMax = a.rawMax,
            deadzone = a.deadzone, learned = a.learned,
            positions = a.positions.toMutableList(),
        )
        val v = evalBinding(sc)
        return AuxState(
            name = a.name, value = v,
            position = -1, positionCount = a.positions.size,
            kind = a.kind, source = "${a.kind} ${a.axisIndex + 1}", calibrated = a.learned,
        )
    }

    companion object {
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
