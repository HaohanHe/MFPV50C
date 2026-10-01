/*
 * FPV Craft - MIT
 * Persisted OSD layout record + default layout. The canonical element specs live
 * in [dev.fpv.flight.OsdElements.REGISTRY]; this object only maps those specs to
 * the persisted per-user records (id -> enabled / position / unit override).
 *
 * The live layout lives in [dev.fpv.flight.FpvConfig.osdElements].
 */
package dev.fpv.client.osd

import dev.fpv.flight.OsdElements

/**
 * One OSD element's persisted layout record.
 *
 * @param id             stable identifier, also the registry key
 * @param centerAnchored true => x/y are ignored and the element centers on screen
 * @param unitOverride   null => follow the global unit system; else "METRIC"/"IMPERIAL"
 */
data class OsdElement(
    val id: String,
    var enabled: Boolean,
    var x: Int,
    var y: Int,
    val centerAnchored: Boolean = false,
    var unitOverride: String? = null,
)

object OsdLayout {

    /** Canonical ids, re-exported from the registry so existing imports keep working. */
    const val CROSSHAIR = OsdElements.CROSSHAIR
    const val ARTIFICIAL_HORIZON = OsdElements.ARTIFICIAL_HORIZON
    const val HORIZON_SIDEBARS = OsdElements.HORIZON_SIDEBARS
    const val SPEED = OsdElements.SPEED
    const val THROTTLE = OsdElements.THROTTLE
    const val MODE = OsdElements.MODE
    const val TARGET = OsdElements.TARGET
    const val BATTERY = OsdElements.BATTERY
    const val LQ = OsdElements.LQ
    const val FLIGHT_TIMER = OsdElements.FLIGHT_TIMER
    const val ATTITUDE = OsdElements.ATTITUDE
    const val CURRENT = OsdElements.CURRENT
    const val MAH_DRAWN = OsdElements.MAH_DRAWN
    const val CENTER_WARNING = OsdElements.CENTER_WARNING

    /** Default layout, derived from the registry (single source of truth). */
    fun defaultLayout(): MutableList<OsdElement> =
        OsdElements.REGISTRY.map { spec ->
            OsdElement(spec.id, spec.defaultEnabled, spec.defaultX, spec.defaultY, spec.centerAnchored)
        }.toMutableList()
}
