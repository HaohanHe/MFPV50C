/*
 * FPV Craft - MIT
 * FPV OSD. Element positions/enabled flags come from the PERSISTED
 * [dev.fpv.flight.FpvConfig.osdElements] list (edited by OsdEditorScreen);
 * only the crosshair, artificial horizon and horizon sidebars are center-anchored.
 * Adds a virtual battery readout, virtual link quality (explicitly marked as
 * simulated), flight timer and a central transient warning (low battery / RX
 * loss / failsafe) on top of the existing speed / throttle / mode / horizon.
 */
package dev.fpv.client.osd

import dev.fpv.client.FpvClient
import dev.fpv.flight.BatteryStage
import dev.fpv.flight.Defaults
import dev.fpv.flight.LinkState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.asin
import kotlin.math.atan2

object FpvOsd {

    private const val GREEN = 0xFF55FF55.toInt()
    private const val GREEN_FILL = 0xFF55FF55.toInt()
    private const val YELLOW = 0xFFFFFF55.toInt()
    private const val YELLOW_FILL = 0xFFFFFF55.toInt()
    private const val RED = 0xFFFF5555.toInt()
    private const val RED_FILL = 0xFFFF5555.toInt()
    private const val CYAN = 0xFF55FFFF.toInt()
    private const val GRAY = 0xFFAAAAAA.toInt()

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

        /** Look up a persisted layout element by id. */
        fun el(id: String): OsdElement? = cfg.osdElements.firstOrNull { it.id == id }

        // ---- Recording / replay status (always visible when active) ----
        if (dev.fpv.replay.FlightRecorder.recording) {
            val f = dev.fpv.replay.FlightRecorder
            val blink = (System.currentTimeMillis() / 500) % 2 == 0L
            val mark = if (blink) "\u25CF" else "\u25CB"
            ctx.drawString(
                font,
                "$mark REC  T+${formatSec(f.recordedSec)}  n=${f.sampleCount}",                4, sh - 12, 0xFFFF3333.toInt(), true,
            )
        }
        if (dev.fpv.replay.ReplayManager.active) {
            val rm = dev.fpv.replay.ReplayManager
            ctx.drawString(
                font,
                "REPLAY x${String.format("%.2f", rm.speed)} ${rm.view}  " +
                    rm.formatTime(rm.cursorSec),
                sw - 150, 4, 0xFFFFFF55.toInt(), true,
            )
        }

        // Guidance while the player is trying to fly but cannot yet. List ALL
        // arming-check blockers (Betaflight-style) rather than a single message,
        // so the pilot is never silently refused an unlock without a reason.
        if (p.isFallFlying && !flight.ready) {
            val codes = FpvClient.armingBlockers
            val texts = FpvClient.armingBlockerText()
            if (texts.isEmpty()) {
                ctx.drawCenteredString(font, "准备中…", cx, 8, GRAY)
            } else {
                texts.forEachIndexed { i, r ->
                    val col = when (codes[i]) {
                        "NO_RX" -> GRAY
                        "BATTERY_CRITICAL", "RX_FAILSAFE" -> RED_FILL
                        else -> YELLOW_FILL
                    }
                    ctx.drawCenteredString(font, r, cx, 8 + i * 10, col)
                }
            }
            return
        }

        if (!flight.ready) return

        // ---- Top-left cluster (positions from the persisted layout) ----
        if (el(OsdLayout.SPEED)?.enabled == true) {
            val e = el(OsdLayout.SPEED)!!
            val kmh = p.deltaMovement.length() * 20.0 * 3.6
            ctx.drawString(font, String.format("%.0f km/h", kmh), e.x, e.y, GREEN, true)
        }

        if (el(OsdLayout.TARGET)?.enabled == true && flight.currentMode != dev.fpv.flight.FlightMode.ACRO) {
            val e = el(OsdLayout.TARGET)!!
            ctx.drawString(
                font,
                String.format("TGT P %+4.0f R %+4.0f", flight.targetPitchDeg, flight.targetRollDeg),
                e.x, e.y, CYAN, true,
            )
        }

