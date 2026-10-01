/*
 * FPV Craft - MIT
 * Replay center (isPauseScreen=false): pick a recorded .fpr, scrub the
 * timeline, pause / change speed / switch view, and jump to the cinematic
 * exporter. Buttons all drive ReplayManager for real.
 */
package dev.fpv.replay

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ReplayScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.replay")) {

    private var files: List<java.nio.file.Path> = emptyList()
    private var selected = -1
    private var toast = ""
    private var toastUntil = 0L

    private val scrubX = 12
    private val scrubW = 360
    private val scrubY = 120

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        clearWidgets()
        files = FlightRecorder.listReplays()
        if (selected >= files.size) selected = files.lastIndex

        val lx = 12
        // File list rows (left).
        var y = 30
        for ((i, f) in files.withIndex()) {
            val name = f.fileName.toString()
            addBtn(lx, y, 150, 16, if (i == selected) "> $name" else "  $name") {
                selected = i
                loadSelected()
            }
            y += 18
            if (y > 110) break
        }
        if (files.isEmpty()) {
            addRenderableWidget(
                Button.builder(Component.translatable("gui.fpv.replay.empty")) { toast("record a flight first") }
                    .bounds(lx, y, 150, 16).build()
            )
        }

        // Transport cluster (right).
        val rx = 200
        var ty = 30
        addBtn(rx, ty, 90, 18, "gui.fpv.replay.playpause") { ReplayManager.togglePause() }; ty += 22
        addBtn(rx, ty, 90, 18, "gui.fpv.replay.stop") { ReplayManager.stop() }; ty += 22
        addBtn(rx, ty, 90, 18, "gui.fpv.replay.speed") { ReplayManager.cycleSpeed() }; ty += 22
        addBtn(rx, ty, 90, 18, "gui.fpv.replay.view") { ReplayManager.cycleView() }; ty += 22
        addBtn(rx, ty, 90, 18, "gui.fpv.replay.export") {
            if (ReplayManager.file == null) toast("load a replay first")
            else minecraft.setScreen(ExportScreen(this))
        }; ty += 22
        addBtn(rx, ty, 90, 18, "Cameras...") {
            if (ReplayManager.file == null) toast("load a replay first")
            else minecraft.setScreen(CinematicEditorScreen(this))
        }; ty += 24

        addBtn(12, height - 26, 110, 18, "gui.fpv.replay.close") { onClose() }
    }

    private fun loadSelected() {
        if (selected < 0) return
        val ok = ReplayManager.load(files[selected])
        toast(if (ok) "loaded" else "failed to read file")
    }

    private fun addBtn(x: Int, y: Int, w: Int, h: Int, labelKey: String, onPress: () -> Unit): Button {
        val b = Button.builder(Component.translatable(labelKey)) { onPress() }.bounds(x, y, w, h).build()
        addRenderableWidget(b)
        return b
    }

    private fun toast(msg: String) { toast = msg; toastUntil = System.currentTimeMillis() + 1500L }

    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubled: Boolean): Boolean {
        // Scrub bar: click horizontally anywhere on it to jump.
        val mx = event.x()
        val my = event.y()
        if (event.button() == 0 && ReplayManager.file != null &&
            mx >= scrubX && mx <= scrubX + scrubW &&
            my >= scrubY - 4 && my <= scrubY + 8
        ) {
            val u = ((mx - scrubX) / scrubW).coerceIn(0.0, 1.0)
            ReplayManager.scrubTo(u * ReplayManager.durationSec)
            return true
        }
        return super.mouseClicked(event, doubled)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // Background already drawn by renderWithTooltipAndSubtitles.
        super.render(g, mouseX, mouseY, delta)
        val font = this.font
        g.drawString(font, Component.translatable("screen.fpv.replay"), 12, 8, 0xFFFFFFFF.toInt(), true)

        val f = ReplayManager.file
        if (f == null) {
            g.drawString(font, Component.translatable("gui.fpv.replay.hint"), 180, 80, 0xFFAAAAAA.toInt(), true)
        } else {
            // Timeline rail + fill.
            g.fill(scrubX, scrubY, scrubX + scrubW, scrubY + 4, 0xFF222222.toInt())
            val frac = (ReplayManager.cursorSec / ReplayManager.durationSec).coerceIn(0.0, 1.0)
            g.fill(scrubX, scrubY, scrubX + (scrubW * frac).toInt(), scrubY + 4, 0xFF55FF55.toInt())
            // Timecode.
            g.drawString(
                font,
                Component.literal(
                    "${ReplayManager.formatTime(ReplayManager.cursorSec)} / " +
                        ReplayManager.formatTime(ReplayManager.durationSec)
                ),
                scrubX, scrubY + 8, 0xFF55FF55.toInt(), true,
            )
            // Speed / view / pause state.
            g.drawString(font, Component.literal("x${ReplayManager.speed}"), scrubX + 200, scrubY + 8, 0xFF55FFFF.toInt(), true)
            g.drawString(font, Component.literal(ReplayManager.view.name), scrubX + 260, scrubY + 8, 0xFF55FFFF.toInt(), true)
            if (ReplayManager.paused)
                g.drawCenteredString(font, Component.translatable("gui.fpv.replay.paused"), width / 2, 60, 0xFFFFFF55.toInt())

            g.drawString(font, Component.translatable("gui.fpv.replay.limit_note"), 12, height - 44, 0xFFAAAAAA.toInt(), true)
        }

        if (System.currentTimeMillis() < toastUntil)
            g.drawCenteredString(font, toast, width / 2, height - 12, 0xFFFFFFFF.toInt())
    }

    override fun onClose() {
        ReplayManager.stop()
        minecraft.setScreen(parent)
    }
}
