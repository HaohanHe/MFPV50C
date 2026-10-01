/*
 * FPV Craft - MIT
 * Optional on-frame RC overlay: turns the recorded normalized RC channels into
 * joystick positions drawn in a corner of the exported frame. Pure mapping -- no
 * Minecraft types -- so it is headless-testable. Off by default (ExportConfig).
 */
package dev.fpv.replay

object StickOverlay {

    /**
     * Map a recorded normalized RC channel (already -1..1, 0 centered) to an overlay
     * coordinate in [0,1] within the joystick box. Roll moves the stick horizontally,
     * pitch vertically. Returns (cx, cy) each in 0..1 with 0.5 = centered.
     */
    fun stickPos(rcRoll: Float, rcPitch: Float): Pair<Float, Float> {
        val cx = (0.5f + 0.5f * rcRoll.coerceIn(-1f, 1f))
        // pitch up (negative pitch demand) = stick at top of the box (small screen y).
        val cy = (0.5f + 0.5f * rcPitch.coerceIn(-1f, 1f))
        return cx.coerceIn(0f, 1f) to cy.coerceIn(0f, 1f)
    }

    /** Throttle as a 0..1 fill level (3D signed input maps to -1..1 -> 0..1). */
    fun throttleFill(rcThrottle: Float): Float =
        (0.5f + 0.5f * rcThrottle.coerceIn(-1f, 1f)).coerceIn(0f, 1f)
}
