/*
 * FPV Craft - MIT
 * FPV OSD. Element positions/enabled flags come from [OsdLayout] (data-driven);
 * only the crosshair, artificial horizon and horizon sidebars are center-anchored.
 * Adds a virtual battery readout, virtual link quality (explicitly marked as
 * simulated), flight timer and a central transient warning (low battery / RX
 * loss / failsafe) on top of the existing speed / throttle / mode / horizon.
 */
package dev.fpv.client.osd

import dev.fpv.client.FpvClient
import dev.fpv.flight.BatteryStage
import dev.fpv.flight.LinkState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.atan2

object FpvOsd {

    private const val GREEN = 0x55FF55
    private const val GREEN_FILL = 0xFF55FF55.toInt()
    private const val YELLOW = 0xFFFF55
    private const val YELLOW_FILL = 0xFFFFFF55.toInt()
    private const val RED = 0xFF5555
    private const val RED_FILL = 0xFFFF5555.toInt()
    private const val CYAN = 0x55FFFF
    private const val GRAY = 0xAAAAAA

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
                sw - 150, 4, 0xFFFF55, true,
            )
        }

        // Guidance while the player is trying to fly but cannot yet.
        if (p.isFallFlying && !flight.ready) {
            when {
                cfg.useRadio && !FpvClient.input.lastFrame().present ->
                    ctx.drawCenteredString(
                        font, "未检测到遥控器（键盘备用：按 V 开启 FPV）", cx, 8, GRAY
                    )
                cfg.useRadio && !cfg.isCalibrated() ->
                    ctx.drawCenteredString(
                        font, "遥控器未校准：打开设置 → 摇杆校准", cx, 8, YELLOW_FILL
                    )
                !FpvClient.armed ->
                    ctx.drawCenteredString(font, "DISARMED 未解锁（按 B 解锁）", cx, 8, RED_FILL)
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

        // Throttle at its persisted position (a stored y of 0 anchors bottom).
        if (el(OsdLayout.THROTTLE)?.enabled == true) {
            val e = el(OsdLayout.THROTTLE)!!
            val thr = FpvClient.throttle
            val thrStr = if (cfg.reversible3D) {
                String.format("THR %+3d%%", (thr * 100).toInt())
            } else {
                String.format("THR %3d%%", (thr * 100).toInt())
            }
            val ty = if (e.y <= 0) sh - 20 else e.y
            ctx.drawString(font, thrStr, e.x, ty, GREEN, true)
        }

        // Mode banner (centered).
        if (el(OsdLayout.MODE)?.enabled == true) {
            val modeTag = buildString {
                append(flight.currentMode.name)
                if (cfg.reversible3D) append(" 3D")
                if (cfg.headfreeEnabled) append(" HF")
            }
            ctx.drawCenteredString(font, "FPV $modeTag", cx, 8, GREEN)
        }

        // ---- Center-anchored: artificial horizon + sidebars + crosshair ----
        val inv = Quaternionf(flight.attitude).conjugate()
        val bodyUp = Vector3f(0f, 1f, 0f).rotate(inv)
        val roll = atan2(-bodyUp.x, bodyUp.y)

        if (el(OsdLayout.ARTIFICIAL_HORIZON)?.enabled == true ||
            el(OsdLayout.HORIZON_SIDEBARS)?.enabled == true
        ) {
            val pose = ctx.pose()
            pose.pushMatrix()
            pose.translate(cx.toFloat(), cy.toFloat())
            pose.rotate(roll)
            if (el(OsdLayout.ARTIFICIAL_HORIZON)?.enabled == true) {
                ctx.fill(-30, -1, 30, 1, GREEN_FILL)
            }
            if (el(OsdLayout.HORIZON_SIDEBARS)?.enabled == true) {
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
