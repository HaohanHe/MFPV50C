/*
 * FPV Craft - MIT
 * Guided, move-to-bind radio setup (clean-room). For every control the pilot
 * moves/holds the real control; the wizard watches ALL raw axes/buttons/hats
 * over a short window and binds whichever source changed the most. No axis,
 * button or channel order is assumed. Unused controls are skipped and output
 * neutral (0).
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.input.HandLayout
import dev.fpv.input.SlotCalib
import dev.fpv.input.StickChannels
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.abs

class CalibrationScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.calibrate")) {

    private enum class TKind { ANALOG, SWITCH }

    private data class Target(
        val prompt: String,
        /** Logical gimbal channel (StickChannels.ROLL/PITCH/YAW/THROTTLE), or -1 for aux. */
        val channel: Int = -1,
        /** Aux channel name (LS/RS/SA..SH) when channel == -1. */
        val auxName: String? = null,
        val kind: TKind,
    )

    // NOTE: the four gimbals are addressed by *logical* channel. The physical
    // StickSlot they bind to is resolved from the hand mode chosen on the first
    // screen (HandLayout.slot(handMode, channel)) at accept time, so Mode1..4
    // really re-targets the bindings instead of always writing Mode-2 slots.
    private val targets = listOf(
        Target("cal.fpv.roll", channel = StickChannels.ROLL, kind = TKind.ANALOG),
        Target("cal.fpv.pitch", channel = StickChannels.PITCH, kind = TKind.ANALOG),
        Target("cal.fpv.throttle", channel = StickChannels.THROTTLE, kind = TKind.ANALOG),
        Target("cal.fpv.yaw", channel = StickChannels.YAW, kind = TKind.ANALOG),
        Target("cal.fpv.ls", auxName = "LS", kind = TKind.ANALOG),
        Target("cal.fpv.rs", auxName = "RS", kind = TKind.ANALOG),
        Target("cal.fpv.sa", auxName = "SA", kind = TKind.SWITCH),
        Target("cal.fpv.sb", auxName = "SB", kind = TKind.SWITCH),
        Target("cal.fpv.sc", auxName = "SC", kind = TKind.SWITCH),
        Target("cal.fpv.sd", auxName = "SD", kind = TKind.SWITCH),
        Target("cal.fpv.se", auxName = "SE", kind = TKind.SWITCH),
        Target("cal.fpv.sf", auxName = "SF", kind = TKind.SWITCH),
        Target("cal.fpv.sg", auxName = "SG", kind = TKind.SWITCH),
        Target("cal.fpv.sh", auxName = "SH", kind = TKind.SWITCH),
    )

    private var phase = 0          // 0=hand, 1=bind, 2=done
    private var tIdx = 0

    // Detection state for the current target.
    private var prevAxes = FloatArray(0)
    private var prevButtons = ByteArray(0)
    private var prevHats = ByteArray(0)
    private var foundKind = -1      // 0 axis, 1 button, 2 hat
    private var foundIdx = -1
    private var baseline = 0f
    private var curMin = 0f
    private var curMax = 0f
    private var positions = ArrayList<Float>()

    private val cfg get() = FpvClient.config

    override fun init() {
        clearWidgets()
        val cx = width / 2
        when (phase) {
            0 -> {
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.hand", cfg.handMode)) {
                        cfg.handMode = cfg.handMode % 4 + 1; rebuild()
                    }.bounds(cx - 60, height / 2 + 40, 120, 20).build()
                )
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.next")) {
                        phase = 1; tIdx = 0; resetTarget(); rebuild()
                    }.bounds(cx - 60, height / 2 + 70, 120, 20).build()
                )
            }
            1 -> {
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.retest")) { resetTarget(); rebuild() }
                        .bounds(cx - 190, height - 60, 110, 20).build()
                )
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.skip")) { skipTarget() }
                        .bounds(cx - 60, height - 60, 120, 20).build()
                )
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.next")) { acceptTarget() }
                        .bounds(cx + 70, height - 60, 120, 20).build()
                )
            }
            2 -> {
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.finish")) { cfg.save(); onClose() }
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

    private fun resetTarget() {
        foundKind = -1; foundIdx = -1; positions.clear()
        // Drop the previous target's baselines so the first frame of the new
        // target re-seeds cleanly instead of latching a stale delta.
        prevAxes = FloatArray(0); prevButtons = ByteArray(0); prevHats = ByteArray(0)
    }

    private fun nextTarget() {
        tIdx++
        if (tIdx >= targets.size) phase = 2 else resetTarget()
        rebuild()
    }

    private fun acceptTarget() {
        val t = targets[tIdx]
        // Only commit when a source was actually detected AND (for analog) the
        // pilot swept a real travel; otherwise leave the binding untouched.
        val detected = foundKind >= 0 && foundIdx >= 0
        val analogTravel = if (foundKind == 0) (curMax - curMin) else 1f
        if (detected && (t.kind == TKind.SWITCH || analogTravel >= MIN_ANALOG_TRAVEL)) {
            // Center = midpoint of the observed travel (self-consistent with the
            // two-sided normalizer); the latch-time baseline is NOT used as center.
            val mid = (curMin + curMax) / 2f
            val type = when (foundKind) { 1 -> "BUTTON"; 2 -> "HAT"; else -> "AXIS" }
            if (t.channel >= 0) {
                val slot = HandLayout.slot(cfg.handMode, t.channel)
                cfg.slotCalib[slot.ordinal] = SlotCalib(
                    type = type,
                    axisIndex = foundIdx,
                    reversed = false,
                    rawMin = curMin, rawMid = mid, rawMax = curMax,
                    positions = ArrayList(positions),
                    learned = true,
                )
            } else if (t.auxName != null) {
                val existing = cfg.auxChannels.firstOrNull { it.name == t.auxName }
                val ac = existing ?: dev.fpv.input.AuxChannel(name = t.auxName).also { cfg.auxChannels.add(it) }
                ac.kind = if (foundKind == 0) "AXIS" else "BUTTONS"
                ac.axisIndex = foundIdx
                ac.reversed = false
                ac.rawMin = curMin; ac.rawMid = mid; ac.rawMax = curMax
                ac.positions = ArrayList(positions)
                ac.learned = true
            }
        }
        nextTarget()
    }

    /**
     * Skip explicitly UNBINDS this control (axisIndex = -1, unlearned) so a
     * skipped or unrecognised gimbal/aux can never leak into flight. The pilot
     * can re-run the wizard to bind it later.
     */
    private fun skipTarget() {
        val t = targets[tIdx]
        if (t.channel >= 0) {
            val slot = HandLayout.slot(cfg.handMode, t.channel)
            cfg.slotCalib[slot.ordinal] = SlotCalib()
        } else if (t.auxName != null) {
            cfg.auxChannels.firstOrNull { it.name == t.auxName }?.let {
                it.axisIndex = -1
                it.learned = false
                it.positions.clear()
            }
        }
        nextTarget()
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // Background already drawn by renderWithTooltipAndSubtitles (see FpvConfigScreen).
        FpvClient.input.rawAxes() // refresh raw snapshots
        val cx = width / 2

        when (phase) {
            0 -> {
                g.drawCenteredString(font, Component.translatable("cal.fpv.mode_title"), cx, height / 2 - 60, 0xFFFFFFFF.toInt())
                g.drawCenteredString(font, Component.translatable("cal.fpv.mode_default"), cx, height / 2 - 35, 0xFFAAAAAA.toInt())
            }
            1 -> {
                val t = targets[tIdx]
                g.drawCenteredString(font, Component.translatable(t.prompt), cx, height / 2 - 70, 0xFFFFFFFF.toInt())
                observe(t)
                val status = when {
                    foundKind < 0 -> Component.translatable("cal.fpv.move_hint")
                    t.kind == TKind.SWITCH -> Component.translatable("cal.fpv.levels", positions.size)
                    else -> Component.translatable("cal.fpv.detected", sourceLabel())
                }
                g.drawCenteredString(font, status, cx, height / 2 + 60, 0xFF55FF55.toInt())
            }
            2 -> {
                g.drawCenteredString(font, Component.translatable("cal.fpv.done_title"), cx, height / 2 - 40, 0xFF55FF55.toInt())
                g.drawCenteredString(font, Component.translatable("cal.fpv.done_desc"), cx, height / 2 - 10, 0xFFAAAAAA.toInt())
            }
        }
        super.render(g, mouseX, mouseY, delta)
    }

    private fun sourceLabel(): String = when (foundKind) {
        0 -> "Axis ${foundIdx + 1}"
        1 -> "Button ${foundIdx + 1}"
        else -> "Hat ${foundIdx + 1}"
    }

    /** Watch raw sources and bind whichever moved most; track extrema/levels. */
    private fun observe(t: Target) {
        val ax = FpvClient.input.axes()
        val bt = FpvClient.input.buttons()
        val ht = FpvClient.input.hats()

        // First frame: seed baselines.
        if (prevAxes.isEmpty() && ax.isNotEmpty()) { prevAxes = ax.copyOf(); prevButtons = bt.copyOf(); prevHats = ht.copyOf() }

        var best = 0f; var bk = -1; var bi = -1
        for (i in ax.indices) {
            val d = abs(ax[i] - prevAxes[i])
            if (d > best) { best = d; bk = 0; bi = i }
        }
        for (i in bt.indices) {
            if (bt[i] != prevButtons[i]) { best = 1f; bk = 1; bi = i }
        }
        for (i in ht.indices) {
            if (ht[i] != prevHats[i]) { best = 1f; bk = 2; bi = i }
        }
        prevAxes = ax.copyOf(); prevButtons = bt.copyOf(); prevHats = ht.copyOf()

        if (bk < 0 || best < 0.15f) return
        // Latch the first source that moves enough, then keep sampling it.
        if (foundKind < 0) { foundKind = bk; foundIdx = bi; baseline = currentRaw(); curMin = baseline; curMax = baseline }

        if (bk == foundKind && bi == foundIdx) {
            val v = currentRaw()
            if (t.kind == TKind.SWITCH) {
                val level = if (foundKind == 0) v else v.toInt().toFloat()
                if (positions.none { abs(it - level) < 0.05f }) positions.add(level)
            } else {
                if (v < curMin) curMin = v
                if (v > curMax) curMax = v
            }
        }
    }

    private fun currentRaw(): Float = when (foundKind) {
        1 -> if (FpvClient.input.buttons().getOrElse(foundIdx) { 0 }.toInt() != 0) 1f else 0f
        2 -> FpvClient.input.hats().getOrElse(foundIdx) { 0 }.toFloat()
        else -> FpvClient.input.axes().getOrElse(foundIdx) { 0f }
    }

    override fun onClose() {
        cfg.save()
        minecraft.setScreen(parent)
    }

    companion object {
        /** Minimum swept raw travel that counts as a real analog calibration. */
        private const val MIN_ANALOG_TRAVEL = 0.08f
    }
}
