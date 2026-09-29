/*
 * FPV Craft - MIT
 * First-run radio calibration wizard (clean-room). Steps, in order:
 *   0. control mode (hand layout) picker
 *   1. center the sticks: sample raw centers and center jitter
 *   2. move each channel's stick: auto-detect the single moved axis, bind it
 *      to the channel's physical slot, and record raw min/max
 *   3. write per-slot calibration back to config and save
 *
 * Bindings are stored per physical StickSlot, so they survive hand-mode
 * changes and no axis order is ever assumed.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.flight.Defaults
import dev.fpv.input.AxisLearner
import dev.fpv.input.HandLayout
import dev.fpv.input.StickChannels
import dev.fpv.input.StickSlot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.max

class CalibrationScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.calibrate")) {

    private var step = 0

    /** Channels requested in order, with their prompt keys. */
    private val targets = listOf(
        StickChannels.THROTTLE to "cal.fpv.throttle",
        StickChannels.ROLL to "cal.fpv.roll",
        StickChannels.PITCH to "cal.fpv.pitch",
        StickChannels.YAW to "cal.fpv.yaw",
    )
    private var targetStep = 0

    // Per raw-axis center sampling.
    private var center = FloatArray(0)
    private var centerSum = FloatArray(0)
    private var centerCount = 0
    private var centerJitter = FloatArray(0)

    /** Raw axis detected for each target, -1 = not yet. */
    private val boundAxis = IntArray(targets.size) { -1 }

    // Per raw-axis extrema while moving.
    private var minRaw = FloatArray(0)
    private var maxRaw = FloatArray(0)

    private val cfg get() = FpvClient.config

    override fun init() {
        clearWidgets()
        val cx = width / 2
        when (step) {
            0 -> {
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.hand", cfg.handMode)) {
                        cfg.handMode = cfg.handMode % 4 + 1; rebuild()
                    }.bounds(cx - 60, height / 2 + 40, 120, 20).build()
                )
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.next")) {
                        step = 1; rebuild()
                    }.bounds(cx - 60, height / 2 + 70, 120, 20).build()
                )
            }
            1 -> {
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.ok")) {
                        captureCenter(); step = 2; targetStep = 0; rebuild()
                    }.bounds(cx - 60, height - 60, 120, 20).build()
                )
            }
            2 -> {
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.next")) { advanceTarget() }
                        .bounds(cx - 60, height - 60, 120, 20).build()
                )
            }
            3 -> {
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.finish")) { finish() }
                        .bounds(cx - 60, height - 60, 120, 20).build()
                )
            }
        }
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.cancel")) { onClose() }
                .bounds(10, 10, 70, 20).build()
        )
    }

    private fun rebuild() = init()

    private fun raw(): FloatArray = FpvClient.input.rawAxes()

    private fun captureCenter() {
        // centerSum/Jitter were accumulated live in render; derive per-axis means.
        center = if (centerCount > 0 && centerSum.isNotEmpty())
            FloatArray(centerSum.size) { centerSum[it] / centerCount }
        else FloatArray(0)
    }

    private fun advanceTarget() {
        targetStep++
        if (targetStep >= targets.size) step = 3
        rebuild()
    }

    private fun finish() {
        for ((i, target) in targets.withIndex()) {
            val axis = boundAxis[i]
            if (axis < 0) continue
            val slot = HandLayout.slot(cfg.handMode, target.first)
            val sc = cfg.slotCalib[slot.ordinal]
            sc.axisIndex = axis
            if (axis in minRaw.indices) {
                sc.rawMin = minRaw[axis]
                sc.rawMax = maxRaw[axis]
            }
            sc.rawMid = if (axis in center.indices) center[axis] else Defaults.RAW_RANGE_MID
            val jitter = if (axis in centerJitter.indices) centerJitter[axis] else 0f
            sc.deadzone = (jitter + Defaults.CALIB_JITTER_MARGIN)
                .coerceIn(Defaults.CALIB_DEADZONE_MIN, Defaults.CALIB_DEADZONE_MAX)
            sc.learned = true
        }
        cfg.save()
        onClose()
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(g, mouseX, mouseY, delta)
        val r = raw()
        val cx = width / 2

        when (step) {
            0 -> {
                g.drawCenteredString(font, Component.translatable("cal.fpv.mode_title"), cx, height / 2 - 60, 0xFFFFFF)
                g.drawCenteredString(font, Component.translatable("cal.fpv.mode_desc"), cx, height / 2 - 35, 0xAAAAAA)
                g.drawCenteredString(font, Component.translatable("cal.fpv.mode_default"), cx, height / 2 - 15, 0xAAAAAA)
                drawTxDiagram(g, cx, height / 2 + 10)
            }
            1 -> {
                g.drawCenteredString(font, Component.translatable("cal.fpv.center_title"), cx, height / 2 - 80, 0xFFFFFF)
                // Crosses rest at center while sampling (mapping unknown until step 2).
                drawStaticCross(g, cx - 80, height / 2)
                drawStaticCross(g, cx + 80, height / 2)
                sampleCenters(r)
            }
            2 -> {
                val (ch, labelKey) = targets[targetStep]
                g.drawCenteredString(font, Component.translatable(labelKey), cx, height / 2 - 80, 0xFFFFFF)

                // Detect the single moved axis and bind it to this channel's slot.
                val exclude = boundAxis.withIndex()
                    .filter { it.index != targetStep && it.value >= 0 }
                    .map { it.value }.toSet()
                val detected = AxisLearner.detect(r, center, exclude)
                if (detected >= 0) {
                    boundAxis[targetStep] = detected
                    val slot = HandLayout.slot(cfg.handMode, ch)
                    cfg.slotCalib[slot.ordinal].axisIndex = detected
                    if (minRaw.size != r.size) {
                        minRaw = FloatArray(r.size) { Float.MAX_VALUE }
                        maxRaw = FloatArray(r.size) { -Float.MAX_VALUE }
                    }
                    for (i in r.indices) {
                        if (r[i] < minRaw[i]) minRaw[i] = r[i]
                        if (r[i] > maxRaw[i]) maxRaw[i] = r[i]
                    }
                }

                // Crosshairs driven by live slot bindings.
                drawSlotCross(g, cx - 80, height / 2, r, StickSlot.LH, StickSlot.LV)
                drawSlotCross(g, cx + 80, height / 2, r, StickSlot.RH, StickSlot.RV)

                val bound = boundAxis[targetStep]
                if (bound >= 0)
                    g.drawCenteredString(
                        font, Component.translatable("cal.fpv.detected", bound + 1),
                        cx, height / 2 + 80, 0x55FF55,
                    )
                else
                    g.drawCenteredString(
                        font, Component.translatable("cal.fpv.move_hint"),
                        cx, height / 2 + 80, 0xFFFF55,
                    )
            }
            3 -> {
                g.drawCenteredString(font, Component.translatable("cal.fpv.done_title"), cx, height / 2 - 40, 0x55FF55)
                for ((i, pair) in targets.withIndex()) {
                    val a = boundAxis[i]
                    val label = Component.translatable(pair.second)
                    val txt = if (a >= 0)
                        Component.translatable("cal.fpv.summary_row", label, a + 1)
                    else
                        Component.translatable("cal.fpv.summary_skip", label)
                    g.drawCenteredString(font, txt, cx, height / 2 - 10 + i * 16, 0xAAAAAA)
                }
            }
        }

        super.render(g, mouseX, mouseY, delta)
    }

    /** Live center sampling for every raw axis. */
    private fun sampleCenters(r: FloatArray) {
        if (r.isEmpty()) return
        if (centerSum.size != r.size) {
            centerSum = FloatArray(r.size)
            centerJitter = FloatArray(r.size)
            centerCount = 0
        }
        for (i in r.indices) {
            centerSum[i] += r[i]
            val n = centerCount + 1
            val mean = (centerSum[i]) / n
            centerJitter[i] = max(centerJitter[i], abs(r[i] - mean))
        }
        centerCount++
    }

    /** Transmitter diagram: two gimbals with colored channel labels. */
    private fun drawTxDiagram(g: GuiGraphics, cx: Int, cy: Int) {
        g.fill(cx - 30, cy - 30, cx + 30, cy + 30, 0xFF333333.toInt())
        g.fill(cx - 19, cy - 18, cx - 17, cy + 18, 0xFFFFFFFF.toInt())
        g.fill(cx - 30, cy - 1, cx - 6, cy + 1, 0xFFFFFFFF.toInt())
        g.fill(cx + 17, cy - 18, cx + 19, cy + 18, 0xFFFFFFFF.toInt())
        g.fill(cx + 6, cy - 1, cx + 30, cy + 1, 0xFFFFFFFF.toInt())
        g.drawString(font, "THR", cx - 52, cy - 24, 0xFFFF5555.toInt())
        g.drawString(font, "YAW", cx - 58, cy + 22, 0xFFFFAA00.toInt())
        g.drawString(font, "PIT", cx + 26, cy - 24, 0xFF55AAAA.toInt())
        g.drawString(font, "ROL", cx + 26, cy + 22, 0xFF55FF55.toInt())
    }

    private fun drawStaticCross(g: GuiGraphics, cx: Int, cy: Int) {
        val s = 26
        g.fill(cx - s, cy - 1, cx + s, cy + 1, 0xFFFFFFFF.toInt())
        g.fill(cx - 1, cy - s, cx + 1, cy + s, 0xFFFFFFFF.toInt())
        g.fill(cx - 3, cy - 3, cx + 3, cy + 3, 0xFFFFFFFF.toInt())
    }

    private fun drawSlotCross(
        g: GuiGraphics, cx: Int, cy: Int, raw: FloatArray,
        hSlot: StickSlot, vSlot: StickSlot,
    ) {
        val s = 26
        g.fill(cx - s, cy - 1, cx + s, cy + 1, 0xFFFFFFFF.toInt())
        g.fill(cx - 1, cy - s, cx + 1, cy + s, 0xFFFFFFFF.toInt())
        val hc = cfg.slotCalib[hSlot.ordinal]
        val vc = cfg.slotCalib[vSlot.ordinal]
        val hx = if (hc.axisIndex in raw.indices) raw[hc.axisIndex] else 0f
        val vy = if (vc.axisIndex in raw.indices) raw[vc.axisIndex] else 0f
        val px = (hx * s).toInt()
        val py = (-vy * s).toInt()
        g.fill(cx + px - 3, cy + py - 3, cx + px + 3, cy + py + 3, 0xFFFF5555.toInt())
    }

    override fun onClose() {
        minecraft.setScreen(parent)
    }
}
