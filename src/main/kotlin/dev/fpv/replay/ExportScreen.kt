/*
 * FPV Craft - MIT
 * Cinematic export dialog: cycles the ExportConfig knobs, detects ffmpeg,
 * starts the offline frame loop, and shows real progress (frame/total) with a
 * cancel button. While exporting the screen stays open and polls progress.
 */
package dev.fpv.replay

import dev.fpv.flight.ExportConfig
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ExportScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.export")) {

    private val cfg get() = dev.fpv.client.FpvClient.config.export ?: ExportConfig()

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        clearWidgets()
        val cx = width / 2 - 100
        var y = 30
        addCycle(cx, y, 200, "gui.fpv.export.aspect",
            listOf("16:9", "9:16", "2.35:1", "2.39:1", "21:9"),
            { cfg.aspect }, { cfg.aspect = it }); y += 22
        addCycle(cx, y, 200, "gui.fpv.export.resolution",
            listOf("720p", "1080p", "1440p", "2160p"),
            { cfg.resolution }, { cfg.resolution = it }); y += 22
        addCycleI(cx, y, 200, "gui.fpv.export.fps",
            intArrayOf(24, 30, 60), { cfg.fps }, { cfg.fps = it }); y += 22
        addCycleI(cx, y, 200, "gui.fpv.export.blur",
            intArrayOf(1, 2, 4, 6, 8), { cfg.motionBlurSamples }, { cfg.motionBlurSamples = it }); y += 22
        addCycle(cx, y, 200, "gui.fpv.export.container",
            listOf("mp4", "mkv"), { cfg.container }, { cfg.container = it }); y += 24

        val ffmpeg = CinematicExport.detectFfmpeg()
        val ffmpegLabel = if (ffmpeg != null) "gui.fpv.export.ffmpeg_ok" else "gui.fpv.export.ffmpeg_missing"
        addRenderableWidget(
            Button.builder(Component.translatable(ffmpegLabel)) {}
                .bounds(cx, y, 200, 18).build().apply { active = false }
        )
        y += 22

        if (CinematicExport.exporting) {
            addBtn(cx, y, 120, 20, "gui.fpv.export.cancel") { CinematicExport.cancel() }; y += 24
        } else {
            addBtn(cx, y, 200, 22, "gui.fpv.export.start") {
                dev.fpv.client.FpvClient.config.save()
                CinematicExport.start()
            }; y += 24
        }

        addBtn(cx, y, 200, 18, "gui.fpv.replay.close") { onClose() }
    }

    private fun addBtn(x: Int, y: Int, w: Int, h: Int, key: String, onPress: () -> Unit): Button {
        val b = Button.builder(Component.translatable(key)) { onPress() }.bounds(x, y, w, h).build()
        addRenderableWidget(b); return b
    }

    private fun addCycle(x: Int, y: Int, w: Int, key: String, opts: List<String>,
                         get: () -> String, set: (String) -> Unit) {
        lateinit var b: Button
        b = Button.builder(Component.literal("")) {
            val i = opts.indexOf(get()).let { if (it < 0) 0 else it }
            set(opts[(i + 1) % opts.size])
            b.message = Component.translatable(key, get())
        }.bounds(x, y, w, 18).build()
        b.message = Component.translatable(key, get())
        addRenderableWidget(b)
    }

    private fun addCycleI(x: Int, y: Int, w: Int, key: String, opts: IntArray,
                          get: () -> Int, set: (Int) -> Unit) {
        lateinit var b: Button
        b = Button.builder(Component.literal("")) {
            val i = opts.indexOfFirst { it >= get() }.let { if (it < 0) opts.lastIndex else it }
            set(opts[(i + 1) % opts.size])
            b.message = Component.translatable(key, get())
        }.bounds(x, y, w, 18).build()
        b.message = Component.translatable(key, get())
        addRenderableWidget(b)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // Background already drawn by renderWithTooltipAndSubtitles.
        super.render(g, mouseX, mouseY, delta)
        g.drawCenteredString(font, Component.translatable("screen.fpv.export"), width / 2, 8, 0xFFFFFFFF.toInt())

        if (CinematicExport.exporting) {
            val frac = if (CinematicExport.totalFrames > 0)
                CinematicExport.currentFrame.toDouble() / CinematicExport.totalFrames else 0.0
            val bw = 200
            g.fill(width / 2 - bw / 2, height - 60, width / 2 + bw / 2, height - 56, 0xFF222222.toInt())
            g.fill(width / 2 - bw / 2, height - 60,
                width / 2 - bw / 2 + (bw * frac).toInt(), height - 56, 0xFF55FF55.toInt())
            g.drawCenteredString(
                font,
                Component.translatable("gui.fpv.export.progress",
                    CinematicExport.currentFrame, CinematicExport.totalFrames),
                width / 2, height - 52, 0xFF55FF55.toInt(),
            )
        }
        if (CinematicExport.status.isNotEmpty()) {
            g.drawCenteredString(font, CinematicExport.status, width / 2, height - 30, 0xFFFFFF55.toInt())
        }
    }

    override fun onClose() {
        if (!CinematicExport.exporting) minecraft.setScreen(parent)
    }
}
