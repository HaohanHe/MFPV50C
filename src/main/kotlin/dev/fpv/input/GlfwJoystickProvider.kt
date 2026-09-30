/*
 * FPV Craft - MIT
 * Native USB-radio backend on top of GLFW's joystick API (bundled with the
 * game via LWJGL, zero extra dependency). Most FPV USB radios / ELRS / FrSky /
 * Spektrum receivers enumerate as generic HID joysticks, which GLFW exposes as
 * raw axes; they are NOT SDL gamepads, so the gamecontroller mapping path is
 * intentionally not used here.
 *
 * All axes and ALL buttons are enumerated dynamically (counts come from GLFW at
 * runtime). AUX channels are a growable list: continuous axes become AXIS
 * channels, and observed mutually-exclusive button groups become multi-position
 * switch channels (EdgeTX "Btn" mode).
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

    var buttonCount: Int = 0
        private set

    private var lastAxes: FloatArray = FloatArray(0)
    private var lastButtons: ByteArray = ByteArray(0)
    private val analyzer = AuxAnalyzer()

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

    /** Refresh raw axes + buttons of the active device. */
    fun refreshRaw(): FloatArray {
        val j = resolve()
        jid = j
        if (j < 0) {
            deviceName = ""
            axisCount = 0
            buttonCount = 0
            lastAxes = FloatArray(0)
            lastButtons = ByteArray(0)
            return lastAxes
        }
        deviceName = GLFW.glfwGetJoystickName(j) ?: "Radio"
        val ab = GLFW.glfwGetJoystickAxes(j)
        axisCount = ab?.remaining() ?: 0
        lastAxes = if (ab == null || axisCount == 0) FloatArray(0) else FloatArray(axisCount) { ab[it] }
        val bb = GLFW.glfwGetJoystickButtons(j)
        buttonCount = bb?.remaining() ?: 0
        lastButtons = if (bb == null || buttonCount == 0) ByteArray(0) else ByteArray(buttonCount) { bb[it] }
        analyzer.onDevice(axisCount, buttonCount, "$jid:$deviceName:$axisCount:$buttonCount")
        analyzer.observe(lastAxes, lastButtons)
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

        // Legacy aux: indexed by RAW axis index (consumed by mode/arm/headadjust).
        val aux = FloatArray(axes.size)
        val used = BooleanArray(axes.size)
        for (slot in StickSlot.entries) {
            val ai = cfg.slotCalib[slot.ordinal].axisIndex
            if (ai in axes.indices) used[ai] = true
        }
        for (i in axes.indices) if (!used[i]) aux[i] = axes[i]
        ch.aux = aux

        // Data-driven logical AUX list.
        reconcileAux(used)
        ch.auxChannels = cfg.auxChannels.map { evalAux(it) }
        return ch
    }

    /**
     * Grow cfg.auxChannels to cover every discovered source (free axes and
     * observed button groups), assigning EdgeTX-style suggested names. Existing
     * user entries are never overwritten.
     */
    private fun reconcileAux(usedAxis: BooleanArray) {
        val have = cfg.auxChannels.map { signature(it) }.toHashSet()
        var axisN = 0
        var btnN = 0
        for (i in lastAxes.indices) {
            if (usedAxis[i]) continue
            val sig = "axis:$i"
            if (sig in have) continue
            val name = when (axisN) { 0 -> "LS"; 1 -> "RS"; else -> "Dial${axisN + 1 - 2}" }
            axisN++
            cfg.auxChannels.add(AuxChannel(name = name, kind = "AXIS", axisIndex = i))
            have += sig
        }
        for (group in analyzer.groupButtons()) {
            val sig = "buttons:" + group.joinToString(",")
            if (sig in have) continue
            val name = switchName(btnN++)
            cfg.auxChannels.add(
                AuxChannel(name = name, kind = "BUTTONS", buttons = ArrayList(group))
            )
            have += sig
        }
    }

    private fun signature(a: AuxChannel): String =
        if (a.kind == "BUTTONS") "buttons:" + a.buttons.joinToString(",") else "axis:${a.axisIndex}"

    private fun switchName(n: Int): String {
        val letters = "ABCDEFGH"
        return if (n < letters.length) "S${letters[n]}" else "SW${n + 1}"
    }

    /** Evaluate one configured AUX channel into its per-frame state. */
    private fun evalAux(a: AuxChannel): AuxState {
        if (a.kind == "BUTTONS") {
            var pos = -1
            for (p in a.buttons.indices) {
                val bi = a.buttons[p]
                if (bi in lastButtons.indices && lastButtons[bi].toInt() != 0) { pos = p; break }
            }
            val n = a.buttons.size
            val norm = if (n <= 1) (if (pos >= 0) 1f else 0f)
            else (pos.toFloat() / (n - 1)) * 2f - 1f
            return AuxState(
                name = a.name,
                value = if (a.reversed) -norm else norm,
                position = pos,
                positionCount = n,
                kind = "BUTTONS",
                source = "Btns " + a.buttons.joinToString(",") { "${it + 1}" },
                calibrated = true,
            )
        }
        // AXIS
        if (a.axisIndex !in lastAxes.indices) {
            return AuxState(a.name, 0f, -1, 0, "AXIS", "Axis -", a.learned)
        }
        // Reuse the centered normalizer with the aux channel's own endpoints.
        val sc = SlotCalib(
            axisIndex = a.axisIndex, reversed = a.reversed,
            rawMin = a.rawMin, rawMid = a.rawMid, rawMax = a.rawMax,
            deadzone = a.deadzone, learned = a.learned,
        )
        val v = ChannelNormalizer.centered(lastAxes[a.axisIndex], sc)
        val levels = analyzer.levelCount(a.axisIndex)
        return AuxState(
            name = a.name,
            value = v,
            position = -1,
            positionCount = if (levels <= 3) levels else 0,
            kind = "AXIS",
            source = "Axis ${a.axisIndex + 1}",
            calibrated = a.learned,
        )
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
