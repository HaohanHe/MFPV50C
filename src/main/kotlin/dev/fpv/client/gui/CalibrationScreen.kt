/*
 * FPV Craft - MIT
 * Graphical radio calibration wizard (clean-room).
 *
 *   0 HAND    : pick Mode 1..4 over a live twin-stick map showing which stick
 *               carries Roll/Pitch/Yaw/Throttle (re-drawn on every Mode change).
 *   1 CENTER  : release every control; live per-axis bars + a settle detector,
 *               so the move-to-bind step starts from a still baseline.
 *   2 BIND    : for each control, move the REAL control. A large scope draws
 *               the live stick position and the measured endpoints (analog), or
 *               the detected positions (switch). No axis/button order assumed.
 *   3 DONE    : summary.
 *
 * The wizard watches ALL raw axes/buttons/hats and binds whichever source
 * changed the most; unused controls are skipped and output neutral.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.input.HandLayout
import dev.fpv.input.SlotCalib
import dev.fpv.input.StickChannels
import dev.fpv.input.StickSlot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.roundToInt

class CalibrationScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.calibrate")) {

    private enum class TKind { ANALOG, SWITCH }

    private data class Target(
        val prompt: String,
        /** Logical gimbal channel (StickChannels.*), or -1 for an aux control. */
        val channel: Int = -1,
        /** Aux channel name (LS/RS/SA..SH) when channel == -1. */
        val auxName: String? = null,
        val kind: TKind,
    )

    // The four gimbals are addressed by *logical* channel; the physical StickSlot
    // is resolved from the chosen hand mode at accept time (HandLayout.slot).
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

    private var phase = 0          // 0 hand, 1 center, 2 bind, 3 done
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

    // Center-step settle detection.
    private var settleCount = 0
    private var settled = false

    private var centerNext: Button? = null

    private val cfg get() = FpvClient.config

    // ---- Opaque-ARGB palette (1.21.11 discards text whose alpha == 0). ----
    private val WHITE = 0xFFFFFFFF.toInt()
    private val DIM = 0xFF9A9AA2.toInt()
    private val FAINT = 0xFF6A6A72.toInt()
    private val GOOD = 0xFF54E06A.toInt()
    private val WARN = 0xFFFFD23F.toInt()
    private val BAR_BG = 0xFF202026.toInt()
    private val SCOPE_BG = 0x33000000
    private val C_ROLL = 0xFF46E05A.toInt()
    private val C_PITCH = 0xFF4FA8FF.toInt()
    private val C_YAW = 0xFFFFB02E.toInt()
    private val C_THROTTLE = 0xFFFF4FA3.toInt()

    override fun init() {
        clearWidgets()
        val cx = width / 2
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.cancel")) { onClose() }
                .bounds(10, 10, 70, 20).build()
        )
        when (phase) {
            0 -> {
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.hand", cfg.handMode)) {
                        cfg.handMode = cfg.handMode % 4 + 1; rebuild()
                    }.bounds(cx - 60, height - 52, 120, 20).build()
                )
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.next")) {
                        phase = 1; settleCount = 0; settled = false; rebuild()
                    }.bounds(cx - 60, height - 28, 120, 20).build()
                )
            }
            1 -> {
                val btn = Button.builder(Component.translatable("gui.fpv.next")) {
                    phase = 2; tIdx = 0; resetTarget(); rebuild()
                }.bounds(cx - 60, height - 28, 120, 20).build()
                centerNext = btn
                addRenderableWidget(btn)
            }
            2 -> {
                val bw = ((width - 40) / 3).coerceAtMost(110)
                val total = bw * 3 + 20
                val x0 = cx - total / 2
                val y = height - 28
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.retest")) { resetTarget(); rebuild() }
                        .bounds(x0, y, bw, 20).build()
                )
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.skip")) { skipTarget() }
                        .bounds(x0 + bw + 10, y, bw, 20).build()
                )
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.next")) { acceptTarget() }
                        .bounds(x0 + 2 * (bw + 10), y, bw, 20).build()
                )
            }
            3 -> {
                addRenderableWidget(
                    Button.builder(Component.translatable("gui.fpv.finish")) { cfg.save(); onClose() }
                        .bounds(cx - 60, height - 28, 120, 20).build()
                )
            }
        }
    }

    private fun rebuild() = init()

    private fun resetTarget() {
        foundKind = -1; foundIdx = -1; positions.clear()
        // Drop baselines so the new target's first frame re-seeds cleanly.
        prevAxes = FloatArray(0); prevButtons = ByteArray(0); prevHats = ByteArray(0)
    }

    private fun nextTarget() {
        tIdx++
        if (tIdx >= targets.size) phase = 3 else resetTarget()
        rebuild()
    }

    private fun acceptTarget() {
        val t = targets[tIdx]
        val detected = foundKind >= 0 && foundIdx >= 0
        val analogTravel = if (foundKind == 0) (curMax - curMin) else 1f
        // Only commit when a source was detected AND (analog) a real sweep was made.
        if (detected && (t.kind == TKind.SWITCH || analogTravel >= MIN_ANALOG_TRAVEL)) {
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

    /** Skip explicitly UNBINDS this control so an unrecognised source can never
     *  leak into flight. The wizard can be re-run to bind it later. */
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
        FpvClient.input.rawAxes() // refresh raw snapshots
        val cx = width / 2
        when (phase) {
            0 -> renderHand(g, cx)
            1 -> renderCenter(g, cx)
            2 -> renderBind(g, cx)
            3 -> renderDone(g, cx)
        }
        super.render(g, mouseX, mouseY, delta)
    }

    // ---- Phase 0: hand mode + live stick map ----
    private fun renderHand(g: GuiGraphics, cx: Int) {
        g.drawCenteredString(font, Component.translatable("cal.fpv.mode_title"), cx, 34, WHITE)
        g.drawCenteredString(font, Component.translatable("cal.fpv.mode_desc"), cx, 48, DIM)
        drawStickMap(g, cx, height / 2 + 4, 26, 58, -1)
    }

    // ---- Phase 1: center / settle ----
    private fun renderCenter(g: GuiGraphics, cx: Int) {
        val ax = FpvClient.input.axes()
        val bt = FpvClient.input.buttons()
        val ht = FpvClient.input.hats()

        g.drawCenteredString(font, Component.translatable("cal.fpv.center_title"), cx, 30, WHITE)
        g.drawCenteredString(font, Component.translatable("cal.fpv.center_hint"), cx, 44, DIM)

        if (ax.isEmpty()) {
            // No radio: nothing to center.
            settled = true
            g.drawCenteredString(font, Component.translatable("cal.fpv.center_ok"), cx, height / 2, GOOD)
        } else {
            // Settle detector: every raw source must hold still for CENTER_FRAMES.
            var moving = false
            if (prevAxes.size == ax.size) {
                for (i in ax.indices) if (abs(ax[i] - prevAxes[i]) > CENTER_EPS) moving = true
                for (i in bt.indices) if (bt[i] != prevButtons.getOrElse(i) { 0 }) moving = true
                for (i in ht.indices) if (ht[i] != prevHats.getOrElse(i) { 0 }) moving = true
            } else moving = true
            prevAxes = ax.copyOf(); prevButtons = bt.copyOf(); prevHats = ht.copyOf()

            settleCount = if (moving) 0 else settleCount + 1
            settled = settleCount >= CENTER_FRAMES

            // Compact live bars for every axis.
            val barW = (width * 0.46f).roundToInt().coerceAtLeast(80)
            val barX = cx - barW / 2
            val nameX = barX - 26
            val startY = 66
            val rowH = 11
            for (i in ax.indices) {
                val y = startY + i * rowH
                if (y > height - 40) break
                g.drawString(font, Component.literal("A${i + 1}"), nameX, y, FAINT)
                g.fill(barX, y + 2, barX + barW, y + 6, BAR_BG)
                val midx = barX + barW / 2
                g.fill(midx, y + 1, midx, y + 7, FAINT)
                val v = ax[i].coerceIn(-1f, 1f)
                if (v >= 0) g.fill(midx, y + 2, midx + (barW / 2 * v).toInt(), y + 6, GOOD)
                else g.fill(midx + (barW / 2 * v).toInt(), y + 2, midx, y + 6, WARN)
            }
            val msg = if (settled) "cal.fpv.center_ok" else "cal.fpv.center_wait"
            g.drawCenteredString(font, Component.translatable(msg), cx, height - 46,
                if (settled) GOOD else WARN)
        }
        centerNext?.active = settled
    }

    // ---- Phase 2: per-control graphical binding ----
    private fun renderBind(g: GuiGraphics, cx: Int) {
        val t = targets[tIdx]
        g.drawCenteredString(font, Component.translatable(t.prompt), cx, 26, WHITE)

        // Small stick map for gimbals (highlight this channel); aux gets a caption.
        val scopeCy = height / 2 + 12
        if (t.channel >= 0) {
            drawStickMap(g, cx, 52, 13, 46, t.channel, showLabels = false)
        } else {
            g.drawCenteredString(font, Component.literal(t.auxName ?: ""), cx, 50, DIM)
        }

        observe(t)
        drawBindScope(g, t, cx, scopeCy)

        // Status line.
        val status: Component = when {
            foundKind < 0 -> Component.translatable("cal.fpv.move_hint")
            t.kind == TKind.SWITCH -> {
                val n = positions.size
                if (n >= 2) Component.translatable("cal.fpv.levels", n)
                else Component.translatable("cal.fpv.switch_more")
            }
            else -> {
                val travel = curMax - curMin
                if (travel >= GOOD_ANALOG_TRAVEL)
                    Component.translatable("cal.fpv.travel_good", sourceLabel())
                else
                    Component.translatable("cal.fpv.travel_low", sourceLabel())
            }
        }
        val statusColor = when {
            foundKind < 0 -> WARN
            t.kind == TKind.SWITCH -> if (positions.size >= 2) GOOD else WARN
            else -> if ((curMax - curMin) >= GOOD_ANALOG_TRAVEL) GOOD else WARN
        }
        g.drawCenteredString(font, status, cx, scopeCy + SCOPE_ARM + 16, statusColor)
    }

    /** Large live scope: gimbal cross with a moving dot + endpoints, a vertical
     *  travel rail for sliders, or a position track for switches. */
    private fun drawBindScope(g: GuiGraphics, t: Target, cx: Int, cy: Int) {
        val arm = SCOPE_ARM
        g.fill(cx - arm - 6, cy - arm - 6, cx + arm + 6, cy + arm + 6, SCOPE_BG)
        when {
            t.kind == TKind.SWITCH -> {
                // Horizontal position track.
                g.fill(cx - arm, cy, cx + arm, cy + 1, FAINT)
                val cur = if (foundKind >= 0) currentRaw() else 0f
                for (p in positions) {
                    val px = switchX(cx, p, arm)
                    g.fill(px - 2, cy - 8, px + 3, cy + 9, GOOD)
                }
                if (foundKind >= 0) {
                    val px = switchX(cx, cur, arm)
                    g.fill(px - 1, cy - 11, px + 2, cy + 12, WHITE)
                }
            }
            t.channel >= 0 -> {
                val slot = HandLayout.slot(cfg.handMode, t.channel)
                val horiz = slot == StickSlot.LH || slot == StickSlot.RH
                val chColor = channelColor(t.channel)
                // Cross.
                g.fill(cx - arm, cy, cx + arm, cy + 1, FAINT)
                g.fill(cx, cy - arm, cx + 1, cy + arm, FAINT)
                // Endpoint targets on the active axis; turn green once reached.
                val half = (curMax - curMin) / 2f
                val reached = half >= GOOD_ANALOG_TRAVEL / 2f
                val epColor = if (reached) GOOD else FAINT
                if (horiz) {
                    g.fill(cx - arm - 1, cy - 4, cx - arm + 2, cy + 5, epColor)
                    g.fill(cx + arm - 1, cy - 4, cx + arm + 2, cy + 5, epColor)
                } else {
                    g.fill(cx - 4, cy - arm - 1, cx + 5, cy - arm + 2, epColor)
                    g.fill(cx - 4, cy + arm - 1, cx + 5, cy + arm + 2, epColor)
                }
                // Live dot.
                if (foundKind == 0) {
                    val n = analogNorm(currentRaw())
                    val dx = if (horiz) (n * arm).roundToInt() else 0
                    val dy = if (horiz) 0 else (-n * arm).roundToInt()
                    g.fill(cx + dx - 2, cy + dy - 2, cx + dx + 3, cy + dy + 3, chColor)
                }
            }
            else -> {
                // Aux analog slider (LS/RS): vertical travel rail.
                g.fill(cx, cy - arm, cx + 1, cy + arm, FAINT)
                if (foundKind == 0) {
                    val lo = analogNorm(curMin); val hi = analogNorm(curMax)
                    val yLo = cy + (-lo * arm).roundToInt()
                    val yHi = cy + (-hi * arm).roundToInt()
                    g.fill(cx - 3, minOf(yLo, yHi), cx + 4, maxOf(yLo, yHi), 0x5546E05A)
                    val n = analogNorm(currentRaw())
                    val y = cy + (-n * arm).roundToInt()
                    g.fill(cx - 6, y - 1, cx + 7, y + 2, WHITE)
                }
            }
        }
    }

    // ---- Phase 3: done ----
    private fun renderDone(g: GuiGraphics, cx: Int) {
        g.drawCenteredString(font, Component.translatable("cal.fpv.done_title"), cx, height / 2 - 30, GOOD)
        g.drawCenteredString(font, Component.translatable("cal.fpv.done_desc"), cx, height / 2 - 12, DIM)
    }

    /** Twin-stick map. Labels are placed per the current hand mode; when
     *  [highlight] is a channel index, that channel and its axis are emphasised. */
    private fun drawStickMap(g: GuiGraphics, cx: Int, cy: Int, arm: Int, gap: Int, highlight: Int,
                             showLabels: Boolean = true) {
        val lx = cx - gap / 2
        val rx = cx + gap / 2
        for (centerX in intArrayOf(lx, rx)) {
            g.fill(centerX - arm, cy, centerX + arm, cy + 1, FAINT)
            g.fill(centerX, cy - arm, centerX + 1, cy + arm, FAINT)
        }
        for (ch in 0..3) {
            val on = highlight == ch
            val color = if (highlight < 0 || on) channelColor(ch) else FAINT
            val slot = HandLayout.slot(cfg.handMode, ch)
            val label = channelShort(ch)
            when (slot) {
                StickSlot.LH -> {
                    if (on) g.fill(lx - arm, cy, lx + arm, cy + 1, color)
                    if (showLabels)
                        g.drawString(font, Component.literal(label), lx - arm - 4 - font.width(label), cy - 4, color)
                }
                StickSlot.RH -> {
                    if (on) g.fill(rx - arm, cy, rx + arm, cy + 1, color)
                    if (showLabels)
                        g.drawString(font, Component.literal(label), rx + arm + 5, cy - 4, color)
                }
                StickSlot.LV -> {
                    if (on) g.fill(lx, cy - arm, lx + 1, cy + arm, color)
                    if (showLabels)
                        g.drawCenteredString(font, Component.literal(label), lx, cy - arm - 12, color)
                }
                StickSlot.RV -> {
                    if (on) g.fill(rx, cy - arm, rx + 1, cy + arm, color)
                    if (showLabels)
                        g.drawCenteredString(font, Component.literal(label), rx, cy - arm - 12, color)
                }
            }
        }
    }

    private fun channelColor(ch: Int): Int = when (ch) {
        StickChannels.ROLL -> C_ROLL
        StickChannels.PITCH -> C_PITCH
        StickChannels.YAW -> C_YAW
        else -> C_THROTTLE
    }

    private fun channelShort(ch: Int): String = when (ch) {
        StickChannels.ROLL -> "Roll"
        StickChannels.PITCH -> "Pitch"
        StickChannels.YAW -> "Yaw"
        else -> "Thr"
    }

    private fun sourceLabel(): String = when (foundKind) {
        0 -> "Axis ${foundIdx + 1}"
        1 -> "Button ${foundIdx + 1}"
        else -> "Hat ${foundIdx + 1}"
    }

    /** Normalized (-1..1) position of an analog source against measured travel. */
    private fun analogNorm(v: Float): Float {
        val mid = (curMin + curMax) / 2f
        val half = ((curMax - curMin) / 2f).coerceAtLeast(0.06f)
        return ((v - mid) / half).coerceIn(-1f, 1f)
    }

    /** Pixel x for a switch position value (axis raw, or button 0/1). */
    private fun switchX(cx: Int, v: Float, arm: Int): Int =
        if (foundKind == 0) cx + (v * arm).roundToInt()
        else cx + ((v - 0.5f) * 2f * arm * 0.7f).roundToInt()

    /** Watch raw sources and bind whichever moved most; track extrema/levels. */
    private fun observe(t: Target) {
        val ax = FpvClient.input.axes()
        val bt = FpvClient.input.buttons()
        val ht = FpvClient.input.hats()

        if (prevAxes.isEmpty() && ax.isNotEmpty()) {
            prevAxes = ax.copyOf(); prevButtons = bt.copyOf(); prevHats = ht.copyOf()
        }

        var best = 0f; var bk = -1; var bi = -1
        for (i in ax.indices) {
            val d = abs(ax[i] - prevAxes.getOrElse(i) { 0f })
            if (d > best) { best = d; bk = 0; bi = i }
        }
        for (i in bt.indices) {
            if (bt[i] != prevButtons.getOrElse(i) { 0 }) { best = 1f; bk = 1; bi = i }
        }
        for (i in ht.indices) {
            if (ht[i] != prevHats.getOrElse(i) { 0 }) { best = 1f; bk = 2; bi = i }
        }
        prevAxes = ax.copyOf(); prevButtons = bt.copyOf(); prevHats = ht.copyOf()

        if (bk < 0 || best < 0.15f) return
        if (foundKind < 0) {
            foundKind = bk; foundIdx = bi; baseline = currentRaw()
            curMin = baseline; curMax = baseline
        }
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
        /** Minimum swept raw travel that commits an analog binding. */
        private const val MIN_ANALOG_TRAVEL = 0.08f
        /** Travel at which the scope reports a good (full) sweep. */
        private const val GOOD_ANALOG_TRAVEL = 0.55f
        /** Per-source motion below which the center step counts as still. */
        private const val CENTER_EPS = 0.012f
        /** Consecutive still frames required at the center step. */
        private const val CENTER_FRAMES = 12
        /** Half-size of the large binding scope, px. */
        private const val SCOPE_ARM = 40
    }
}
