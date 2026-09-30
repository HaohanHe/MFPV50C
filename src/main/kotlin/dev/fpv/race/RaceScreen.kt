/*
 * FPV Craft - MIT
 * In-game F9U race GUI (isPauseScreen=false). Left = course editor, right = live
 * race controls / readout. Every button wires to RaceManager / F9URules.
 */
package dev.fpv.race

import dev.fpv.client.FpvClient
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class RaceScreen(private val last: Screen?) : Screen(Component.literal("F9U Race")) {

    private lateinit var nameBox: EditBox
    private var selectedGate = 0
    private var templateIdx = 0
    private var toast = ""
    private var toastUntil = 0L
    /** Deferred widget rebuild so dynamic button labels (template/ghost) actually refresh. */
    private var dirty = false

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        clearWidgets()
        val lx = 8
        var y = 28

        nameBox = EditBox(font, lx, y, 130, 18, Component.literal("name")).apply {
            setMaxLength(32)
            setValue(RaceManager.track.name)
        }
        addRenderableWidget(nameBox)
        addBtn(lx + 136, y, 60, 18, "New") { RaceManager.newTrack(nameBox.value.trim().ifBlank { "untitled" }); refresh() }
        y += 22

        addBtn(lx, y, 96, 18, "Set Start@eye") { RaceManager.setStartAtEye(minecraft); toast("start set"); refresh() }
        addBtn(lx + 100, y, 96, 18, "Set Finish@eye") { RaceManager.setFinishAtEye(minecraft); toast("finish set"); refresh() }
        y += 20
        addBtn(lx, y, 130, 18, "Add Gate: ${F9URules.TEMPLATES[templateIdx].label}") {
            RaceManager.addGateAtEye(minecraft, F9URules.TEMPLATES[templateIdx])
            toast("gate #${RaceManager.track.gates.size - 1} added")
            refresh()
        }
        addBtn(lx + 134, y, 62, 18, "Cycle Tpl") {
            templateIdx = (templateIdx + 1) % F9URules.TEMPLATES.size; refresh()
        }
        y += 20
        addBtn(lx, y, 60, 18, "Sel -") { selectedGate = (selectedGate - 1).coerceAtLeast(0); refresh() }
        addBtn(lx + 62, y, 60, 18, "Sel +") { selectedGate = (selectedGate + 1).coerceAtMost(RaceManager.track.gates.size - 1); refresh() }
        addBtn(lx + 124, y, 72, 18, "Del Gate") { RaceManager.removeGate(selectedGate); refresh() }
        y += 20
        addBtn(lx, y, 44, 18, "W-0.1") { RaceManager.setGateWidth(selectedGate, (RaceManager.track.gates.getOrNull(selectedGate)?.width ?: 1.5f) - 0.1f); refresh() }
        addBtn(lx + 46, y, 44, 18, "W+0.1") { RaceManager.setGateWidth(selectedGate, (RaceManager.track.gates.getOrNull(selectedGate)?.width ?: 1.5f) + 0.1f); refresh() }
        addBtn(lx + 92, y, 44, 18, "H-0.1") { RaceManager.setGateHeight(selectedGate, (RaceManager.track.gates.getOrNull(selectedGate)?.height ?: 1.5f) - 0.1f); refresh() }
        addBtn(lx + 138, y, 58, 18, "H+0.1") { RaceManager.setGateHeight(selectedGate, (RaceManager.track.gates.getOrNull(selectedGate)?.height ?: 1.5f) + 0.1f); refresh() }
        y += 20
        addBtn(lx, y, 110, 18, "Set Timing Gate") { RaceManager.setTimingGate(selectedGate); toast("timing=$selectedGate"); refresh() }
        addBtn(lx + 114, y, 94, 18, "LandZone@eye") { RaceManager.setLandingZoneAtEye(minecraft); toast("landing set"); refresh() }
        y += 22
        addBtn(lx, y, 60, 18, "Save") {
            RaceManager.saveTrackAs(RaceManager.track.name)
            toast("saved -> ${RaceManager.trackPath()}")
        }
        addBtn(lx + 62, y, 60, 18, "Save As") {
            val ok = RaceManager.saveTrackAs(nameBox.value.trim().ifBlank { "untitled" })
            nameBox.setValue(RaceManager.track.name)
            toast(if (ok) "saved -> ${RaceManager.trackPath()}" else "save failed")
        }
        addBtn(lx + 124, y, 40, 18, "Load") { if (RaceManager.loadTrack(nameBox.value.trim())) { nameBox.setValue(RaceManager.track.name); toast("loaded"); refresh() } else toast("not found") }
        addBtn(lx + 166, y, 40, 18, "Del") { RaceManager.deleteTrack(nameBox.value.trim()); toast("deleted") }

        // ---- right column: race controls ----
        val rx = width - 150
        var ry = 28
        addBtn(rx, ry, 142, 20, "START HEAT") { RaceManager.startHeat() }.active = RaceManager.track.gates.isNotEmpty()
        ry += 22
        addBtn(rx, ry, 70, 20, "Reset") { RaceManager.reset(); refresh() }
        addBtn(rx + 72, ry, 70, 20, "Ghost: ${ghostOn()}") { RaceManager.toggleGhost(); refresh() }
        ry += 22
        addBtn(rx, ry, 70, 20, "+${penalty()}s Pen") { RaceManager.addPenalty() }
        addBtn(rx + 72, ry, 70, 20, "Turtle R") { toast(RaceManager.turtleRight()) }
        ry += 24
        addBtn(rx, ry, 142, 20, "Back") { onClose() }
    }

    private fun ghostOn() = if (FpvClient.config.race?.ghostEnabled == true) "ON" else "OFF"
    private fun penalty() = FpvClient.config.race?.penaltySec ?: 30

    private fun addBtn(x: Int, y: Int, w: Int, h: Int, label: String, onPress: () -> Unit): Button {
        val b = Button.builder(Component.literal(label), { onPress() }).bounds(x, y, w, h).build()
        addRenderableWidget(b)
        return b
    }

    private fun toast(msg: String) { toast = msg; toastUntil = System.currentTimeMillis() + 1800L }
    /** Mark the widget tree dirty; rebuild happens at the top of the next render
     *  frame (safe to call from inside a button press). This is what makes the
     *  "Cycle Tpl", "Ghost ON/OFF" and "START HEAT" labels/activeness refresh. */
    private fun refresh() { dirty = true }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // Rebuild buttons on demand so dynamic labels/activeness refresh.
        if (dirty) { dirty = false; clearWidgets(); init() }
        // Background already drawn by renderWithTooltipAndSubtitles.
        super.render(g, mouseX, mouseY, delta)
        val font = this.font
        var y = 6
        g.drawString(font, "F9U RACE EDITOR  (1m ~= 1 block)", 8, y, 0xFFFFFFFF.toInt(), true)
        y += 12

        // Gate list (below the left editor buttons, which end ~y=170).
        val gates = RaceManager.track.gates
        g.drawString(font, "Gates (${gates.size}): timing=${RaceManager.track.timingGateIndex}", 8, 178, 0xFFAAAAAA.toInt(), true)
        var gy = 190
        gates.forEachIndexed { i, gt ->
            val sel = if (i == selectedGate) "> " else "  "
            g.drawString(font, "$sel#$i ${gt.gateShape()} ${String.format("%.1fx%.1f", gt.width, gt.height)}", 10, gy,
                if (i == selectedGate) 0xFFFFFF55.toInt() else 0xFFFFFFFF.toInt(), false)
            gy += 11
            if (gy > height - 10) return@forEachIndexed
        }

        // Race readout (right column).
        val rx = width - 150
        var ry = 90
        g.drawString(font, "Phase: ${RaceManager.phase}", rx, ry, 0xFFFFFFFF.toInt(), true); ry += 11
        g.drawString(font, "Lap ${RaceManager.lapsCompleted()}/${RaceManager.lapsRemaining()} left", rx, ry, 0xFF55FF55.toInt(), true); ry += 11
        g.drawString(font, "Time ${RaceManager.elapsedMs() / 1000.0}s", rx, ry, 0xFF55FF55.toInt(), true); ry += 11
        g.drawString(font, "Left ${RaceManager.remainingTimeMs() / 1000.0}s", rx, ry, 0xFFFFFF55.toInt(), true); ry += 11
        g.drawString(font, "Pen +${RaceManager.penaltyMs() / 1000.0}s", rx, ry, 0xFFFF5555.toInt(), true); ry += 11
        g.drawString(font, RaceManager.rankingSummary(), rx, ry, 0xFF55FFFF.toInt(), true); ry += 11
        if (RaceManager.isDnf()) g.drawString(font, "DNF", rx, ry, 0xFFFF3333.toInt(), true)

        if (System.currentTimeMillis() < toastUntil)
            g.drawCenteredString(font, toast, width / 2, height - 12, 0xFFFFFFFF.toInt())
    }

    override fun onClose() { minecraft.setScreen(last) }
}
