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

    // Home = arm position tracking (rising edge of armed).
    private var wasArmed = false
    private var hasHome = false
    private var homeX = 0f
    private var homeZ = 0f

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

        // Home point = arm position (captured on the arm rising edge).
        val wx = p.x.toFloat(); val wz = p.z.toFloat()
        if (FpvClient.armed && !wasArmed) { homeX = wx; homeZ = wz; hasHome = true }
        if (!FpvClient.armed) { wasArmed = false; hasHome = false }
        wasArmed = FpvClient.armed
        val homeBearing = if (hasHome) Math.toDegrees(kotlin.math.atan2((wx - homeX).toDouble(), (wz - homeZ).toDouble())).toFloat() else 0f
        val homeDist = if (hasHome) kotlin.math.hypot((wx - homeX).toDouble(), (wz - homeZ).toDouble()).toFloat() else 0f

        val motorNorm = flight.readMotorNorm()
        val motorRpm = FloatArray(motorNorm.size) { kotlin.math.abs(motorNorm[it]) * Defaults.ESC_MAX_RPM }
        val horiz = (flight.transHoriz * 20f).coerceAtLeast(0f)
        val groundKmh = horiz * dev.fpv.flight.OsdUnit.MPS_TO_KMH
        val packMah = cfg.activeBattery().packCapacityMah.toFloat().coerceAtLeast(1f)
        val remHours = if (b.currentA > 0.1f) (b.percent() / 100f) * packMah / 100f / b.currentA else 0f
        val tel = OsdTelemetry(
            rollDeg = Math.toDegrees(atan2(-bu.x, bu.y).toDouble()).toFloat(),
            pitchDeg = Math.toDegrees(asin((-bfwd.y).coerceIn(-1f, 1f).toDouble())).toFloat(),
            targetPitchDeg = flight.targetPitchDeg,
            targetRollDeg = flight.targetRollDeg,
            groundSpeedMps = horiz,
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
            headingDeg = flight.headingDeg(),
            hasHome = hasHome,
            homeBearingDeg = homeBearing,
            homeDistM = homeDist,
            varioMps = flight.transVy * 20f,
            altitudeM = p.getY().toFloat(),
            motorRpm = motorRpm,
            mixerOut = motorNorm,
            lapCurrentMs = dev.fpv.race.RaceManager.currentLapMs(),
            lapBestMs = dev.fpv.race.RaceManager.bestRoundMs(),
            remainingTimeSec = remHours * 3600f,
            efficiency = if (b.currentA > 0.1f) groundKmh / b.currentA else 0f,
            craftName = cfg.craftName,
            pilotName = cfg.pilotName,
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
                OsdRenderer.BAR -> drawBar(ctx, e, spec, tel, globalUnit)
                OsdRenderer.LADDER -> drawCompassBar(ctx, e, tel, font)
                OsdRenderer.GAUGE -> { /* reserved */ }
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

    /**
     * BAR widgets: speed / altitude side bars (Uncrashed-style) and the 4 motor-diag
     * bars. Vertical fill proportional to the real telemetry value.
     */
    private fun drawBar(
        ctx: GuiGraphics, e: OsdElement, spec: OsdElementSpec, tel: OsdTelemetry, unit: OsdUnit,
    ) {
        val W = 6; val H = 60
        when (spec.id) {
            OsdElements.SPEED_BAR -> {
                val v = OsdUnit.speedDisplay(tel.groundSpeedMps, unit)
                val f = (v / 120f).coerceIn(0f, 1f)
                ctx.fill(e.x, e.y, e.x + W, e.y + H, 0x40FFFFFF.toInt())
                ctx.fill(e.x, e.y + H - (f * H).toInt(), e.x + W, e.y + H, GREEN_FILL)
            }
            OsdElements.ALTITUDE_BAR -> {
                val v = OsdUnit.distanceDisplay(tel.altitudeM, unit)
                val f = (v / 50f).coerceIn(0f, 1f)
                ctx.fill(e.x, e.y, e.x + W, e.y + H, 0x40FFFFFF.toInt())
                ctx.fill(e.x, e.y + H - (f * H).toInt(), e.x + W, e.y + H, CYAN)
            }
            OsdElements.MOTOR_DIAG -> {
                // 4 vertical bars, signed (3D reverse fills downward).
                for (i in tel.mixerOut.indices) {
                    val m = tel.mixerOut[i].coerceIn(-1f, 1f)
                    val bx = e.x + i * 8
                    val mid = e.y + H / 2
                    ctx.fill(bx, e.y, bx + 6, e.y + H, 0x40FFFFFF.toInt())
                    if (m >= 0f) ctx.fill(bx, mid - (m * H / 2).toInt(), bx + 6, mid, GREEN_FILL)
                    else ctx.fill(bx, mid, bx + 6, mid + (-m * H / 2).toInt(), YELLOW_FILL)
                }
            }
        }
    }

    /** Compass ladder: N/E/S/W ticks + a centre marker on the current heading. */
    private fun drawCompassBar(
        ctx: GuiGraphics, e: OsdElement, tel: OsdTelemetry, font: net.minecraft.client.gui.Font,
    ) {
        val W = 120; val H = 12
        val h = tel.headingDeg
        ctx.fill(e.x, e.y, e.x + W, e.y + H, 0x40000000.toInt())
        for (d in 0..11) {
            val deg = d * 30
            val rel = ((deg - h) % 360 + 360) % 360
            if (rel > 90 && rel < 270) continue
            val px = (e.x + W / 2 - (rel - 180) * (W / 180)).toInt()
            val label = OsdElements.compassLetter(deg.toFloat())
            ctx.drawString(font, label, px - 3, e.y + 2, CYAN, false)
        }
        // centre marker
        ctx.fill(e.x + W / 2 - 1, e.y - 2, e.x + W / 2 + 1, e.y + H + 2, YELLOW_FILL)
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
