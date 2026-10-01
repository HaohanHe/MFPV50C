/*
 * FPV Craft - MIT
 * Cinematic keyframe timeline editor. Runs on top of a loaded replay:
 *   - drag/click the timeline rail to scrub and live-preview the spline camera;
 *   - "Set KF here" inserts/updates a keyframe at the current recorded pose;
 *   - select a keyframe to delete / cycle its interpolator / nudge its time;
 *   - Save writes the track atomically to the .cam.json sidecar; Done returns.
 * Every button has a real action (no dead controls).
 */
package dev.fpv.replay

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class CinematicEditorScreen(private val parent: Screen?) :
    Screen(Component.literal("Cinematic Track")) {

    private var selected = -1
    private var toast = ""
    private var toastUntil = 0L

    private val railX = 16
    private val railW = 360
    private val railY = 60

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        clearWidgets()
        ReplayManager.trackActive = true // live-preview the spline while editing

        var y = 90
        addBtn(16, y, 130, 18, "Set KF here") {
            val i = ReplayManager.upsertKeyframeHere()
            selected = i
            toast(if (i >= 0) "keyframe #$i @ ${ReplayManager.formatTime(ReplayManager.cursorSec)}" else "no replay loaded")
            rebuildWidgets()
        }; y += 22
        addBtn(16, y, 130, 18, "Delete KF") {
            if (selected in ReplayManager.track.keyframes.indices) {
                ReplayManager.deleteKeyframeAt(selected)
                selected = selected.coerceAtMost(ReplayManager.track.keyframes.lastIndex)
                toast("deleted")
            } else toast("select a keyframe first")
            rebuildWidgets()
        }; y += 22
        addBtn(16, y, 130, 18, "Interpolator") {
            if (selected in ReplayManager.track.keyframes.indices) {
                val label = ReplayManager.cycleKeyframeInterp(selected)
                toast("interp -> $label")
            } else toast("select a keyframe first")
            rebuildWidgets()
        }; y += 22
        addBtn(16, y, 62, 18, "KF -0.1s") {
            if (selected in ReplayManager.track.keyframes.indices) {
                val k = ReplayManager.track.keyframes[selected]
                k.tSec = (k.tSec - 0.1).coerceAtLeast(0.0)
                toast("moved to ${ReplayManager.formatTime(k.tSec)}")
            } else toast("select a keyframe first")
            rebuildWidgets()
        }
        addBtn(84, y, 62, 18, "KF +0.1s") {
            if (selected in ReplayManager.track.keyframes.indices) {
                val k = ReplayManager.track.keyframes[selected]
                k.tSec = (k.tSec + 0.1).coerceAtMost(ReplayManager.durationSec)
                toast("moved to ${ReplayManager.formatTime(k.tSec)}")
            } else toast("select a keyframe first")
            rebuildWidgets()
        }; y += 26

        addBtn(16, y, 130, 18, "Save track") {
            val ok = ReplayManager.saveTrack()
            toast(if (ok) "saved .cam.json" else "save failed (no replay?)")
        }; y += 22
        addBtn(16, y, 130, 18, "Done") { onClose() }
    }

    private fun addBtn(x: Int, y: Int, w: Int, h: Int, label: String, onPress: () -> Unit): Button {
        val b = Button.builder(Component.literal(label)) { onPress() }.bounds(x, y, w, h).build()
        addRenderableWidget(b)
        return b
    }

    private fun toast(msg: String) { toast = msg; toastUntil = System.currentTimeMillis() + 1500L }

    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = event.x(); val my = event.y()
        val f = ReplayManager.file ?: return super.mouseClicked(event, doubled)
        val dur = ReplayManager.durationSec
        if (event.button() == 0 && mx >= railX && mx <= railX + railW && my >= railY - 6 && my <= railY + 10) {
            // Click near a keyframe tick selects it; otherwise just scrubs.
            val t = ((mx - railX) / railW).coerceIn(0.0, 1.0) * dur
            var best = -1; var bestD = 0.15
            ReplayManager.track.keyframes.forEachIndexed { i, k ->
                val d = kotlin.math.abs(k.tSec - t)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0) { selected = best; toast("selected KF #$best"); rebuildWidgets() }
            ReplayManager.scrubTo(t)
            return true
        }
        return super.mouseClicked(event, doubled)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(g, mouseX, mouseY, delta)
        val font = this.font
        g.drawString(font, Component.literal("Cinematic Camera Track"), 16, 8, 0xFFFFFFFF.toInt(), true)

        val f = ReplayManager.file
        if (f == null) {
            g.drawString(font, Component.literal("load a replay first"), 16, 30, 0xFFAAAAAA.toInt(), true)
        } else {
            val dur = ReplayManager.durationSec
            // Rail + fill.
            g.fill(railX, railY, railX + railW, railY + 4, 0xFF222222.toInt())
            val frac = (ReplayManager.cursorSec / dur).coerceIn(0.0, 1.0)
            g.fill(railX, railY, railX + (railW * frac).toInt(), railY + 4, 0xFF55FF55.toInt())
            // Keyframe ticks.
            ReplayManager.track.keyframes.forEachIndexed { i, k ->
                val tx = railX + ((k.tSec / dur).coerceIn(0.0, 1.0) * railW).toInt()
                val col = if (i == selected) 0xFFFFFFFF.toInt() else 0xFF55FFFF.toInt()
                g.fill(tx, railY - 5, tx + 1, railY + 9, col)
            }
            // Timecode + selected keyframe info.
            g.drawString(font, Component.literal(
                "${ReplayManager.formatTime(ReplayManager.cursorSec)} / ${ReplayManager.formatTime(dur)}"
            ), railX, railY + 12, 0xFF55FF55.toInt(), true)
            g.drawString(font, Component.literal("keyframes: ${ReplayManager.track.keyframes.size}"), railX, railY + 24, 0xFFAAAAAA.toInt(), true)
            if (selected in ReplayManager.track.keyframes.indices) {
                val k = ReplayManager.track.keyframes[selected]
                g.drawString(font, Component.literal(
                    "sel #$selected t=${ReplayManager.formatTime(k.tSec)} interp=${k.interp.name}"
                ), railX, railY + 36, 0xFFFFFF55.toInt(), true)
            }
        }

        if (System.currentTimeMillis() < toastUntil)
            g.drawCenteredString(font, toast, width / 2, height - 12, 0xFFFFFFFF.toInt())
    }

    override fun onClose() {
        ReplayManager.trackActive = !ReplayManager.track.isEmpty()
        minecraft.setScreen(parent)
    }
}
