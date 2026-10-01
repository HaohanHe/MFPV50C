/*
 * FPV Craft - MIT
 * Live, drag-to-position OSD layout editor (clean-room: built only on vanilla
 * MC screens / widgets / GuiGraphics primitives; no third-party UI library,
 * no third-party textures or trademarks).
 *
 * Data model: edits are made directly on [dev.fpv.flight.FpvConfig.osdElements]
 * (a persisted MutableList<OsdElement>). The list opened with is the source of
 * truth; "Done" calls cfg.save() and returns to the parent screen.
 *
 * Preview: renderBackground is overridden to draw no dark/menu overlay, so the
 * live world frame stays visible behind the editor. Each element is redrawn in
 * place with a small, self-contained stand-in that mirrors FpvOsd's layout and
 * colours (simplified on purpose so this screen has zero dependency on FpvOsd).
 * Center-anchored elements (crosshair / horizon / sidebars / mode banner /
 * center warning) can be selected and toggled on/off, but cannot be moved.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.client.osd.OsdElement
import dev.fpv.client.osd.OsdLayout
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class OsdEditorScreen(private val parent: Screen?) :
    Screen(Component.literal("OSD Layout Editor")) {

    private val cfg get() = FpvClient.config

    /** Live working list: the persisted layout. Mutated in place; saved on Done. */
    private val elements: MutableList<OsdElement> get() = cfg.osdElements

    /** Currently selected element id (any element; movable ones can also move). */
    private var selectedId: String? = null

    /** Movable element currently being dragged, and the cursor offset inside it. */
    private var dragging: OsdElement? = null
    private var grabDX = 0
    private var grabDY = 0

    /** Add-popup open state. */
    private var addMenuOpen = false

    private lateinit var deleteBtn: Button
    private lateinit var enableBtn: Button

    // ---- Screen lifecycle ----

    override fun isPauseScreen(): Boolean = false

    /**
     * Intentionally empty: we do NOT call super, so neither the blurred menu
     * background nor the dark in-world scrim is drawn. The game world stays
     * fully visible behind the OSD preview.
     */
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // no-op: keep the live world un-obscured
    }

    override fun init() {
        clearWidgets()
        val barY = height - 24

        addRenderableWidget(
            Button.builder(Component.literal("Reset Default")) {
                cfg.osdElements = OsdLayout.defaultLayout()
                selectedId = null
                addMenuOpen = false
                rebuild()
            }.bounds(8, barY, 80, 20).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Add \u25BC")) {
                addMenuOpen = !addMenuOpen
                rebuild()
            }.bounds(96, barY, 70, 20).build()
        )

        deleteBtn = Button.builder(Component.literal("Delete")) {
            doDelete()
        }.bounds(174, barY, 70, 20).build()
        addRenderableWidget(deleteBtn)

        enableBtn = Button.builder(Component.literal("")) {
            toggleEnabled()
        }.bounds(252, barY, 160, 20).build()
        addRenderableWidget(enableBtn)

        addRenderableWidget(
            Button.builder(Component.literal("Done")) {
                cfg.save()
                minecraft.setScreen(parent)
            }.bounds(width - 98, barY, 90, 20).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal(if (cfg.osdUnit == "IMPERIAL") "Units: IMP" else "Units: MET")) {
                cfg.osdUnit = if (cfg.osdUnit == "IMPERIAL") "METRIC" else "IMPERIAL"
                rebuild()
            }.bounds(width - 200, barY, 94, 20).build()
        )

        // Add-popup: one button per movable element not currently in the layout.
        if (addMenuOpen) {
            var ay = barY - 6
            for (id in missingMovableIds()) {
                val idFinal = id
                ay -= 20
                addRenderableWidget(
                    Button.builder(Component.literal("+ " + labelOf(idFinal))) {
                        addMissing(idFinal)
                        addMenuOpen = false
                        rebuild()
                    }.bounds(8, ay, 150, 18).build()
                )
            }
        }

        refreshButtons()
    }

    private fun rebuild() = init()

    // ---- Layout helpers ----

    /** Movable (= not center-anchored) element ids from the canonical default. */
    private fun movableIds(): List<String> =
        OsdLayout.defaultLayout().filter { !it.centerAnchored }.map { it.id }

    private fun missingMovableIds(): List<String> {
        val present = elements.map { it.id }.toSet()
        return movableIds().filter { it !in present }
    }

    private fun templateFor(id: String): OsdElement? =
        OsdLayout.defaultLayout().firstOrNull { it.id == id }

    private fun addMissing(id: String) {
        val t = templateFor(id) ?: return
        elements.add(t.copy(enabled = true))
        selectedId = id
    }

    private fun doDelete() {
        val sel = selectedId ?: return
        val e = elements.firstOrNull { it.id == sel && !it.centerAnchored } ?: return
        elements.remove(e)
        selectedId = null
        rebuild()
    }

    private fun toggleEnabled() {
        val sel = selectedId ?: return
        val e = elements.firstOrNull { it.id == sel } ?: return
        e.enabled = !e.enabled
        refreshButtons()
    }

    private fun selected(): OsdElement? =
        elements.firstOrNull { it.id == selectedId }

    private fun refreshButtons() {
        val sel = selected()
        deleteBtn.active = sel != null && !sel.centerAnchored
        enableBtn.message = if (sel == null) {
            Component.literal("Enable: <select an element>")
        } else {
            Component.literal("${labelOf(sel.id)}: " + if (sel.enabled) "ON" else "OFF")
        }
    }

    // ---- Stand-in content ----

    private fun previewText(id: String): String = when (id) {
        OsdLayout.SPEED -> "52 km/h"
        OsdLayout.TARGET -> "TGT P +12 R -3"
        OsdLayout.BATTERY -> "BAT 3.8V (3.80/c) 82%"
        OsdLayout.LQ -> "LQ(v) 96%"
        OsdLayout.FLIGHT_TIMER -> "T+0:42"
        OsdLayout.THROTTLE -> "THR 45%"
        OsdLayout.MODE -> "FPV ACRO"
        OsdLayout.ATTITUDE -> "P +12 R -3"
        OsdLayout.CURRENT -> "CUR 24.0A"
        OsdLayout.MAH_DRAWN -> "MAH 320"
        OsdLayout.CENTER_WARNING -> "(warn)"
        else -> ""
    }

    private fun labelOf(id: String): String =
        dev.fpv.flight.OsdElements.byId(id)?.labelEn ?: id

    private fun colorFor(id: String): Int = when (id) {
        OsdLayout.SPEED, OsdLayout.THROTTLE, OsdLayout.BATTERY, OsdLayout.LQ -> 0xFF55FF55.toInt()
        OsdLayout.TARGET -> 0xFF55FFFF.toInt()
        OsdLayout.FLIGHT_TIMER -> 0xFFAAAAAA.toInt()
        OsdLayout.MODE -> 0xFF55FF55.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    // ---- Hit / highlight geometry (guiScaled pixels) ----

    /** Bounding box used for both click hit-testing and the selection outline. */
    private fun box(e: OsdElement): IntArray {
        val cx = width / 2
        val cy = height / 2
        return when {
            e.centerAnchored && e.id == OsdLayout.MODE -> {
                val w = font.width(previewText(e.id))
                intArrayOf(cx - w / 2, 8, cx + w / 2, 18)
            }
            e.centerAnchored && e.id == OsdLayout.CROSSHAIR ->
                intArrayOf(cx - 4, cy - 4, cx + 4, cy + 4)
            e.centerAnchored && e.id == OsdLayout.ARTIFICIAL_HORIZON ->
                intArrayOf(cx - 31, cy - 2, cx + 31, cy + 2)
            e.centerAnchored && e.id == OsdLayout.HORIZON_SIDEBARS ->
                intArrayOf(cx - 37, cy - 4, cx + 37, cy + 4)
            e.centerAnchored && e.id == OsdLayout.CENTER_WARNING -> {
                val w = font.width(previewText(e.id))
                intArrayOf(cx - w / 2, cy + 14, cx + w / 2, cy + 24)
            }
            else -> {
                val w = font.width(previewText(e.id))
                intArrayOf(e.x, e.y, e.x + w, e.y + 10)
            }
        }
    }

    private fun hit(mx: Double, my: Double, b: IntArray): Boolean =
        mx >= b[0] - 2 && mx <= b[2] + 2 && my >= b[1] - 2 && my <= b[3] + 2

    // ---- Input ----

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        // Let native widgets (buttons / add-popup) consume first.
        if (super.mouseClicked(event, doubled)) return true
        if (event.button() != 0) return false

        val mx = event.x()
        val my = event.y()

        // Topmost element under the cursor wins (iterate back-to-front).
        val hitEl = elements.lastOrNull { hit(mx, my, box(it)) }
        if (hitEl != null) {
            selectedId = hitEl.id
            // Only non-anchored elements can be dragged.
            if (!hitEl.centerAnchored) {
                dragging = hitEl
                grabDX = (mx - hitEl.x).toInt()
                grabDY = (my - hitEl.y).toInt()
            }
            refreshButtons()
            return true
        }

        // Clicked empty space: deselect; close the add popup if it was open.
        selectedId = null
        if (addMenuOpen) {
            addMenuOpen = false
            rebuild()
        } else {
            refreshButtons()
        }
        return false
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val e = dragging ?: return super.mouseDragged(event, dragX, dragY)
        val b = box(e)
        val bw = b[2] - b[0]
        val bh = b[3] - b[1]
        // Anchor the grab point under the cursor; clamp inside the OSD area
        // (above the bottom control bar).
        val nx = (event.x() - grabDX).toInt()
        val ny = (event.y() - grabDY).toInt()
        e.x = nx.coerceIn(0, (width - bw).coerceAtLeast(0))
        e.y = ny.coerceIn(0, (height - BOTTOM_BAR - bh).coerceAtLeast(0))
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val sel = selected()?.takeIf { !it.centerAnchored }
        if (sel != null) {
            val step = if (event.hasShiftDown()) 10 else 1
            val b = box(sel)
            val bw = b[2] - b[0]
            val bh = b[3] - b[1]
            when {
                event.isLeft() -> { sel.x = (sel.x - step).coerceAtLeast(0); return true }
                event.isRight() -> { sel.x = (sel.x + step).coerceAtMost(width - bw); return true }
                event.isUp() -> { sel.y = (sel.y - step).coerceAtLeast(0); return true }
                event.isDown() -> { sel.y = (sel.y + step).coerceAtMost(height - BOTTOM_BAR - bh); return true }
            }
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        // Esc cancels without saving; "Done" is the explicit save path.
        minecraft.setScreen(parent)
    }

    // ---- Rendering ----

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val cx = width / 2
        val cy = height / 2

        // Center-anchored first, then movable elements.
        for (e in elements) if (e.centerAnchored) drawAnchored(g, e, cx, cy)
        for (e in elements) if (!e.centerAnchored) drawMovable(g, e)

        // Selection highlight around the chosen element (any kind).
        selected()?.let { drawHighlight(g, it) }

        // Hint + live coordinate readout.
        g.drawCenteredString(
            font,
            Component.literal("Click to select \u00B7 drag to move \u00B7 arrows nudge (Shift=10px)"),
            cx, 4, 0xFFFFFFFF.toInt(),
        )
        selected()?.let { s ->
            g.drawString(font, "x=${s.x} y=${s.y}", 8, height - BOTTOM_BAR - 12, 0xFFAAAAAA.toInt())
        }

        // Translucent strip behind the control buttons only.
        g.fill(0, height - BOTTOM_BAR, width, height, 0x90000000.toInt())

        // Native widgets (buttons) on top of the preview.
        super.render(g, mouseX, mouseY, delta)
    }

    private fun drawMovable(g: GuiGraphics, e: OsdElement) {
        val col = if (e.enabled) colorFor(e.id) else DISABLED
        g.drawString(font, previewText(e.id), e.x, e.y, col, true)
    }

    private fun drawAnchored(g: GuiGraphics, e: OsdElement, cx: Int, cy: Int) {
        if (!e.enabled) {
            // Disabled anchored elements: a dim placeholder so they remain visible
            // AND clickable (the selection outline can re-enable them).
            when (e.id) {
                OsdLayout.MODE -> g.drawCenteredString(font, "FPV ACRO", cx, 8, DISABLED)
                OsdLayout.CENTER_WARNING -> g.drawCenteredString(font, "(warn)", cx, cy + 14, DISABLED)
                OsdLayout.CROSSHAIR -> g.fill(cx - 1, cy - 1, cx + 1, cy + 1, DISABLED)
                OsdLayout.ARTIFICIAL_HORIZON ->
                    g.fill(cx - 30, cy - 1, cx + 30, cy + 1, DISABLED)
                OsdLayout.HORIZON_SIDEBARS -> {
                    g.fill(cx - 36, cy - 3, cx - 30, cy + 3, DISABLED)
                    g.fill(cx + 30, cy - 3, cx + 36, cy + 3, DISABLED)
                }
            }
            return
        }
        when (e.id) {
            OsdLayout.CROSSHAIR ->
                g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFF55.toInt())
            OsdLayout.ARTIFICIAL_HORIZON ->
                g.fill(cx - 30, cy - 1, cx + 30, cy + 1, 0xFF55FF55.toInt())
            OsdLayout.HORIZON_SIDEBARS -> {
                g.fill(cx - 36, cy - 3, cx - 30, cy + 3, 0xFF55FF55.toInt())
                g.fill(cx + 30, cy - 3, cx + 36, cy + 3, 0xFF55FF55.toInt())
            }
            OsdLayout.MODE ->
                g.drawCenteredString(font, "FPV ACRO", cx, 8, 0xFF55FF55.toInt())
            OsdLayout.CENTER_WARNING ->
                g.drawCenteredString(font, "(warn)", cx, cy + 14, 0xFFFFFF55.toInt())
        }
    }

    private fun drawHighlight(g: GuiGraphics, e: OsdElement) {
        val b = box(e)
        val c = 0xFFFFFF55.toInt()
        g.fill(b[0] - 1, b[1] - 1, b[2] + 1, b[1], c)        // top edge
        g.fill(b[0] - 1, b[3], b[2] + 1, b[3] + 1, c)        // bottom edge
        g.fill(b[0] - 1, b[1], b[0], b[3], c)                // left edge
        g.fill(b[2], b[1], b[2] + 1, b[3], c)                // right edge
    }

    companion object {
        private const val BOTTOM_BAR = 30
        private const val DISABLED = 0xFF606060.toInt()
    }
}
