/*
 * FPV Craft - MIT
 * Client-side radio configuration screen (clean-room re-implementation of the
 * common receiver-setup layout: per-channel axis picker / clear / reverse /
 * live bar / auto-learn, plus hand mode, calibration, deadzone and device
 * picker). No third-party GUI library: YACL was rejected because these screens
 * need per-frame live axis values and a calibration state machine, which custom
 * rendering handles directly.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.flight.FlightMode
import dev.fpv.flight.FpvConfig
import dev.fpv.input.AxisLearner
import dev.fpv.input.ChannelNormalizer
import dev.fpv.input.GlfwJoystickProvider
import dev.fpv.input.StickChannels
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class FpvConfigScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.config")) {

    /** Logical channels shown, in display order. */
    private data class Row(val index: Int, val labelKey: String)

    private val rows = listOf(
        Row(StickChannels.THROTTLE, "channel.fpv.throttle"),
        Row(StickChannels.ROLL, "channel.fpv.roll"),
        Row(StickChannels.PITCH, "channel.fpv.pitch"),
        Row(StickChannels.YAW, "channel.fpv.yaw"),
    )

    // Dynamic buttons, refreshed each render for their labels.
    private lateinit var deviceBtn: Button
    private lateinit var handBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var threeDBtn: Button
    private lateinit var deadzoneBtn: Button
    private val axisBtns = HashMap<Int, Button>()
    private val clearBtns = HashMap<Int, Button>()
    private val revBtns = HashMap<Int, Button>()
    private val autoBtns = HashMap<Int, Button>()
    private var modeSwitchBtn: Button? = null
    private var armBtn: Button? = null

    /** Which channel the "auto learn" is currently listening for, -1 = off. */
    private var learnTarget = -2
    private var learnFlash = 0f

    private val cfg: FpvConfig get() = FpvClient.config

    override fun init() {
        val cx = width / 2
        var y = 28

        deviceBtn = Button.builder(
            Component.literal(""), { rebuildDeviceLabel() }
        ).bounds(cx - 150, y, 300, 20).build()
        addRenderableWidget(deviceBtn)
        y += 26

        // Channel rows.
        val rowH = 24
        val xLabel = 12
        val xAxis = 92
        val xClear = 150
        val xRev = 170
        val xAuto = 214
        for (row in rows) {
            axisBtns[row.index] = Button.builder(
                Component.literal(""), { cycleAxis(row.index) }
            ).bounds(xAxis, y, 54, 18).build()
            addRenderableWidget(axisBtns[row.index]!!)

            clearBtns[row.index] = Button.builder(
                Component.literal("X"), { cfg.channels[row.index].axisIndex = -1; refreshDynamic() }
            ).bounds(xClear, y, 18, 18).build()
            addRenderableWidget(clearBtns[row.index]!!)

            revBtns[row.index] = Button.builder(
                Component.literal(""), { cfg.channels[row.index].reversed = !cfg.channels[row.index].reversed; refreshDynamic() }
            ).bounds(xRev, y, 40, 18).build()
            addRenderableWidget(revBtns[row.index]!!)

            autoBtns[row.index] = Button.builder(
                Component.translatable("gui.fpv.auto"), { learnTarget = row.index; learnFlash = 2f }
            ).bounds(xAuto, y, 44, 18).build()
            addRenderableWidget(autoBtns[row.index]!!)
            y += rowH
        }

        // Flight-mode switch row (binds an aux axis to cycle modes).
        modeSwitchBtn = Button.builder(
            Component.literal(""), { learnTarget = LEARN_MODE; learnFlash = 2f }
        ).bounds(xAxis, y, 166, 18).build()
        addRenderableWidget(modeSwitchBtn!!)
        y += rowH

        // Arm / disarm row (placeholder binding, real handler).
        armBtn = Button.builder(
            Component.literal(""), { learnTarget = LEARN_ARM; learnFlash = 2f }
        ).bounds(xAxis, y, 166, 18).build()
        addRenderableWidget(armBtn!!)
        y += rowH + 6

        // Right-side / bottom control cluster.
        handBtn = Button.builder(Component.literal(""), { cycleHandMode() }).bounds(12, y, 120, 20).build()
        addRenderableWidget(handBtn)
        y += 24

        Button.builder(
            Component.translatable("gui.fpv.calibrate"),
            { minecraft.setScreen(CalibrationScreen(this)) }
        ).bounds(12, y, 120, 20).build().let { addRenderableWidget(it) }

        Button.builder(
            Component.translatable("gui.fpv.guided"),
            { minecraft.setScreen(CalibrationScreen(this)) }
        ).bounds(140, y, 120, 20).build().let { addRenderableWidget(it) }

        deadzoneBtn = Button.builder(Component.literal(""), { cycleDeadzone() }).bounds(268, y, 110, 20).build()
        addRenderableWidget(deadzoneBtn)
        y += 24

        modeBtn = Button.builder(Component.literal(""), { cycleFlightMode() }).bounds(12, y, 150, 20).build()
        addRenderableWidget(modeBtn)

        threeDBtn = Button.builder(Component.literal(""), { cfg.reversible3D = !cfg.reversible3D; refreshDynamic() }).bounds(168, y, 210, 20).build()
        addRenderableWidget(threeDBtn)
        y += 24

        Button.builder(
            Component.translatable("gui.fpv.reset_radio"), { resetRadioDefaults() }
        ).bounds(12, y, 200, 20).build().let { addRenderableWidget(it) }

        Button.builder(
            Component.translatable("gui.fpv.done"), { onClose() }
        ).bounds(width - 112, height - 28, 100, 20).build().let { addRenderableWidget(it) }

        refreshDynamic()
    }

    // ---- device picker ----
    private fun rebuildDeviceLabel() {
        val devices = GlfwJoystickProvider.listJoysticks()
        // Build the cycle list: [Keyboard] + devices.
        val currentIsRadio = cfg.useRadio
        val currentId = cfg.joystickId
        // Find current position.
        var idx = if (currentIsRadio) devices.indexOfFirst { it.id == currentId } + 1 else 0
        if (idx < 0) idx = 0
        val next = (idx + 1) % (devices.size + 1)
        if (next == 0) {
            cfg.useRadio = false
        } else {
            cfg.useRadio = true
            cfg.joystickId = devices[next - 1].id
        }
        refreshDynamic()
    }

    private fun deviceLabel(): String {
        if (!cfg.useRadio) return Component.translatable("gui.fpv.device_keyboard").string
        val devices = GlfwJoystickProvider.listJoysticks()
        val d = devices.firstOrNull { it.id == cfg.joystickId }
        return if (d != null) "遥控器: ${d.name}" else "遥控器: (未连接)"
    }

    // ---- hand mode presets (physical sticks: LH=0, LV=1, RH=2, RV=3) ----
    private fun cycleHandMode() {
        cfg.handMode = when (cfg.handMode) { 1 -> 2; 2 -> 3; 3 -> 4; else -> 1 }
        applyHandPreset(cfg.handMode)
        refreshDynamic()
    }

    private fun applyHandPreset(mode: Int) {
        // axisIndex, reversed per logical channel
        data class P(val axis: Int, val rev: Boolean)
        val map: Map<Int, P> = when (mode) {
            1 -> mapOf(
                StickChannels.ROLL to P(2, false),
                StickChannels.PITCH to P(1, true),
                StickChannels.YAW to P(0, false),
                StickChannels.THROTTLE to P(3, true),
            )
            3 -> mapOf(
                StickChannels.ROLL to P(0, false),
                StickChannels.PITCH to P(3, true),
                StickChannels.YAW to P(2, false),
                StickChannels.THROTTLE to P(1, true),
            )
            4 -> mapOf(
                StickChannels.ROLL to P(0, false),
                StickChannels.PITCH to P(1, true),
                StickChannels.YAW to P(2, false),
                StickChannels.THROTTLE to P(3, true),
            )
            else -> mapOf( // Mode 2 (default)
                StickChannels.ROLL to P(2, false),
                StickChannels.PITCH to P(3, true),
                StickChannels.YAW to P(0, false),
                StickChannels.THROTTLE to P(1, true),
            )
        }
        for ((ch, p) in map) {
            cfg.channels[ch].axisIndex = p.axis
            cfg.channels[ch].reversed = p.rev
        }
    }

    private fun cycleAxis(index: Int) {
        val n = FpvClient.input.radioAxisCount().coerceAtLeast(4)
        val cur = cfg.channels[index].axisIndex
        val next = if (cur < 0) 0 else (cur + 1) % n
        cfg.channels[index].axisIndex = next
        refreshDynamic()
    }

    private fun cycleFlightMode() {
        cfg.flightMode = when (cfg.flightMode) {
            FlightMode.ACRO -> FlightMode.ANGLE
            FlightMode.ANGLE -> FlightMode.HORIZON
            else -> FlightMode.ACRO
        }
        refreshDynamic()
    }

    private fun cycleDeadzone() {
        val options = floatArrayOf(0f, 0.01f, 0.02f, 0.05f, 0.10f)
        val cur = cfg.channels[StickChannels.ROLL].deadzone
        var i = options.indexOfFirst { it >= cur }.let { if (it < 0) options.size - 1 else it }
        i = (i + 1) % options.size
        for (c in cfg.channels) c.deadzone = options[i]
        refreshDynamic()
    }

    private fun resetRadioDefaults() {
        cfg.useRadio = true
        cfg.joystickId = -1
        cfg.handMode = 2
        cfg.reversible3D = false
        applyHandPreset(2)
        for (c in cfg.channels) c.deadzone = 0.02f
        cfg.modeSwitchAxis = -1
        FpvClient.armAxis = -1
        refreshDynamic()
    }

    private fun refreshDynamic() {
        deviceBtn.message = Component.literal(deviceLabel())
        handBtn.message = Component.translatable("gui.fpv.hand", cfg.handMode)
        modeBtn.message = Component.translatable("gui.fpv.flightmode", cfg.flightMode.id)
        threeDBtn.message = Component.translatable(
            "gui.fpv.throttle_mode",
            if (cfg.reversible3D) "3D/中点" else "普通",
        )
        deadzoneBtn.message = Component.translatable(
            "gui.fpv.deadzone", String.format("%.2f", cfg.channels[StickChannels.ROLL].deadzone)
        )
        for (row in rows) {
            val c = cfg.channels[row.index]
            axisBtns[row.index]?.message = Component.translatable(
                "gui.fpv.axis", if (c.axisIndex < 0) "-" else "${c.axisIndex + 1}"
            )
            revBtns[row.index]?.message = Component.translatable(
                "gui.fpv.rev", if (c.reversed) "ON" else "OFF"
            )
        }
        modeSwitchBtn?.message = Component.translatable(
            "gui.fpv.mode_switch", if (cfg.modeSwitchAxis < 0) "-" else "${cfg.modeSwitchAxis + 1}"
        )
        armBtn?.message = Component.translatable(
            "gui.fpv.arm", if (FpvClient.armAxis < 0) "-" else "${FpvClient.armAxis + 1}"
        )
    }

    // ---- rendering ----
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(g, mouseX, mouseY, delta)
        val raw = FpvClient.input.rawAxes()
        val center = FloatArray(raw.size) { 0f }

        // Live channel bars.
        var y = 52
        val barX = 266
        val barW = (width - barX - 12).coerceAtLeast(40)
        for (row in rows) {
            g.drawString(font, Component.translatable(row.labelKey), 12, y + 5, 0xFFFFFF, true)
            val c = cfg.channels[row.index]
            val rv = if (c.axisIndex in raw.indices) raw[c.axisIndex] else 0f
            val norm = if (row.index == StickChannels.THROTTLE && !cfg.reversible3D)
                ChannelNormalizer.throttle(rv, c)
            else
                ChannelNormalizer.centered(rv, c)
            drawBar(g, barX, y + 4, barW, norm, row.index == StickChannels.THROTTLE && !cfg.reversible3D)
            g.drawString(font, String.format("%+.2f", norm), barX + barW + 4, y + 5, 0x55FF55, true)
            y += 24
        }
        // skip mode-switch/arm rows' bars (they are aux switches)
        y += 4

        // Crosshair stick visualizers (raw axes 0/1 and 2/3).
        drawCrosshair(g, width - 150, 90, raw)
        drawCrosshair(g, width - 70, 90, raw, offX = 2, offY = 3)

        // Auto-learn detection.
        if (learnTarget != -2) {
            val detected = AxisLearner.detect(raw, center, emptySet())
            if (detected >= 0) {
                when (learnTarget) {
                    LEARN_MODE -> cfg.modeSwitchAxis = detected
                    LEARN_ARM -> FpvClient.armAxis = detected
                    else -> {
                        cfg.channels[learnTarget].axisIndex = detected
                        // nudge reversed to a sensible default for the newly bound axis
                    }
                }
                learnTarget = -2
                learnFlash = 1.5f
                refreshDynamic()
            }
        }
        if (learnFlash > 0f) {
            learnFlash -= delta / 20f
            g.drawCenteredString(font, Component.translatable("gui.fpv.move_stick"), width / 2, 6, 0xFFFF55)
        } else if (learnTarget != -2) {
            g.drawCenteredString(font, Component.translatable("gui.fpv.listening"), width / 2, 6, 0xFFFF55)
        }

        g.drawString(font, Component.translatable("gui.fpv.keyboard_section"), 12, height - 44, 0xAAAAAA, true)

        super.render(g, mouseX, mouseY, delta)
    }

    private fun drawBar(g: GuiGraphics, x: Int, y: Int, w: Int, v: Float, fromZero: Boolean) {
        g.fill(x, y, x + w, y + 6, 0xFF222222.toInt())
        if (fromZero) {
            val fw = (v.coerceIn(0f, 1f) * w).toInt()
            g.fill(x, y, x + fw, y + 6, 0xFF55FF55.toInt())
        } else {
            val cx = x + w / 2
            val dx = (v.coerceIn(-1f, 1f) * w / 2f).toInt()
            if (dx >= 0) g.fill(cx, y, cx + dx, y + 6, 0xFF55FF55.toInt())
            else g.fill(cx + dx, y, cx, y + 6, 0xFF55FF55.toInt())
            g.fill(cx, y - 1, cx + 1, y + 7, 0xFFFFFFFF.toInt())
        }
    }

    /** Draw a small crosshair; dots show live raw axis [offX]/[offY]. */
    private fun drawCrosshair(g: GuiGraphics, cx: Int, cy: Int, raw: FloatArray, offX: Int = 0, offY: Int = 1) {
        val r = 26
        g.fill(cx - r, cy - 1, cx + r, cy + 1, 0xFFFFFFFF.toInt())
        g.fill(cx - 1, cy - r, cx + 1, cy + r, 0xFFFFFFFF.toInt())
        val px = if (offX in raw.indices) (raw[offX] * r).toInt() else 0
        val py = if (offY in raw.indices) (-raw[offY] * r).toInt() else 0
        g.fill(cx + px - 2, cy + py - 2, cx + px + 2, cy + py + 2, 0xFFFF5555.toInt())
    }

    override fun onClose() {
        cfg.save()
        minecraft.setScreen(parent)
    }

    companion object {
        private const val LEARN_MODE = 100
        private const val LEARN_ARM = 101
    }
}
