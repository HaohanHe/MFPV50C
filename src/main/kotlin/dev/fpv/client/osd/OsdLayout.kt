/*
 * FPV Craft - MIT
 * OSD element registry: data-driven layout. Each element has an id, an enabled
 * flag and a position in guiScaled pixels. Only the elements that are anchored
 * to the screen center (crosshair, artificial horizon, horizon sidebars) ignore
 * their stored x/y and are always drawn centered.
 *
 * A future drag-to-position editor can call [OsdLayout.setPosition] by id; no
 * editor UI ships in this phase.
 */
package dev.fpv.client.osd

/**
 * One OSD element's layout record.
 *
 * @param id             stable identifier, also the translation key suffix
 * @param centerAnchored true => x/y are ignored and the element centers on screen
 */
data class OsdElement(
    val id: String,
    var enabled: Boolean,
    var x: Int,
    var y: Int,
    val centerAnchored: Boolean = false,
)

object OsdLayout {

    // Stable element ids.
    const val CROSSHAIR = "crosshair"
    const val ARTIFICIAL_HORIZON = "artificial_horizon"
    const val HORIZON_SIDEBARS = "horizon_sidebars"
    const val SPEED = "speed"
    const val THROTTLE = "throttle"
    const val MODE = "mode"
    const val TARGET = "target"
    const val BATTERY = "battery"
    const val LQ = "lq"
    const val FLIGHT_TIMER = "flight_timer"
    const val CENTER_WARNING = "center_warning" // transient, center-drawn, not movable

    /** Default layout; positions are guiScaled pixels. */
    fun defaultLayout(): MutableList<OsdElement> = mutableListOf(
        OsdElement(CROSSHAIR, true, 0, 0, centerAnchored = true),
        OsdElement(ARTIFICIAL_HORIZON, true, 0, 0, centerAnchored = true),
        OsdElement(HORIZON_SIDEBARS, true, 0, 0, centerAnchored = true),
        OsdElement(SPEED, true, 8, 8),
        OsdElement(MODE, true, 0, 8, centerAnchored = true),
        OsdElement(TARGET, true, 8, 20),
        OsdElement(THROTTLE, true, 8, 0), // y anchored near bottom at draw time
        OsdElement(BATTERY, true, 8, 32),
        OsdElement(LQ, true, 8, 44),
        OsdElement(FLIGHT_TIMER, true, 8, 56),
        OsdElement(CENTER_WARNING, true, 0, 0, centerAnchored = true),
    )

    val elements: MutableList<OsdElement> = defaultLayout()

    fun byId(id: String): OsdElement? = elements.firstOrNull { it.id == id }

    fun enabled(id: String): Boolean = byId(id)?.enabled == true

    /** Move an element by id (reserved for the future drag editor). No-op for
     *  center-anchored elements. */
    fun setPosition(id: String, x: Int, y: Int) {
        val e = byId(id) ?: return
        if (e.centerAnchored) return
        e.x = x
        e.y = y
    }
}