        // Battery: total / per-cell / percent.
        if (el(OsdLayout.BATTERY)?.enabled == true) {
            val e = el(OsdLayout.BATTERY)!!
            val b = FpvClient.battery
            val col = when (b.stage) {
                BatteryStage.CRITICAL -> RED
                BatteryStage.WARNING -> YELLOW
                else -> GREEN
            }
            ctx.drawString(
                font,
                String.format("BAT %.1fV (%.2f/c) %3.0f%%", b.vbat, b.perCell(), b.percent()),
                e.x, e.y, col, true,
            )
        }

        // Virtual link quality - explicitly labelled as simulated.
        if (el(OsdLayout.LQ)?.enabled == true) {
            val e = el(OsdLayout.LQ)!!
            val lq = FpvClient.link.lq
            val col = if (lq < 50) RED else if (lq < 80) YELLOW else GREEN
            ctx.drawString(font, "LQ(v) $lq%", e.x, e.y, col, true)
        }

        // Flight timer.
        if (el(OsdLayout.FLIGHT_TIMER)?.enabled == true) {
            val e = el(OsdLayout.FLIGHT_TIMER)!!
            val s = FpvClient.flightTimeSec.toInt()
            ctx.drawString(font, String.format("T+%d:%02d", s / 60, s % 60), e.x, e.y, GRAY, true)
        }

        // Throttle drawn at its persisted x/y (the editor fully controls it;
        // no hidden bottom-anchor override anymore).
        if (el(OsdLayout.THROTTLE)?.enabled == true) {
            val e = el(OsdLayout.THROTTLE)!!
            val thr = FpvClient.throttle
            val thrStr = if (cfg.reversible3D) {
                String.format("THR %+3d%%", (thr * 100).toInt())
            } else {
                String.format("THR %3d%%", (thr * 100).toInt())
            }
            ctx.drawString(font, thrStr, e.x, e.y, GREEN, true)
        }

        // Attitude degrees (pitch/roll; BF OSD_PITCH_ANGLE / OSD_ROLL_ANGLE).
        if (el(OsdLayout.ATTITUDE)?.enabled == true) {
            val e = el(OsdLayout.ATTITUDE)!!
            val iv = Quaternionf(flight.attitude).conjugate()
            val bu = Vector3f(0f, 1f, 0f).rotate(iv)
            val bf = Vector3f(0f, 0f, -1f).rotate(iv)
            val rDeg = Math.toDegrees(atan2(-bu.x, bu.y).toDouble())
            val pDeg = Math.toDegrees(asin((-bf.y).coerceIn(-1f, 1f).toDouble()))
            ctx.drawString(
                font, String.format("P %+3.0f R %+3.0f", pDeg, rDeg), e.x, e.y, CYAN, true,
            )
        }

        // Pack current (BF OSD_CURRENT).
        if (el(OsdLayout.CURRENT)?.enabled == true) {
            val e = el(OsdLayout.CURRENT)!!
            ctx.drawString(
                font, String.format("CUR %4.1fA", FpvClient.battery.currentA), e.x, e.y, GREEN, true,
            )
        }

        // Consumed charge (BF OSD_MAH_DRAWN).
        if (el(OsdLayout.MAH_DRAWN)?.enabled == true) {
            val e = el(OsdLayout.MAH_DRAWN)!!
            ctx.drawString(
                font, String.format("MAH %4.0f", FpvClient.battery.mAhDrawn), e.x, e.y, GREEN, true,
            )
        }

        // Mode banner (centered).
        if (el(OsdLayout.MODE)?.enabled == true) {
            val modeTag = buildString {
                append(flight.currentMode.name)
                if (cfg.reversible3D) append(" 3D")
                if (cfg.headfreeEnabled) append(" HF")
            }
            // Persistent ARM/DISARM marker alongside the flight mode.
            val armTag = if (FpvClient.armed) " ARM" else " DISARM"
            ctx.drawCenteredString(
                font, "FPV $modeTag$armTag", cx, 8,
                if (FpvClient.armed) GREEN else RED,
            )
        }

