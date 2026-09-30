/*
 * FPV Craft - MIT
 * Observes the live HID axis/button stream and suggests how to turn it into
 * logical AUX channels. Clean-room heuristics based on the researched EdgeTX
 * USB-joystick behavior:
 *   - In "Btn" mode each switch position is its own HID button; the positions
 *     of one switch are mutually exclusive (only one pressed at a time). We
 *     group observed, consecutive, pairwise-mutually-exclusive buttons into one
 *     multi-position switch. Buttons never observed are EdgeTX ghost buttons and
 *     are ignored.
 *   - In "Axis" mode a switch outputs discrete levels; we bin each unused axis
 *     and count how many distinct levels it visits (<=3 => discrete switch).
 *   - Naming follows EdgeTX convention: the two free continuous axes are LS/RS,
 *     then switches SA..SH.
 */
package dev.fpv.input

class AuxAnalyzer {

    private var devKey: String = ""

    /** Frames each button has been held. */
    private var pressCount = IntArray(0)

    /** Frames on which two buttons were held simultaneously. */
    private var coPress = Array(0) { IntArray(0) }

    /** Observed quantized level bins per axis (0..10, bin width 0.2). */
    private var axisLevels = Array(0) { HashSet<Int>() }

    /** Call every time the active device might have changed. */
    fun onDevice(axes: Int, buttons: Int, key: String) {
        if (key == devKey && pressCount.size == buttons && axisLevels.size == axes) return
        devKey = key
        pressCount = IntArray(buttons)
        coPress = Array(buttons) { IntArray(buttons) }
        axisLevels = Array(axes) { HashSet<Int>() }
    }

    /** Fold one live frame into the observation history. */
    fun observe(axes: FloatArray, buttons: ByteArray) {
        if (axisLevels.size != axes.size) axisLevels = Array(axes.size) { HashSet<Int>() }
        for (i in axes.indices) {
            val bin = ((axes[i] + 1f) * 5f).toInt().coerceIn(0, 10)
            axisLevels[i].add(bin)
        }
        if (pressCount.size != buttons.size) {
            pressCount = IntArray(buttons.size)
            coPress = Array(buttons.size) { IntArray(buttons.size) }
        }
        val pressed = ArrayList<Int>()
        for (b in buttons.indices) {
            if (buttons[b].toInt() != 0) {
                pressCount[b]++
                pressed += b
            }
        }
        for (a in pressed.indices) {
            for (k in (a + 1) until pressed.size) {
                coPress[pressed[a]][pressed[k]]++
            }
        }
    }

    /** Distinct observed levels for axis [i]; small (<=3) => discrete switch axis. */
    fun levelCount(axis: Int): Int =
        if (axis in axisLevels.indices) axisLevels[axis].size else 1

    /** True once the button has actually been observed held (not a ghost). */
    private fun seen(b: Int): Boolean = b in pressCount.indices && pressCount[b] > GHOST_FRAMES

    /**
     * Group observed buttons into switch position groups. Consecutive observed
     * buttons that are pairwise mutually exclusive belong to the same switch.
     */
    fun groupButtons(): List<List<Int>> {
        val groups = ArrayList<List<Int>>()
        var b = 0
        while (b < pressCount.size) {
            if (!seen(b)) { b++; continue }
            val group = ArrayList<Int>()
            group += b
            var next = b + 1
            while (next < pressCount.size && seen(next)) {
                var exclusive = true
                for (m in group) {
                    if (coPress[m][next] > 0) { exclusive = false; break }
                }
                if (!exclusive) break
                group += next
                next++
            }
            groups += group
            b = next
        }
        return groups
    }

    companion object {
        /** A button pressed fewer than this many frames is treated as never-used. */
        private const val GHOST_FRAMES = 2
    }
}
