/*
 * FPV Craft - MIT
 * Minimal FPV OSD: speed, throttle, mode banner, roll-referenced horizon line,
 * and self-level target inclination.
 */
package dev.fpv.client.osd

import dev.fpv.client.FpvClient
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.atan2

object FpvOsd {

    private const val GREEN = 0x55FF55
    private const val GREEN_FILL = 0xFF55FF55.toInt()
    private const val YELLOW_FILL = 0xFFFFFF55.toInt()
    private const val CYAN = 0x55FFFF

    fun draw(ctx: GuiGraphics) {
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        val font = mc.font
        val sw = mc.window.guiScaledWidth
        val sh = mc.window.guiScaledHeight
        val cx = sw / 2
        val cy = sh / 2
        val flight = FpvClient.flight
        val cfg = FpvClient.config

        // Speed (player velocity is blocks/tick; *20 = m/s; *3.6 = km/h).
        val kmh = p.deltaMovement.length() * 20.0 * 3.6
        ctx.drawString(font, String.format("%.0f km/h", kmh), 8, 8, GREEN, true)

        // Throttle (signed in reversible-3D).
        val thr = FpvClient.throttle
        val thrStr = if (cfg.reversible3D) {
            String.format("THR %+3d%%", (thr * 100).toInt())
        } else {
            String.format("THR %3d%%", (thr * 100).toInt())
        }
        ctx.drawString(font, thrStr, 8, sh - 20, GREEN, true)

        // Mode banner: ACRO / ANGLE / HORIZON, plus a 3D tag.
        val modeTag = flight.currentMode.name + if (cfg.reversible3D) " 3D" else ""
        ctx.drawCenteredString(font, "FPV $modeTag", cx, 8, GREEN)

        // Self-level target inclination (Angle/Horizon only).
        if (flight.currentMode != dev.fpv.flight.FlightMode.ACRO) {
            ctx.drawString(
                font,
                String.format("TGT P %+4.0f R %+4.0f", flight.targetPitchDeg, flight.targetRollDeg),
                8, 20, CYAN, true,
            )
        }

        // Roll-referenced horizon line.
        val inv = Quaternionf(flight.attitude).conjugate()
        val bodyUp = Vector3f(0f, 1f, 0f).rotate(inv)
        val roll = atan2(-bodyUp.x, bodyUp.y)

        val pose = ctx.pose()
        pose.pushMatrix()
        pose.translate(cx.toFloat(), cy.toFloat())
        pose.rotate(roll)
        ctx.fill(-30, -1, 30, 1, GREEN_FILL)
        ctx.fill(-36, -3, -30, 3, GREEN_FILL)
        ctx.fill(30, -3, 36, 3, GREEN_FILL)
        pose.popMatrix()

        // Fixed center dot.
        ctx.fill(cx - 1, cy - 1, cx + 1, cy + 1, YELLOW_FILL)
    }
}
