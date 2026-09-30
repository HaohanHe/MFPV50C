/*
 * FPV Craft - MIT
 * Channel monitor: every raw axis / button / hat of the attached radio, with a
 * live bar (axes) or a grid cell (buttons). The source that changed most
 * recently is highlighted ("<== ACTIVE") and named at the top, so the pilot
 * can identify S1/S2/LS/RS and the SA..SH switches without guessing.
 * Purely diagnostic - no writes.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.input.ChannelNormalizer
import dev.fpv.input.SlotCalib
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.abs

class MonitorScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.monitor")) {

    // Opaque-ARGB palette (1.21.11 discards text whose alpha == 0).
    private val WHITE = 0xFFFFFFFF.toInt()
    private val DIM = 0xFFAAAAAA.toInt()
    private val GOOD = 0xFF55FF55.toInt()
    private val WARN = 0xFFFFFF55.toInt()
    private val BAD = 0xFFFF5555.toInt()
    private val BAR_BG = 0xFF222222.toInt()
    private val BTN_OFF = 0xFF333333.toInt()
    private val BTN_ON = 0xFF2FA02F.toInt()

    // Previous frame for change detection.
    private var prevAxes = FloatArray(0)
    private var prevButtons = ByteArray(0)
    private var prevHats = ByteArray(0)
    private var seeded = false

    // Most-recently-active source (kept on screen for ACTIVE_HOLD_MS).
    private var activeKind = -1   // 0 axis, 1 button, 2 hat
    private var activeIdx = -1
    private var activeTime = 0L

    private val cfg get() = FpvClient.config

    override fun init() {
        clearWidgets()
        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(width / 2 - 60, height - 26, 120, 20).build()
        )
    }

    /** Logical gimbal a raw axis drives, or "" when unbound. */
    private fun axisRole(idx: Int): String {
        for (slot in dev.fpv.input.StickSlot.entries) {
            val sc = cfg.slotCalib[slot.ordinal]
            if (sc.type == "AXIS" && sc.axisIndex == idx) return gimbalName(slot)
        }
        val a = cfg.auxChannels.firstOrNull { it.kind == "AXIS" && it.axisIndex == idx }
        return a?.name ?: ""
    }

    /** Logical switch a raw button drives, or "" when unbound. */
    private fun buttonRole(idx: Int): String {
        val a = cfg.auxChannels.firstOrNull { it.kind == "BUTTONS" && it.axisIndex == idx }
        return a?.name ?: ""
    }

    private fun gimbalName(slot: dev.fpv.input.StickSlot): String = when (slot) {
        dev.fpv.input.StickSlot.LH -> "Yaw"
        dev.fpv.input.StickSlot.LV -> "Throttle"
        dev.fpv.input.StickSlot.RH -> "Roll"
        dev.fpv.input.StickSlot.RV -> "Pitch"
    }

    /**
     * Normalized (-1..1) value of raw axis [idx] through whatever calibration
     * currently binds it (gimbal slot or aux axis). Unbound axes are already in
     * [-1,1] from GLFW, so they pass through unchanged.
     */
    private fun normAxis(idx: Int): Float {
        val raw = axSnapshot.getOrElse(idx) { 0f }
        for (slot in dev.fpv.input.StickSlot.entries) {
            val sc = cfg.slotCalib[slot.ordinal]
            if (sc.type == "AXIS" && sc.axisIndex == idx) return ChannelNormalizer.centered(raw, sc)
        }
        val a = cfg.auxChannels.firstOrNull { it.kind == "AXIS" && it.axisIndex == idx }
        if (a != null) {
            val sc = SlotCalib(
                type = "AXIS", axisIndex = idx, reversed = a.reversed,
                rawMin = a.rawMin, rawMid = a.rawMid, rawMax = a.rawMax,
                deadzone = a.deadzone, learned = a.learned,
            )
            return ChannelNormalizer.centered(raw, sc)
        }
        return raw
    }

    private lateinit var axSnapshot: FloatArray

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // Background already drawn by renderWithTooltipAndSubtitles.
        FpvClient.input.rawAxes()
        val ax = FpvClient.input.axes()
        val bt = FpvClient.input.buttons()
        val ht = FpvClient.input.hats()
        axSnapshot = ax

        // ---- Change detection (axes + buttons + hats, all dynamic counts) ----
        if (!seeded || prevAxes.size != ax.size || prevButtons.size != bt.size ||
            prevHats.size != ht.size
        ) {
            activeKind = -1; activeIdx = -1
        } else {
            var best = 0f
            for (i in ax.indices) {
                val d = abs(ax[i] - prevAxes[i])
                if (d > best) { best = d; activeKind = 0; activeIdx = i }
            }
            for (i in bt.indices) {
                if (bt[i] != prevButtons[i]) { best = 1f; activeKind = 1; activeIdx = i }
            }
            for (i in ht.indices) {
                if (ht[i] != prevHats[i]) { best = 1f; activeKind = 2; activeIdx = i }
            }
            if (best > 0.08f) activeTime = System.currentTimeMillis()
        }
        prevAxes = ax.copyOf(); prevButtons = bt.copyOf(); prevHats = ht.copyOf(); seeded = true

        val activeFresh = System.currentTimeMillis() - activeTime < ACTIVE_HOLD_MS
        val showActive = activeFresh && activeKind >= 0

        // ---- Header ----
        g.drawString(font, Component.literal(
            "Device: " + FpvClient.input.fingerprint().ifEmpty { "—" }), 8, 4, DIM)
        val activeText = if (showActive) when (activeKind) {
            0 -> "ACTIVE  Axis ${activeIdx + 1}" + (axisRole(activeIdx).let { if (it.isEmpty()) "" else " ($it)" })
            1 -> "ACTIVE  Btn ${activeIdx + 1}" + (buttonRole(activeIdx).let { if (it.isEmpty()) "" else " ($it)" })
            else -> "ACTIVE  Hat ${activeIdx + 1} (0x%02X)".format(ht[activeIdx].toInt())
        } else "Move any control to identify it"
        g.drawString(font, Component.literal(activeText), 8, 15, if (showActive) WARN else DIM)

        // ---- Axes (left column), each with a centered live bar ----
        val axNameX = 8
        val barX = 64
        val barW = 92
        val axStartY = 30
        val axRowH = 11
        g.drawString(font, Component.literal("AXES (${ax.size})"), axNameX, axStartY - 10, WHITE)
        for (i in ax.indices) {
            val y = axStartY + i * axRowH
            if (y > height - 36) break
            val role = axisRole(i)
            val isAct = activeFresh && activeKind == 0 && activeIdx == i
            g.drawString(font, Component.literal(if (role.isEmpty()) "A${i + 1}" else role),
                axNameX, y, if (isAct) WARN else DIM)
            // bar background + center tick
            g.fill(barX, y + 2, barX + barW, y + 6, BAR_BG)
            val cxBar = barX + barW / 2
            g.fill(cxBar, y + 1, cxBar, y + 7, DIM)
            val v = ax[i].coerceIn(-1f, 1f)
            if (v >= 0) g.fill(cxBar, y + 2, cxBar + (barW / 2 * v).toInt(), y + 6, GOOD)
            else g.fill(cxBar + (barW / 2 * v).toInt(), y + 2, cxBar, y + 6, BAD)
            g.drawString(font, Component.literal(
                "%.2f  %+.2f".format(ax[i], normAxis(i))), barX + barW + 3, y,
                if (isAct) WARN else DIM)
        }

        // ---- Buttons (right column), grid of cells ----
        val btStartX = 196
        val btStartY = 30
        val cellW = 30
        val cellH = 16
        val perRow = 6
        g.drawString(font, Component.literal("BUTTONS (${bt.size})"), btStartX, btStartY - 10, WHITE)
        for (i in bt.indices) {
            val r = i / perRow
            val c = i % perRow
            val x = btStartX + c * cellW
            val y = btStartY + r * cellH
            if (y > height - 36) break
            val on = bt[i].toInt() != 0
            val isAct = activeFresh && activeKind == 1 && activeIdx == i
            val bg = when { isAct -> WARN; on -> BTN_ON; else -> BTN_OFF }
            g.fill(x + 1, y + 1, x + cellW - 1, y + cellH - 1, bg)
            val fg = when { isAct -> BAR_BG; on -> WHITE; else -> DIM }
            g.drawString(font, Component.literal("B${i + 1}"), x + 3, y + 4, fg)
        }

        // ---- Hats (bottom-left, if any) ----
        var hy = axStartY + ax.size * axRowH + 4
        if (ht.isNotEmpty() && hy < height - 40) {
            g.drawString(font, Component.literal("HATS (${ht.size})"), 8, hy, WHITE); hy += 10
            for (i in ht.indices) {
                if (hy > height - 36) break
                val isAct = activeFresh && activeKind == 2 && activeIdx == i
                g.drawString(font, Component.literal(
                    "Hat${i + 1}: 0x%02X".format(ht[i].toInt())),
                    12, hy, if (isAct) WARN else GOOD); hy += 9
            }
        }

        super.render(g, mouseX, mouseY, delta)
    }

    override fun onClose() = minecraft.setScreen(parent)

    companion object {
        /** How long the most-recent source stays highlighted, ms. */
        private const val ACTIVE_HOLD_MS = 1200L
    }
}