        // ---- Center-anchored: artificial horizon + pitch ladder + sidebars ----
        val inv = Quaternionf(flight.attitude).conjugate()
        val bodyUp = Vector3f(0f, 1f, 0f).rotate(inv)
        val bodyFwd = Vector3f(0f, 0f, -1f).rotate(inv)
        // Sign matches the camera so the OSD line overlays the true horizon as
        // seen in the already-rolled FPV picture (verified: roll-right 20 ->
        // true horizon -20 on screen, old sign drew +20).
        val roll = atan2(bodyUp.x, bodyUp.y)
        // Current pitch relative to level (positive = nose down), from the same
        // body/error convention used by the flight controller.
        val currentPitchDeg = Math.toDegrees(
            asin((-bodyFwd.y).coerceIn(-1f, 1f).toDouble())
        ).toFloat()
        // Synthetic-instrument vertical shift: nose-down pitches the view down,
        // so the level horizon rises (negative GUI y). Verified by projecting the
        // true horizon (screen y = -tan(pitch)); sign fixed in the cloud.
        val groupDy = -currentPitchDeg * Defaults.OSD_PITCH_PX_PER_DEG

        val showHorizon = el(OsdLayout.ARTIFICIAL_HORIZON)?.enabled == true
        val showSidebars = el(OsdLayout.HORIZON_SIDEBARS)?.enabled == true
        if (showHorizon || showSidebars) {
            val pose = ctx.pose()
            pose.pushMatrix()
            pose.translate(cx.toFloat(), cy.toFloat() + groupDy)
            pose.rotate(roll)
            if (showHorizon) {
                // Level reference line.
                ctx.fill(-30, -1, 30, 1, GREEN_FILL)
                // Pitch ladder: ticks at fixed degree references (both signs),
                // positioned relative to the shifted group; hidden past range.
                for (absL in Defaults.OSD_PITCH_LADDER_DEG) {
                    for (signedL in intArrayOf(absL, -absL)) {
                        val ly = signedL * Defaults.OSD_PITCH_PX_PER_DEG
                        if (ly < -Defaults.OSD_PITCH_LADDER_RANGE_PX ||
                            ly > Defaults.OSD_PITCH_LADDER_RANGE_PX
                        ) continue
                        val iy = ly.toInt()
                        val h = Defaults.OSD_PITCH_TICK_HALF
                        ctx.fill(-h, iy, h, iy + 1, GREEN_FILL)
                        // Degree label to the right of the tick (rotates with roll).
                        ctx.drawString(
                            font, signedL.toString(), h + 2, iy - 3, GREEN, false,
                        )
                    }
                }
            }
            if (showSidebars) {
                ctx.fill(-36, -3, -30, 3, GREEN_FILL)
                ctx.fill(30, -3, 36, 3, GREEN_FILL)
            }
            pose.popMatrix()
        }

        if (el(OsdLayout.CROSSHAIR)?.enabled == true) {
            ctx.fill(cx - 1, cy - 1, cx + 1, cy + 1, YELLOW_FILL)
        }

        // ---- Central transient warnings ----
        if (el(OsdLayout.CENTER_WARNING)?.enabled == true) {
            val b = FpvClient.battery
            val link = FpvClient.link
            when {
                b.stage == BatteryStage.CRITICAL ->
                    ctx.drawCenteredString(font, "LOW BATTERY", cx, cy + 14, RED_FILL)
                b.stage == BatteryStage.WARNING ->
                    ctx.drawCenteredString(font, "BATTERY WARNING", cx, cy + 14, YELLOW_FILL)
                link.state == LinkState.FAILSAFE ->
                    ctx.drawCenteredString(font, "FAILSAFE", cx, cy + 14, RED_FILL)
                link.state == LinkState.HOLD ->
                    ctx.drawCenteredString(font, "RX LOST", cx, cy + 14, YELLOW_FILL)
            }
        }
    }

    private fun formatSec(s: Float): String {
        val v = s.toInt()
        return "%d:%02d".format(v / 60, v % 60)
    }
}
