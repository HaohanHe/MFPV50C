/*
 * FPV Craft - MIT
 * Config safety screen: shows any load/restore notice, lists rotated backups
 * newest-first with one-click restore, and offers reset-to-safe-defaults. All
 * actions mutate the live config (effective next frame) and persist atomically.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.flight.FpvConfig
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class SafetyScreen(private val parent: Screen?) : Screen(Component.translatable("gui.fpv.safety")) {

    private val cfg: FpvConfig get() = FpvClient.config

    override fun init() {
        var y = 40
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.safety.reset")) {
                cfg.resetToDefaults()
                clearWidgets(); init()
            }.bounds(12, y, 180, 18).build()
        )
        y += 26

        val labels = cfg.backupLabels()
        if (labels.isEmpty()) {
            // No backups yet: nothing more to show.
        } else {
            labels.forEachIndexed { i, label ->
                addRenderableWidget(
                    Button.builder(Component.literal(label)) {
                        cfg.restoreBackup(i)
                        clearWidgets(); init()
                    }.bounds(12, y, 180, 18).build()
                )
                y += 20
            }
        }

        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.back")) { minecraft.setScreen(parent) }
                .bounds(12, height - 26, 90, 18).build()
        )
    }

    override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        renderBackground(ctx, mouseX, mouseY, partial)
        ctx.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF)
        val notice = cfg.loadNotice
        if (notice.isNotEmpty())
            ctx.drawCenteredString(font, Component.literal(notice), width / 2, 26, 0x55FF55)
        super.render(ctx, mouseX, mouseY, partial)
    }
}
