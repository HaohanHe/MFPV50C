/*
 * FPV Craft - MIT
 * Paste-a-Betaflight-CLI-diff screen: multi-line text box + Import button,
 * then shows applied settings / hard errors / unsupported names. Pure UI over
 * the clean-room BetaflightCli parser (flight/BetaflightCli.kt).
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.flight.BetaflightCli
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class BfImportScreen(private val parent: Screen?) : Screen(Component.literal("Import Betaflight CLI")) {

    private lateinit var editor: EditBox
    private var resultLines: List<String> = emptyList()
    private var resultColor: Int = 0xFFFFFF

    override fun init() {
        val boxW = width - 24
        editor = EditBox(font, 12, 28, boxW, 20, Component.literal("BF CLI diff"))
        editor.setMaxLength(20000)
        editor.setHint(Component.literal("paste `set roll_rc_rate = 4.50` ... lines here"))
        addRenderableWidget(editor)

        addRenderableWidget(
            Button.builder(Component.literal("Import")) {
                doImport()
            }.bounds(12, 54, 120, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Back")) {
                minecraft.setScreen(parent)
            }.bounds(width - 132, height - 26, 120, 18).build()
        )
    }

    private fun doImport() {
        val cfg = FpvClient.config
        val res = BetaflightCli.importInto(editor.value, cfg)
        cfg.save()
        val lines = mutableListOf<String>()
        lines += if (res.errors.isEmpty()) "OK: applied ${res.applied.size} setting(s), rate=${res.rateType}"
                 else "APPLIED ${res.applied.size}, but ${res.errors.size} ERROR(S):"
        lines += res.applied.take(40)
        for (e in res.errors.take(20)) lines += "! $e"
        for (u in res.unsupported.take(8)) lines += "? $u"
        if (res.unsupported.size > 8) lines += "? ...${res.unsupported.size - 8} more unsupported"
        if (res.skippedUnrelated > 0) lines += "(skipped ${"%d".format(res.skippedUnrelated)} unrelated lines)"
        resultLines = lines
        resultColor = if (res.errors.isEmpty()) 0x55FF55 else 0xFF5555
    }

    override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        renderBackground(ctx, mouseX, mouseY, partial)
        ctx.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF)
        super.render(ctx, mouseX, mouseY, partial)
        var y = 80
        for (l in resultLines) {
            if (y > height - 40) break
            ctx.drawString(font, l, 12, y, resultColor, false)
            y += 11
        }
    }
}
