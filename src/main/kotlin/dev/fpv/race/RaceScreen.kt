/*
 * FPV Craft - MIT
 * In-game race GUI (isPauseScreen=false). Two responsibilities:
 *   1. track authoring: add gates at the eye, select/delete gates, resize,
 *      name, save / save-as / load / delete tracks;
 *   2. live timing: start (reset + arm), reset, ghost toggle (bound to cfg).
 *
 * Every button has real behaviour; no dead widgets.
 */
package dev.fpv.race

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class RaceScreen(private val parent: Screen?) :
    Screen(Component.literal("FPV Race")) {

    private var selectedGate = 0
    private var loadCursor = 0

    private lateinit var nameBox: EditBox
    private val gateButtons = mutableListOf<Button>()

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        clearWidgets()
        gateButtons.clear()

        // ---- track name field ----
        nameBox = EditBox(font, 6, 22, 130, 18, Component.literal("name"))
        nameBox.setMaxLength(48)
        nameBox.value = RaceManager.track.name
        addRenderableWidget(nameBox)

        // ---- gate list (left) ----
        var y = 48
        gateButtons.clear()
        for (i in RaceManager.track.gates.indices) {
            val idx = i
            val b = Button.builder(Component.literal("")) { selectedGate = idx; rebuild() }
                .bounds(6, y, 130, 16).build()
            addRenderableWidget(b)
            gateButtons.add(b)
            y += 18
        }
        refreshGateLabels()

        // ---- gate editing buttons ----
        addRenderableWidget(
            Button.builder(Component.literal("+ Gate @ eye")) {
                RaceManager.addGateAtEye(minecraft)
                rebuild()
            }.bounds(6, y, 130, 18).build()
        )
        y += 20
        addRenderableWidget(
            Button.builder(Component.literal("Delete gate")) {
                RaceManager.removeGate(selectedGate)
                selectedGate = selectedGate.coerceAtMost(RaceManager.gateCount() - 1)
                rebuild()
            }.bounds(6, y, 130, 18).build()
        )
        y += 20
        addRenderableWidget(
            Button.builder(Component.literal("Width +")) {
                RaceManager.setGateWidth(selectedGate, currentGateWidth() + 0.5f); refreshGateLabels()
            }.bounds(6, y, 63, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Width -")) {
                RaceManager.setGateWidth(selectedGate, currentGateWidth() - 0.5f); refreshGateLabels()
            }.bounds(73, y, 63, 18).build()
        )
        y += 20
        addRenderableWidget(
            Button.builder(Component.literal("Height +")) {
                RaceManager.setGateHeight(selectedGate, currentGateHeight() + 0.5f); refreshGateLabels()
            }.bounds(6, y, 63, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Height -")) {
                RaceManager.setGateHeight(selectedGate, currentGateHeight() - 0.5f); refreshGateLabels()
            }.bounds(73, y, 63, 18).build()
        )

        // ---- track file operations (middle column) ----
        val mx = 150
        addRenderableWidget(
            Button.builder(Component.literal("Save")) {
                RaceManager.saveTrackAs(nameBox.value)
            }.bounds(mx, 22, 100, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Save As")) {
                RaceManager.saveTrackAs(nameBox.value)
            }.bounds(mx, 44, 100, 18).build()
        )
        loadButton = Button.builder(Component.literal("Load: -")) { cycleLoad() }
                .bounds(mx, 66, 100, 18).build()
        addRenderableWidget(loadButton!!)
        loadButton!!.message = Component.literal(loadedButtonLabel())
        addRenderableWidget(
            Button.builder(Component.literal("Delete track")) {
                RaceManager.deleteTrack(nameBox.value)
                RaceManager.newTrack(nameBox.value)
                rebuild()
            }.bounds(mx, 88, 100, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("New track")) {
                RaceManager.newTrack(nameBox.value)
                rebuild()
            }.bounds(mx, 110, 100, 18).build()
        )

        // ---- timing controls (right column) ----
        val tx = width - 130
        addRenderableWidget(
            Button.builder(Component.literal("Start / Arm")) {
                RaceManager.reset()
            }.bounds(tx, 22, 124, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Reset")) {
                RaceManager.reset()
            }.bounds(tx, 44, 124, 18).build()
        )
        ghostButton = Button.builder(Component.literal("")) {
            RaceManager.toggleGhost(); refreshGhost()
        }.bounds(tx, 66, 124, 18).build()
        addRenderableWidget(ghostButton!!)
        refreshGhost()

        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.done")) { onClose() }
                .bounds(width - 100, height - 26, 90, 18).build()
        )
    }

    private var ghostButton: Button? = null
    private var loadButton: Button? = null

    private fun rebuild() {
        clearWidgets()
        init()
    }

    private fun currentGate(): GateDef? = RaceManager.track.gates.getOrNull(selectedGate)
    private fun currentGateWidth(): Float = currentGate()?.width ?: 3f
    private fun currentGateHeight(): Float = currentGate()?.height ?: 3f

    private fun refreshGateLabels() {
        for ((i, b) in gateButtons.withIndex()) {
            val g = RaceManager.track.gates[i]
            val sel = if (i == selectedGate) ">" else " "
            b.message = Component.literal(
                "$sel G${g.index}  ${String.format("%.1fx%.1f", g.width, g.height)}"
            )
        }
    }

    private fun loadedButtonLabel(): String {
        val names = RaceManager.listTracks()
        if (names.isEmpty()) return "Load: (none)"
        loadCursor = loadCursor.coerceIn(0, names.size - 1)
        return "Load: ${names[loadCursor]}"
    }

    private fun cycleLoad() {
        val names = RaceManager.listTracks()
        if (names.isEmpty()) return
        loadCursor = (loadCursor + 1) % names.size
        RaceManager.loadTrack(names[loadCursor])
        nameBox.value = RaceManager.track.name
        rebuild()
    }

    private fun refreshGhost() {
        ghostButton?.message = Component.literal(
            if (dev.fpv.client.FpvClient.config.race?.ghostEnabled == true) "Ghost: ON"
            else "Ghost: OFF"
        )
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(g, mouseX, mouseY, delta)
        g.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF)

        // Live timing readout.
        val tx = width - 130
        var ty = 92
        g.drawString(font, "LAP  ${fmt(RaceManager.currentLapMs())}", tx, ty, 0x55FF55, true); ty += 11
        g.drawString(font, "BEST ${fmt(RaceManager.bestLapMs())}", tx, ty, 0xFFFF55, true); ty += 11
        g.drawString(font, "LAST ${fmt(RaceManager.lastLapMs())}", tx, ty, 0xAAAAAA, true); ty += 11
        g.drawString(font, "AVG  ${fmt(RaceManager.averageLapMs())}", tx, ty, 0xAAAAAA, true); ty += 11
        g.drawString(font, "Next: ${RaceManager.nextGateIndex()}/${RaceManager.gateCount()}", tx, ty, 0x55FFFF, true)

        g.drawString(font, "Track: ${RaceManager.track.name}", 150, 132, 0xAAAAAA)
        super.render(g, mouseX, mouseY, delta)
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0L) return "--:--.-"
        return "%d:%05.2f".format(ms / 60000, (ms % 60000) / 1000.0)
    }

    override fun onClose() {
        RaceManager.saveTrackAs(nameBox.value)
        minecraft.setScreen(parent)
    }
}
