/*
 * FPV Craft - MIT
 * Channel monitor: lists every raw axis/button/hat of the attached radio with
 * its live value and the logical control it is bound to (if any). Purely a
 * diagnostic view - no writes.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class MonitorScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.monitor")) {

    private val cfg get() = FpvClient.config

    override fun init() {
        clearWidgets()
        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(width / 2 - 60, height - 28, 120, 20).build()
        )
    }

    /** Logical control a raw source drives, or "" when unbound. */
    private fun bindingLabel(kind: Int, idx: Int): String {
        // kind 0=axis 1=button 2=hat
        for (slot in dev.fpv.input.StickSlot.entries) {
            val sc = cfg.slotCalib[slot.ordinal]
            val k = when (sc.type) { "BUTTON" -> 1; "HAT" -> 2; else -> 0 }
            if (sc.axisIndex >= 0 && k == kind && sc.axisIndex == idx) return gimbalName(slot)
        }
        for (a in cfg.auxChannels) {
            val k = if (a.kind == "BUTTONS") 1 else 0
            if (a.axisIndex >= 0 && k == kind && a.axisIndex == idx) return a.name
        }
        return ""
    }

    private fun gimbalName(slot: dev.fpv.input.StickSlot): String = when (slot) {
        dev.fpv.input.StickSlot.LH -> "Yaw"
        dev.fpv.input.StickSlot.LV -> "Throttle"
        dev.fpv.input.StickSlot.RH -> "Roll"
        dev.fpv.input.StickSlot.RV -> "Pitch"
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(g, mouseX, mouseY, delta)
        FpvClient.input.rawAxes()
        val ax = FpvClient.input.axes()
        val bt = FpvClient.input.buttons()
        val ht = FpvClient.input.hats()

        var y = 24
        g.drawString(font, Component.translatable("mon.fpv.device", FpvClient.input.fingerprint().ifEmpty { "—" }), 8, y, 0xAAAAAA); y += 12
        g.drawString(font, Component.translatable("mon.fpv.axes", ax.size), 8, y, 0xFFFFFF); y += 10
        for (i in ax.indices) {
            val lbl = bindingLabel(0, i)
            val tag = if (lbl.isEmpty()) "" else " -> $lbl"
            g.drawString(font, Component.literal("Axis ${i + 1}: %.3f%s".format(ax[i], tag)), 12, y, 0x55FF55); y += 9
            if (y > height - 40) break
        }
        if (y < height - 40) { g.drawString(font, Component.translatable("mon.fpv.buttons", bt.size), 8, y, 0xFFFFFF); y += 10 }
        for (i in bt.indices) {
            if (y > height - 40) break
            val on = bt[i].toInt() != 0
            val lbl = bindingLabel(1, i)
            val tag = if (lbl.isEmpty()) "" else " -> $lbl"
            g.drawString(font, Component.literal("Btn ${i + 1}: ${if (on) "ON" else "off"}$tag"), 12, y, if (on) 0x55FF55 else 0x888888); y += 9
        }
        if (y < height - 40) { g.drawString(font, Component.translatable("mon.fpv.hats", ht.size), 8, y, 0xFFFFFF); y += 10 }
        for (i in ht.indices) {
            if (y > height - 40) break
            val lbl = bindingLabel(2, i)
            val tag = if (lbl.isEmpty()) "" else " -> $lbl"
            g.drawString(font, Component.literal("Hat ${i + 1}: 0x%02X%s".format(ht[i].toInt(), tag)), 12, y, 0x55FF55); y += 9
        }
        super.render(g, mouseX, mouseY, delta)
    }

    override fun onClose() = minecraft.setScreen(parent)
}
