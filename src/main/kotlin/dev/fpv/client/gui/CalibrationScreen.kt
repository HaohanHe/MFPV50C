/*
 * FPV Craft - MIT
 * First-run radio calibration wizard (clean-room). Steps, in order:
 *   0. control mode (hand layout) picker
 *   1. center sticks and sample raw midpoints + center jitter -> deadzone
 *   2. move each axis (throttle / roll / pitch / yaw): auto-detect the single
 *      moved axis, bind it, and record its raw min/max
 *   3. write normalized calibration back to config and save.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.flight.FpvConfig
import dev.fpv.input.AxisLearner
import dev.fpv.input.StickChannels
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.max

class CalibrationScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.calibrate")) {

    private var step = 0
    private val targets = listOf(
        StickChannels.THROTTLE to "cal.fpv.throttle",
        StickChannels.ROLL to "cal.fpv.roll",
        StickChannels.PITCH to "cal.fpv.pitch",
        StickChannels.YAW to "cal.fpv.yaw",
    )
    private var targetStep = 0

    private var center = FloatArray(0)
    private var centerSum = FloatArray(0)
    private var centerCount = 0
    private var centerJitter = FloatArray(0)

    private var boundAxis = IntArray(4) { -1 }
    private var minRaw = FloatArray(0) { Float.MAX_VALUE }
    private var maxRaw = FloatArray(0) { -Float.MAX_VALUE }

    private val cfg: FpvConfig get() = FpvClient.config

    override fun init() {
        clearWidgets()
        val cx = width / 2
        when (step) {
            0 -> {
                addRenderableWidget(
                    Button.builder(
                        Component.translatable("gui.fpv.hand", cfg.handMode),
                        { cfg.handMode = if (cfg.handMode == 4) 1 else cfg.handMode + 1; rebuild() }
                    ).bounds(cx - 60, height / 2 + 40, 120, 20).build()
                )
                addRenderableWidget(
                    Button.builder(
                        Component.translatable("gui.fpv.next"), { step = 1; rebuild() }
                    ).bounds(cx - 60, height / 2 + 70, 120, 20).build()
                )
            }
            1 -> {
                addRenderableWidget(
                    Button.builder(
                        Component.translatable("gui.fpv.ok"), { captureCenter(); step = 2; targetStep = 0; rebuild() }
                    ).bounds(cx - 60, height - 60, 120, 20).build()
                )
            }
            2 -> {
                addRenderableWidget(
                    Button.builder(
                        Component.translatable("gui.fpv.next"), { advanceAxis() }
                    ).bounds(cx - 60, height - 60, 120, 20).build()
                )
            }
            3 -> {
                addRenderableWidget(
                    Button.builder(
                        Component.translatable("gui.fpv.finish"), { finish() }
                    ).bounds(cx - 60, height - 60, 120, 20).build()
                )
            }
        }
        addRenderableWidget(
            Button.builder(
                Component.translatable("gui.fpv.cancel"), { onClose() }
            ).bounds(10, 10, 70, 20).build()
        )
    }

    private fun rebuild() = init()

    private fun raw(): FloatArray {
        cfg.useRadio = true
        return FpvClient.input.rawAxes()
    }

    private fun captureCenter() {
        val r = raw()
        center = if (centerCount > 0) FloatArray(centerSum.size) { centerSum[it] / centerCount } else r.copyOf()
        // jitter already tracked live during render
    }

    private fun advanceAxis() {
        targetStep++
        if (targetStep >= targets.size) step = 3
        rebuild()
    }

    private fun finish() {
        for ((tIdx, _) in targets.withIndex()) {
            val axis = boundAxis[tIdx]
            if (axis < 0) continue
            val ch = cfg.channels[targets[tIdx].first]
            ch.axisIndex = axis
            ch.rawMin = minRaw[axis]
            ch.rawMax = maxRaw[axis]
            ch.rawMid = if (axis in center.indices) center[axis] else 0f
            val jitter = if (axis in centerJitter.indices) centerJitter[axis] else 0f
            ch.deadzone = (jitter + 0.01f).coerceIn(0.005f, 0.1f)
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
                // Simple transmitter stick diagram (clean-room, drawn with primitives).
                drawTxDiagram(g, cx, height / 2 + 10)
            }
            1 -> {
                g.drawCenteredString(font, Component.translatable("cal.fpv.center_title"), cx, height / 2 - 80, 0xFFFFFF)
                drawCross(g, cx - 80, height / 2, r, 0, 1)
                drawCross(g, cx + 80, height / 2, r, 2, 3)
                // Live center sampling.
                if (r.isNotEmpty()) {
                    if (centerSum.size != r.size) { centerSum = FloatArray(r.size); centerCount = 0; centerJitter = FloatArray(r.size) }
                    for (i in r.indices) {
                        centerSum[i] += r[i]
                        centerCount++
                        val mean = centerSum[i] / centerCount
                        centerJitter[i] = max(centerJitter[i], abs(r[i] - mean))
                    }
                }
            }
            2 -> {
                val (chIdx, labelKey) = targets[targetStep]
                g.drawCenteredString(font, Component.translatable(labelKey), cx, height / 2 - 80, 0xFFFFFF)
                // Detect moved axis and bind + track extrema live.
                val exclude = targets.mapIndexed { i, t -> boundAxis[i] }.filter { it >= 0 }.toSet()
                val detected = AxisLearner.detect(r, center, exclude)
                if (detected >= 0) {
                    boundAxis[targetStep] = detected
                    if (minRaw.size != r.size) {
                        minRaw = FloatArray(r.size) { Float.MAX_VALUE }
                        maxRaw = FloatArray(r.size) { -Float.MAX_VALUE }
                    }
                    for (i in r.indices) {
                        if (r[i] < minRaw[i]) minRaw[i] = r[i]
                        if (r[i] > maxRaw[i]) maxRaw[i] = r[i]
                    }
                }
                drawCross(g, cx - 80, height / 2, r, 0, 1)
                drawCross(g, cx + 80, height / 2, r, 2, 3)
                val bound = boundAxis[targetStep]
                if (bound >= 0) {
                    g.drawCenteredString(font, Component.translatable("cal.fpv.detected", bound + 1), cx, height / 2 + 80, 0x55FF55)
                } else {
                    g.drawCenteredString(font, Component.translatable("cal.fpv.move_hint"), cx, height / 2 + 80, 0xFFFF55)
                }
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

    /** Minimal transmitter diagram: two sticks with colored axis labels. */
    private fun drawTxDiagram(g: GuiGraphics, cx: Int, cy: Int) {
        g.fill(cx - 30, cy - 30, cx + 30, cy + 30, 0xFF333333.toInt())
        // left stick (throttle/yaw on M2), right stick (pitch/roll on M2)
        g.fill(cx - 18 - 1, cy - 18, cx - 18 + 1, cy + 18, 0xFFFFFFFF.toInt())
        g.fill(cx - 18 - 12, cy - 1, cx - 18 + 12, cy + 1, 0xFFFFFFFF.toInt())
        g.fill(cx + 18 - 1, cy - 18, cx + 18 + 1, cy + 18, 0xFFFFFFFF.toInt())
        g.fill(cx + 18 - 12, cy - 1, cx + 18 + 12, cy + 1, 0xFFFFFFFF.toInt())
        g.drawString(font, "THR", cx - 52, cy - 24, 0xFFFF5555.toInt(), true)
        g.drawString(font, "YAW", cx - 58, cy + 22, 0xFFFFAA00.toInt(), true)
        g.drawString(font, "PIT", cx + 26, cy - 24, 0xFF55AAAA.toInt(), true)
        g.drawString(font, "ROL", cx + 26, cy + 22, 0xFF55FF55.toInt(), true)
    }

    private fun drawCross(g: GuiGraphics, cx: Int, cy: Int, r: FloatArray, ax: Int, ay: Int) {
        val s = 26
        g.fill(cx - s, cy - 1, cx + s, cy + 1, 0xFFFFFFFF.toInt())
        g.fill(cx - 1, cy - s, cx + 1, cy + s, 0xFFFFFFFF.toInt())
        val px = if (ax in r.indices) (r[ax] * s).toInt() else 0
        val py = if (ay in r.indices) (-r[ay] * s).toInt() else 0
        g.fill(cx + px - 3, cy + py - 3, cx + px + 3, cy + py + 3, 0xFFFF5555.toInt())
    }

    override fun onClose() {
        minecraft.setScreen(parent)
    }
}
