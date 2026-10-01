/*
 * FPV Craft - MIT
 * FPV OSD. Draws by walking the persisted layout and dispatching on each
 * element's registry renderer type (see dev.fpv.flight.OsdElements). Text elements
 * are formatted by the pure, headless-testable OsdFormatter; positions / enabled /
 * unit come from the persisted layout data. Adding an element is a registry entry.
 */
package dev.fpv.client.osd

import dev.fpv.client.FpvClient
import dev.fpv.flight.BatteryStage
import dev.fpv.flight.Defaults
import dev.fpv.flight.LinkState
import dev.fpv.flight.OsdColor
import dev.fpv.flight.OsdElementSpec
import dev.fpv.flight.OsdElements
import dev.fpv.flight.OsdFormatter
import dev.fpv.flight.OsdRenderer
import dev.fpv.flight.OsdTelemetry
import dev.fpv.flight.OsdUnit
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

    private fun OsdColor.argb(): Int = when (this) {
        OsdColor.GREEN -> GREEN
        OsdColor.YELLOW -> YELLOW
        OsdColor.RED -> RED
        OsdColor.CYAN -> CYAN
        OsdColor.GRAY -> GRAY
    }

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
                "$mark REC  T+${formatSec(f.recordedSec)}  n=${f.sampleCount}", 4, sh - 12, 0xFFFF3333.toInt(), true,
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

        // Guidance while the player is trying to fly but cannot yet.
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

        // ---- Build the frozen telemetry snapshot once ----
        val b = FpvClient.battery
        val iv = Quaternionf(flight.attitude).conjugate()
        val bu = Vector3f(0f, 1f, 0f).rotate(iv)
        val bfwd = Vector3f(0f, 0f, -1f).rotate(iv)
        val tel = OsdTelemetry(
            rollDeg = Math.toDegrees(atan2(-bu.x, bu.y).toDouble()).toFloat(),
            pitchDeg = Math.toDegrees(asin((-bfwd.y).coerceIn(-1f, 1f).toDouble())).toFloat(),
            targetPitchDeg = flight.targetPitchDeg,
            targetRollDeg = flight.targetRollDeg,
            groundSpeedMps = (p.deltaMovement.length() * 20.0).toFloat(),
            vbat = b.vbat,
            perCell = b.perCell(),
            percent = b.percent(),
            batteryStage = b.stage,
            currentA = b.currentA,
            mAhDrawn = b.mAhDrawn,
            lq = FpvClient.link.lq.toFloat(),
            throttle = FpvClient.throttle,
            reversible3D = cfg.reversible3D,
            headfree = cfg.headfreeEnabled,
            mode = flight.currentMode.name,
            armed = FpvClient.armed,
            flightTimeSec = FpvClient.flightTimeSec,
            linkFailsafe = FpvClient.link.state == LinkState.FAILSAFE,
            linkHold = FpvClient.link.state == LinkState.HOLD,
        )
        val globalUnit = OsdUnit.parse(cfg.osdUnit)

        // ---- Walk the persisted layout; dispatch on registry renderer type ----
        for (e in cfg.osdElements) {
            val spec = OsdElements.byId(e.id) ?: continue
            if (!e.enabled) continue
            when (spec.renderer) {
                OsdRenderer.TEXT -> {
                    // TARGET is only shown outside ACRO (self-leveling gives a target attitude).
                    if (e.id == OsdElements.TARGET && flight.currentMode == dev.fpv.flight.FlightMode.ACRO) continue
                    val unit = OsdFormatter.effectiveUnit(globalUnit, e.unitOverride)
                    val txt = OsdFormatter.text(spec, tel, unit) ?: continue
                    ctx.drawString(font, txt, e.x, e.y, textColor(spec, tel), true)
                }
                OsdRenderer.BANNER -> drawBanner(ctx, font, e, spec, tel, cx, cy)
                OsdRenderer.ICON, OsdRenderer.HORIZON -> { /* center group below */ }
                OsdRenderer.BAR, OsdRenderer.LADDER, OsdRenderer.GAUGE -> { /* reserved */ }
            }
        }

        // ---- Center-anchored artificial horizon + sidebars ----
        val roll = dev.fpv.flight.OsdLayoutMath.rollRad(flight.attitude)
        val groupDy = dev.fpv.flight.OsdLayoutMath.groupDyPx(flight.attitude).toFloat()
        val showHorizon = el(OsdLayout.ARTIFICIAL_HORIZON)?.enabled == true
        val showSidebars = el(OsdLayout.HORIZON_SIDEBARS)?.enabled == true
        if (showHorizon || showSidebars) {
            val pose = ctx.pose()
            pose.pushMatrix()
            pose.translate(cx.toFloat(), cy.toFloat() + groupDy)
            pose.rotate(roll)
            if (showHorizon) {
                ctx.fill(-30, -1, 30, 1, GREEN_FILL)
                for (absL in Defaults.OSD_PITCH_LADDER_DEG) {
                    for (signedL in intArrayOf(absL, -absL)) {
                        val ly = signedL * Defaults.OSD_PITCH_PX_PER_DEG
                        if (ly < -Defaults.OSD_PITCH_LADDER_RANGE_PX ||
                            ly > Defaults.OSD_PITCH_LADDER_RANGE_PX
                        ) continue
                        val iy = ly.toInt()
                        val h = Defaults.OSD_PITCH_TICK_HALF
                        ctx.fill(-h, iy, h, iy + 1, GREEN_FILL)
                        ctx.drawString(font, signedL.toString(), h + 2, iy - 3, GREEN, false)
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
    }

    /** Dynamic text colour (battery/lq thresholds override the registry default). */
    private fun textColor(spec: OsdElementSpec, tel: OsdTelemetry): Int = when (spec.id) {
        OsdElements.BATTERY -> when (tel.batteryStage) {
            BatteryStage.CRITICAL -> RED
            BatteryStage.WARNING -> YELLOW
            else -> GREEN
        }
        OsdElements.LQ -> if (tel.lq < 50) RED else if (tel.lq < 80) YELLOW else GREEN
        else -> spec.color.argb()
    }

    /** Centered banner elements: mode banner + transient center warning. */
    private fun drawBanner(
        ctx: GuiGraphics, font: net.minecraft.client.gui.Font, e: OsdElement,
        spec: OsdElementSpec, tel: OsdTelemetry, cx: Int, cy: Int,
    ) {
        when (spec.id) {
            OsdElements.MODE -> {
                val modeTag = buildString {
                    append(tel.mode)
                    if (tel.reversible3D) append(" 3D")
                    if (tel.headfree) append(" HF")
                }
                val armTag = if (tel.armed) " ARM" else " DISARM"
                ctx.drawCenteredString(
                    font, "FPV $modeTag$armTag", cx, 8, if (tel.armed) GREEN else RED,
                )
            }
            OsdElements.CENTER_WARNING -> when {
                tel.batteryStage == BatteryStage.CRITICAL ->
                    ctx.drawCenteredString(font, "LOW BATTERY", cx, cy + 14, RED_FILL)
                tel.batteryStage == BatteryStage.WARNING ->
                    ctx.drawCenteredString(font, "BATTERY WARNING", cx, cy + 14, YELLOW_FILL)
                tel.linkFailsafe ->
                    ctx.drawCenteredString(font, "FAILSAFE", cx, cy + 14, RED_FILL)
                tel.linkHold ->
                    ctx.drawCenteredString(font, "RX LOST", cx, cy + 14, YELLOW_FILL)
            }
        }
    }

    private fun formatSec(s: Float): String {
        val v = s.toInt()
        return "%d:%02d".format(v / 60, v % 60)
    }
}
