/*
 * FPV Craft - MIT
 * Client-side radio configuration screen (clean-room re-implementation of the
 * common receiver-setup layout: per-channel axis picker / clear / reverse /
 * live bar / auto-learn, plus hand mode, calibration, deadzone, tuning
 * presets and device picker). No third-party GUI library: YACL was rejected
 * because these screens need per-frame live axis values and a calibration
 * state machine, which custom rendering handles directly.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.flight.FlightMode
import dev.fpv.flight.TuningPreset
import dev.fpv.flight.FpvConfig
import dev.fpv.input.AxisLearner
import dev.fpv.input.HandLayout
import dev.fpv.input.GlfwJoystickProvider
import dev.fpv.input.StickChannels
import dev.fpv.input.StickSlot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class FpvConfigScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.config")) {

    /** Display order: throttle, roll, pitch, yaw. */
    private val channels = listOf(
        StickChannels.THROTTLE,
        StickChannels.ROLL,
        StickChannels.PITCH,
        StickChannels.YAW,
    )

    // Dynamic buttons, labels refreshed after every change.
    private lateinit var deviceBtn: Button
    private lateinit var handBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var threeDBtn: Button
    private lateinit var deadzoneBtn: Button
    private val axisBtns = HashMap<Int, Button>()
    private val revBtns = HashMap<Int, Button>()
    private var modeSwitchBtn: Button? = null
    private var armBtn: Button? = null

    /** What auto-learn is listening for: a channel index, LEARN_* code, or OFF. */
    private var learnTarget = OFF
    private var learnFlash = 0f

    /** 0 = radio/input page, 1 = advanced (flight/battery/failsafe) page. */
    private var page = 0

    private val cfg: FpvConfig get() = FpvClient.config

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        if (page == 1) {
            buildAdvancedPage()
            return
        }
        if (page == 2) {
            buildAirframePage()
            return
        }
        val w = width
        // Device picker (full width).
        deviceBtn = Button.builder(Component.literal("")) { cycleDevice() }
            .bounds(12, 26, w - 24, 18).build()
        addRenderableWidget(deviceBtn)

        // Channel rows.
        var y = ROW_Y0
        for (ch in channels) {
            axisBtns[ch] = Button.builder(Component.literal("")) { cycleAxis(ch) }
                .bounds(X_AXIS, y, 42, 18).build()
            addRenderableWidget(axisBtns[ch]!!)

            addRenderableWidget(
                Button.builder(Component.literal("X")) { clearSlot(ch) }
                    .bounds(X_CLEAR, y, 16, 18).build()
            )

            revBtns[ch] = Button.builder(Component.literal("")) { toggleReverse(ch) }
                .bounds(X_REV, y, 36, 18).build()
            addRenderableWidget(revBtns[ch]!!)

            addRenderableWidget(
                Button.builder(Component.translatable("gui.fpv.auto")) { learnTarget = ch; learnFlash = 2f }
                    .bounds(X_AUTO, y, 34, 18).build()
            )
            y += ROW_H
        }

        // Switch rows (mode cycle, arm).
        modeSwitchBtn = Button.builder(Component.literal("")) { learnTarget = LEARN_MODE; learnFlash = 2f }
            .bounds(X_AXIS, y, SWITCH_W, 18).build()
        addRenderableWidget(modeSwitchBtn!!)
        y += ROW_H

        armBtn = Button.builder(Component.literal("")) { learnTarget = LEARN_ARM; learnFlash = 2f }
            .bounds(X_AXIS, y, SWITCH_W, 18).build()
        addRenderableWidget(armBtn!!)

        // Button cluster below the crosshairs.
        handBtn = Button.builder(Component.literal("")) { cycleHandMode() }
            .bounds(12, Y_BTN_A, 90, 18).build()
        addRenderableWidget(handBtn)
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.calibrate")) { minecraft.setScreen(CalibrationScreen(this)) }
                .bounds(108, Y_BTN_A, 80, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.guided")) { minecraft.setScreen(CalibrationScreen(this)) }
                .bounds(192, Y_BTN_A, 80, 18).build()
        )

        deadzoneBtn = Button.builder(Component.literal("")) { cycleDeadzone() }
            .bounds(12, Y_BTN_B, 88, 18).build()
        addRenderableWidget(deadzoneBtn)
        modeBtn = Button.builder(Component.literal("")) { cycleFlightMode() }
            .bounds(104, Y_BTN_B, 100, 18).build()
        addRenderableWidget(modeBtn)
        threeDBtn = Button.builder(Component.literal("")) { cfg.reversible3D = !cfg.reversible3D; refreshLabels() }
            .bounds(208, Y_BTN_B, (w - 220).coerceAtLeast(70), 18).build()
        addRenderableWidget(threeDBtn)

        // Tuning presets, one button each (equal widths).
        val pw = (w - 24 - 2 * GAP) / 3
        for ((i, preset) in TuningPreset.entries.withIndex()) {
            addRenderableWidget(
                Button.builder(Component.translatable("gui.fpv.preset.${preset.id}")) {
                    preset.apply(cfg); refreshLabels()
                }.bounds(12 + i * (pw + GAP), Y_BTN_C, pw, 18).build()
            )
        }

        // Bottom row: reset / advanced / race track / OSD editor / replays.
        val bottomN = 5
        val bottomGap = 4
        val bottomTotal = w - 24
        val bottomW = (bottomTotal - (bottomN - 1) * bottomGap) / bottomN
        fun bottomAt(i: Int) = 12 + i * (bottomW + bottomGap)

        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.reset_radio")) { resetRadio() }
                .bounds(bottomAt(0), Y_RESET, bottomW, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.advanced")) {
                page = 1
                clearWidgets()
                init()
            }.bounds(bottomAt(1), Y_RESET, bottomW, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.race")) {
                minecraft.setScreen(dev.fpv.race.RaceScreen(this))
            }.bounds(bottomAt(2), Y_RESET, bottomW, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.osd_editor")) {
                minecraft.setScreen(OsdEditorScreen(this))
            }.bounds(bottomAt(3), Y_RESET, bottomW, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.replays")) {
                minecraft.setScreen(dev.fpv.replay.ReplayScreen(this))
            }.bounds(bottomAt(4), Y_RESET, bottomW, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.done")) { onClose() }
                .bounds(w - 100, height - 26, 90, 18).build()
        )

        refreshLabels()
    }

    // ---- device picker ----
    private fun cycleDevice() {
        val devices = GlfwJoystickProvider.listJoysticks()
        var idx = if (cfg.useRadio) devices.indexOfFirst { it.id == cfg.joystickId } + 1 else 0
        if (idx < 0) idx = 0
        val next = (idx + 1) % (devices.size + 1)
        if (next == 0) {
            cfg.useRadio = false
        } else {
            cfg.useRadio = true
            cfg.joystickId = devices[next - 1].id
        }
        refreshLabels()
    }

    private fun deviceLabel(): String =
        if (!cfg.useRadio) Component.translatable("gui.fpv.device_keyboard").string
        else {
            val d = GlfwJoystickProvider.listJoysticks().firstOrNull { it.id == cfg.joystickId }
            if (d != null) "遥控器: ${d.name}" else "遥控器: (未连接)"
        }

    // ---- hand mode: remaps channels to slots, never touches raw bindings ----
    private fun cycleHandMode() {
        cfg.handMode = cfg.handMode % 4 + 1
        refreshLabels()
    }

    // ---- per-channel slot actions ----
    private fun slotOf(ch: Int): StickSlot = HandLayout.slot(cfg.handMode, ch)

    private fun cycleAxis(ch: Int) {
        val n = FpvClient.input.radioAxisCount().coerceAtLeast(4)
        val sc = cfg.slotCalib[slotOf(ch).ordinal]
        val cur = sc.axisIndex
        sc.axisIndex = if (cur < 0) 0 else (cur + 1) % n
        // Manual bind relies on the documented GLFW range until calibrated.
        sc.learned = false
        refreshLabels()
    }

    private fun clearSlot(ch: Int) {
        cfg.slotCalib[slotOf(ch).ordinal].axisIndex = -1
        refreshLabels()
    }

    private fun toggleReverse(ch: Int) {
        val sc = cfg.slotCalib[slotOf(ch).ordinal]
        sc.reversed = !sc.reversed
        refreshLabels()
    }

    private fun cycleFlightMode() {
        cfg.flightMode = when (cfg.flightMode) {
            FlightMode.ACRO -> FlightMode.ANGLE
            FlightMode.ANGLE -> FlightMode.HORIZON
            else -> FlightMode.ACRO
        }
        refreshLabels()
    }

    private fun cycleDeadzone() {
        val choices = dev.fpv.flight.Defaults.DEADZONE_CHOICES
        val cur = cfg.slotCalib[StickSlot.LH.ordinal].deadzone
        var i = choices.indexOfFirst { it >= cur }.let { if (it < 0) choices.lastIndex else it }
        i = (i + 1) % choices.size
        for (sc in cfg.slotCalib) sc.deadzone = choices[i]
        refreshLabels()
    }

    /** Reset bindings to the unbound state (no axis order is assumed). */
    private fun resetRadio() {
        for (sc in cfg.slotCalib) {
            sc.axisIndex = -1
            sc.reversed = false
            sc.learned = false
            sc.deadzone = dev.fpv.flight.Defaults.CHANNEL_DEADZONE
        }
        cfg.handMode = 2
        cfg.reversible3D = false
        cfg.modeSwitchAxis = -1
        cfg.armSwitchAxis = -1
        FpvClient.armed = false
        refreshLabels()
    }

    // ---- advanced (flight / battery / failsafe) page ----
    private fun buildAdvancedPage() {
        val colW = (width - 30) / 2
        val leftX = 12
        val rightX = width / 2 + 3
        val rowH = 20

        // left column: smoothing + throttle curve + boost + headfree
        var y = 24
        addToggle(leftX, y, colW, "gui.fpv.smoothing",
            { cfg.setpointSmoothingEnabled }, { cfg.setpointSmoothingEnabled = it })
        y += rowH
        addCycle(leftX, y, colW, "gui.fpv.sp_cutoff",
            { cfg.setpointCutoffHz }, { cfg.setpointCutoffHz = it },
            floatArrayOf(15f, 20f, 30f, 40f, 60f), "%.0fHz")
        y += rowH
        addCycle(leftX, y, colW, "gui.fpv.thr_mid",
            { cfg.thrMidPct }, { cfg.thrMidPct = it },
            floatArrayOf(30f, 40f, 50f, 60f, 70f), "%.0f%%")
        y += rowH
        addCycle(leftX, y, colW, "gui.fpv.thr_expo",
            { cfg.thrExpoPct }, { cfg.thrExpoPct = it },
            floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f), "%.0f%%")
        y += rowH
        addToggle(leftX, y, colW, "gui.fpv.boost",
            { cfg.throttleBoostEnabled }, { cfg.throttleBoostEnabled = it })
        y += rowH
        addCycle(leftX, y, colW, "gui.fpv.boost_gain",
            { cfg.boostGain }, { cfg.boostGain = it },
            floatArrayOf(0.02f, 0.05f, 0.10f, 0.15f), "%.2f")
        y += rowH
        addCycle(leftX, y, colW, "gui.fpv.boost_cutoff",
            { cfg.boostCutoffHz }, { cfg.boostCutoffHz = it },
            floatArrayOf(10f, 15f, 25f, 40f), "%.0fHz")
        y += rowH
        addToggle(leftX, y, colW, "gui.fpv.headfree",
            { cfg.headfreeEnabled }, { cfg.headfreeEnabled = it })
        y += rowH
        addToggle(leftX, y, colW, "gui.fpv.pid_loop",
            { cfg.pid?.enabled ?: true }, { cfg.pid?.enabled = it })

        // right column: battery + failsafe + logging + multiplayer
        y = 24
        addCycleI(rightX, y, colW, "gui.fpv.cells",
            { cfg.battery?.cellCount ?: 4 }, { cfg.battery?.cellCount = it },
            intArrayOf(3, 4, 5, 6), "%dS")
        y += rowH
        addCycleI(rightX, y, colW, "gui.fpv.pack_mah",
            { cfg.battery?.packCapacityMah ?: 0 }, { cfg.battery?.packCapacityMah = it },
            intArrayOf(0, 1300, 1500, 1800, 2200), "%s")
        y += rowH
        addCycle(rightX, y, colW, "gui.fpv.warn_v",
            { cfg.battery?.warningCellV ?: 3.5f }, { cfg.battery?.warningCellV = it },
            floatArrayOf(3.40f, 3.50f, 3.60f), "%.2fV")
        y += rowH
        addCycle(rightX, y, colW, "gui.fpv.crit_v",
            { cfg.battery?.criticalCellV ?: 3.3f }, { cfg.battery?.criticalCellV = it },
            floatArrayOf(3.20f, 3.30f, 3.40f), "%.2fV")
        y += rowH
        addFailsafeProc(rightX, y, colW)
        y += rowH
        addCycleI(rightX, y, colW, "gui.fpv.hold_ms",
            { (cfg.failsafe?.holdMs ?: 300L).toInt() }, { cfg.failsafe?.holdMs = it.toLong() },
            intArrayOf(200, 300, 500), "%dms")
        y += rowH
        addToggle(rightX, y, colW, "gui.fpv.telemetry",
            { cfg.telemetryEnabled }, { cfg.telemetryEnabled = it })
        y += rowH
        addToggle(rightX, y, colW, "gui.fpv.translation",
            { cfg.translationEnhance }, { cfg.translationEnhance = it })
        y += rowH
        addToggle(rightX, y, colW, "gui.fpv.allow_mp",
            { cfg.allowTranslationMultiplayer }, { cfg.allowTranslationMultiplayer = it })
        y += rowH

        // Race master switch (default off = single-player freestyle).
        addToggle(rightX, y, colW, "gui.fpv.race_enabled",
            { cfg.race?.raceEnabled ?: false }, { cfg.race?.raceEnabled = it })
        y += rowH

        // Throttle limit: type (OFF/SCALE/CLIP) and percent.
        addThrottleLimitType(rightX, y, colW)
        y += rowH
        addCycleI(rightX, y, colW, "gui.fpv.thr_limit_pct",
            { (cfg.throttleLimit?.percent ?: 100f).toInt() },
            { cfg.throttleLimit?.percent = it.toFloat() },
            intArrayOf(50, 75, 90, 100), "%d%%")
        y += rowH

        // Airframe physics page nav.
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.airframe")) {
                page = 2
                clearWidgets()
                init()
            }.bounds(rightX, y, colW, 18).build()
        )
        y += rowH

        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.back")) {
                page = 0
                clearWidgets()
                init()
            }.bounds(leftX, y, colW, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.done")) { onClose() }
                .bounds(width - 100, height - 26, 90, 18).build()
        )
    }

    // ---- airframe (machine physics) page ----
    private fun buildAirframePage() {
        val colW = (width - 36) / 3
        val c1 = 12
        val c2 = c1 + colW + 6
        val c3 = c2 + colW + 6
        val rowH = 18
        val af = cfg.activeAirframe()

        // Profile selector (full width top) + New/Copy/Delete.
        val profBtn = Button.builder(Component.literal(af.name)) {
            cfg.cycleAirframe(); refreshAirframePage()
        }.bounds(c1, 24, colW * 3 + 12, 18).build()
        addRenderableWidget(profBtn)
        val bW = (colW * 3 + 12) / 3
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.airframe_new")) {
                cfg.newAirframe(); refreshAirframePage()
            }.bounds(c1, 46, bW, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.airframe_copy")) {
                cfg.copyActiveAirframe(); refreshAirframePage()
            }.bounds(c1 + bW, 46, bW, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.airframe_del")) {
                cfg.deleteActiveAirframe(); refreshAirframePage()
            }.bounds(c1 + 2 * bW, 46, bW, 18).build()
        )

        // ---- Col 1: hardware ----
        var y = 70
        addCycle(c1, y, colW, "gui.fpv.cam_tilt",
            { cfg.activeAirframe().cameraTiltDeg }, { cfg.activeAirframe().cameraTiltDeg = it },
            floatArrayOf(15f, 20f, 25f, 30f, 40f), "%.0f°"); y += rowH
        addCycleI(c1, y, colW, "gui.fpv.motor_kv",
            { cfg.activeAirframe().motorKv }, { cfg.activeAirframe().motorKv = it },
            intArrayOf(1500, 1750, 1900, 2200, 2400), "%d"); y += rowH
        addCycleI(c1, y, colW, "gui.fpv.cells_s",
            { cfg.activeAirframe().cellCountS }, { cfg.activeAirframe().cellCountS = it },
            intArrayOf(0, 4, 5, 6), "%dS"); y += rowH
        addCycle(c1, y, colW, "gui.fpv.prop_inch",
            { cfg.activeAirframe().propInch }, { cfg.activeAirframe().propInch = it },
            floatArrayOf(4.0f, 5.0f, 5.1f, 6.0f), "%.1f\""); y += rowH
        addCycle(c1, y, colW, "gui.fpv.prop_pitch",
            { cfg.activeAirframe().propPitch }, { cfg.activeAirframe().propPitch = it },
            floatArrayOf(3.5f, 4.0f, 4.6f, 5.0f, 5.5f), "%.1f"); y += rowH
        addCycle(c1, y, colW, "gui.fpv.min_thr",
            { cfg.activeAirframe().minThrottle }, { cfg.activeAirframe().minThrottle = it },
            floatArrayOf(0f, 0.03f, 0.055f, 0.08f), "%.1f%%"); y += rowH
        addCycle(c1, y, colW, "gui.fpv.thr_low",
            { cfg.activeAirframe().thrLow }, { cfg.activeAirframe().thrLow = it },
            floatArrayOf(0.90f, 0.95f, 1.00f, 1.05f), "%.2f"); y += rowH
        addCycle(c1, y, colW, "gui.fpv.thr_mid",
            { cfg.activeAirframe().thrMid }, { cfg.activeAirframe().thrMid = it },
            floatArrayOf(0.90f, 0.95f, 1.00f, 1.05f), "%.2f"); y += rowH
        addCycle(c1, y, colW, "gui.fpv.thr_high",
            { cfg.activeAirframe().thrHigh }, { cfg.activeAirframe().thrHigh = it },
            floatArrayOf(0.95f, 1.00f, 1.05f, 1.10f), "%.2f"); y += rowH

        // ---- Col 2: behavior + advanced physics ----
        y = 70
        addToggle(c2, y, colW, "gui.fpv.propwash",
            { cfg.activeAirframe().propwashEnabled }, { cfg.activeAirframe().propwashEnabled = it }); y += rowH
        addPidBehaviorRow(c2, y, colW); y += rowH
        addCycle(c2, y, colW, "gui.fpv.gravity",
            { cfg.activeAirframe().gravity }, { cfg.activeAirframe().gravity = it },
            floatArrayOf(9.81f, 9.82f, 10.5f), "%.2f"); y += rowH
        addCycle(c2, y, colW, "gui.fpv.instant_power",
            { cfg.activeAirframe().instantPower }, { cfg.activeAirframe().instantPower = it },
            floatArrayOf(0.40f, 0.55f, 0.65f, 0.80f, 1.00f), "%.2f"); y += rowH
        addCycle(c2, y, colW, "gui.fpv.air_drag",
            { cfg.activeAirframe().airDrag }, { cfg.activeAirframe().airDrag = it },
            floatArrayOf(0.25f, 0.33f, 0.40f, 0.50f, 0.65f), "%.2f"); y += rowH
        addCycle(c2, y, colW, "gui.fpv.air_grip",
            { cfg.activeAirframe().airGrip }, { cfg.activeAirframe().airGrip = it },
            floatArrayOf(0.60f, 0.75f, 0.85f, 1.00f), "%.2f"); y += rowH
        addCycle(c2, y, colW, "gui.fpv.rel_airspeed",
            { cfg.activeAirframe().relativeAirspeed }, { cfg.activeAirframe().relativeAirspeed = it },
            floatArrayOf(0.80f, 0.90f, 1.00f, 1.15f), "%.2f"); y += rowH
        addCycle(c2, y, colW, "gui.fpv.mass",
            { cfg.activeAirframe().massKg }, { cfg.activeAirframe().massKg = it },
            floatArrayOf(0.40f, 0.55f, 0.65f, 0.75f, 0.95f, 1.00f), "%.2fkg"); y += rowH
        addCycle(c2, y, colW, "gui.fpv.max_thrust",
            { cfg.activeAirframe().maxThrustPerMotorN }, { cfg.activeAirframe().maxThrustPerMotorN = it },
            floatArrayOf(20f, 25f, 30f, 35f, 40f, 45f), "%.0fN"); y += rowH

        // ---- Col 3: inertia / angular drag / CG ----
        y = 70
        addCycle(c3, y, colW, "gui.fpv.i_pitch",
            { cfg.activeAirframe().inertiaXX }, { cfg.activeAirframe().inertiaXX = it },
            floatArrayOf(0.0015f, 0.0022f, 0.0030f, 0.0040f), "%.4f"); y += rowH
        addCycle(c3, y, colW, "gui.fpv.i_yaw",
            { cfg.activeAirframe().inertiaYY }, { cfg.activeAirframe().inertiaYY = it },
            floatArrayOf(0.0025f, 0.0035f, 0.0045f, 0.0060f), "%.4f"); y += rowH
        addCycle(c3, y, colW, "gui.fpv.i_roll",
            { cfg.activeAirframe().inertiaZZ }, { cfg.activeAirframe().inertiaZZ = it },
            floatArrayOf(0.0012f, 0.0018f, 0.0025f, 0.0035f), "%.4f"); y += rowH
        addCycle(c3, y, colW, "gui.fpv.ad_pitch",
            { cfg.activeAirframe().angularDragXX }, { cfg.activeAirframe().angularDragXX = it },
            floatArrayOf(0.20f, 0.35f, 0.44f, 0.60f, 0.90f), "%.2f"); y += rowH
        addCycle(c3, y, colW, "gui.fpv.ad_yaw",
            { cfg.activeAirframe().angularDragYY }, { cfg.activeAirframe().angularDragYY = it },
            floatArrayOf(0.40f, 0.55f, 0.70f, 1.00f, 1.40f), "%.2f"); y += rowH
        addCycle(c3, y, colW, "gui.fpv.ad_roll",
            { cfg.activeAirframe().angularDragZZ }, { cfg.activeAirframe().angularDragZZ = it },
            floatArrayOf(0.20f, 0.28f, 0.36f, 0.50f, 0.80f), "%.2f"); y += rowH
        addCycle(c3, y, colW, "gui.fpv.cg_x",
            { cfg.activeAirframe().cgOffsetX }, { cfg.activeAirframe().cgOffsetX = it },
            floatArrayOf(-0.05f, -0.02f, 0f, 0.02f, 0.05f), "%+.2f"); y += rowH
        addCycle(c3, y, colW, "gui.fpv.cg_y",
            { cfg.activeAirframe().cgOffsetY }, { cfg.activeAirframe().cgOffsetY = it },
            floatArrayOf(-0.05f, -0.02f, 0f, 0.02f, 0.05f), "%+.2f"); y += rowH

        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.back")) {
                page = 1
                clearWidgets()
                init()
            }.bounds(c1, height - 26, colW * 3 / 2, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.fpv.done")) { onClose() }
                .bounds(width - 100, height - 26, 90, 18).build()
        )
    }

    /** pidBehavior cycle: PERFECT (no override) -> known TuningPreset ids. */
    private fun addPidBehaviorRow(x: Int, y: Int, w: Int) {
        lateinit var btn: Button
        val opts = listOf("PERFECT", "racing", "beginner", "cinematic")
        fun label(): String = Component.translatable(
            "gui.fpv.pid_behavior",
            Component.translatable("gui.fpv.pidb_" + cfg.activeAirframe().pidBehavior.lowercase()),
        ).string
        btn = Button.builder(Component.literal("")) {
            val cur = cfg.activeAirframe().pidBehavior
            val i = opts.indexOf(cur).let { if (it < 0) 0 else it }
            cfg.activeAirframe().pidBehavior = opts[(i + 1) % opts.size]
            cfg.applyPidBehavior()
            btn.message = Component.literal(label())
        }.bounds(x, y, w, 18).build()
        btn.message = Component.literal(label())
        addRenderableWidget(btn)
    }

    private var y2 = 0

    /** Rebuild the airframe page after create/copy/delete/switch. */
    private fun refreshAirframePage() {
        clearWidgets()
        init()
    }

    /** Live-derived performance strip (bottom of the airframe page). */
    private fun drawDerivedPerf(g: GuiGraphics) {
        val af = cfg.activeAirframe()
        val top = dev.fpv.flight.AirframeDerivation.topSpeedKmh(af)
        val thr = dev.fpv.flight.AirframeDerivation.totalThrustKg(af)
        val aero = dev.fpv.flight.AirframeDerivation.aeroIndex(af)
        val line = Component.translatable(
            "gui.fpv.derived",
            String.format("%.0f", top),
            String.format("%.0f", af.massKg * 1000f),
            String.format("%.2f", thr),
            String.format("%.2f", aero),
            af.physicsModelVersion,
        )
        g.drawCenteredString(font, line, width / 2, height - 44, 0x55FF55)
    }

    private fun addToggle(x: Int, y: Int, w: Int, key: String,
                          get: () -> Boolean, set: (Boolean) -> Unit) {
        lateinit var btn: Button
        btn = Button.builder(Component.literal("")) {
            set(!get()); itRefresh(btn, key, if (get()) "gui.fpv.on" else "gui.fpv.off")
        }.bounds(x, y, w, 18).build()
        itRefresh(btn, key, if (get()) "gui.fpv.on" else "gui.fpv.off")
        addRenderableWidget(btn)
    }

    private fun addCycle(x: Int, y: Int, w: Int, key: String,
                         get: () -> Float, set: (Float) -> Unit,
                         choices: FloatArray, fmt: String) {
        lateinit var btn: Button
        btn = Button.builder(Component.literal("")) {
            val i = choices.indexOfFirst { it >= get() }.let { if (it < 0) choices.lastIndex else it }
            set(choices[(i + 1) % choices.size])
            itRefresh(btn, key, String.format(fmt, get()))
        }.bounds(x, y, w, 18).build()
        itRefresh(btn, key, String.format(fmt, get()))
        addRenderableWidget(btn)
    }

    private fun addCycleI(x: Int, y: Int, w: Int, key: String,
                          get: () -> Int, set: (Int) -> Unit,
                          choices: IntArray, fmt: String) {
        lateinit var btn: Button
        btn = Button.builder(Component.literal("")) {
            val i = choices.indexOfFirst { it >= get() }.let { if (it < 0) choices.lastIndex else it }
            set(choices[(i + 1) % choices.size])
            val v = get()
            itRefresh(btn, key, if (key == "gui.fpv.pack_mah" && v == 0) "auto" else String.format(fmt, v))
        }.bounds(x, y, w, 18).build()
        val v = get()
        itRefresh(btn, key, if (key == "gui.fpv.pack_mah" && v == 0) "auto" else String.format(fmt, v))
        addRenderableWidget(btn)
    }

    private fun addFailsafeProc(x: Int, y: Int, w: Int) {
        lateinit var btn: Button
        btn = Button.builder(Component.literal("")) {
            val cur = cfg.failsafe?.procedure ?: "DROP"
            cfg.failsafe?.procedure = if (cur == "DROP") "LAND" else "DROP"
            itRefresh(btn, "gui.fpv.failsafe",
                Component.translatable(if ((cfg.failsafe?.procedure) == "DROP") "gui.fpv.failsafe_drop" else "gui.fpv.failsafe_land").string)
        }.bounds(x, y, w, 18).build()
        itRefresh(btn, "gui.fpv.failsafe",
            Component.translatable(if ((cfg.failsafe?.procedure) == "DROP") "gui.fpv.failsafe_drop" else "gui.fpv.failsafe_land").string)
        addRenderableWidget(btn)
    }

    private fun addThrottleLimitType(x: Int, y: Int, w: Int) {
        lateinit var btn: Button
        fun label(): String = Component.translatable(
            "gui.fpv.thr_limit",
            Component.translatable("gui.fpv.thr_limit_${cfg.throttleLimit?.type ?: "OFF"}"),
        ).string
        btn = Button.builder(Component.literal("")) {
            val cur = cfg.throttleLimit?.type ?: "OFF"
            val nextType = when (cur) {
                "OFF" -> "SCALE"
                "SCALE" -> "CLIP"
                else -> "OFF"
            }
            cfg.throttleLimit?.type = nextType
            btn.message = Component.literal(label())
        }.bounds(x, y, w, 18).build()
        btn.message = Component.literal(label())
        addRenderableWidget(btn)
    }

    private fun itRefresh(btn: Button, key: String, value: String) {
        btn.message = Component.translatable(key, value)
    }

    private fun refreshLabels() {
        deviceBtn.message = Component.literal(deviceLabel())
        handBtn.message = Component.translatable("gui.fpv.hand", cfg.handMode)
        modeBtn.message = Component.translatable(
            "gui.fpv.flightmode",
            Component.translatable("gui.fpv.mode.${cfg.flightMode.id}"),
        )
        threeDBtn.message = Component.translatable(
            "gui.fpv.throttle_mode",
            Component.translatable(if (cfg.reversible3D) "gui.fpv.throttle_3d" else "gui.fpv.throttle_normal"),
        )
        deadzoneBtn.message = Component.translatable(
            "gui.fpv.deadzone", String.format("%.2f", cfg.slotCalib[StickSlot.LH.ordinal].deadzone)
        )
        for (ch in channels) {
            val sc = cfg.slotCalib[slotOf(ch).ordinal]
            axisBtns[ch]?.message = Component.translatable(
                "gui.fpv.axis", if (sc.axisIndex < 0) "-" else "${sc.axisIndex + 1}"
            )
            revBtns[ch]?.message = Component.translatable(
                "gui.fpv.rev", Component.translatable(if (sc.reversed) "gui.fpv.on" else "gui.fpv.off")
            )
        }
        modeSwitchBtn?.message = Component.translatable(
            "gui.fpv.mode_switch", if (cfg.modeSwitchAxis < 0) "-" else "${cfg.modeSwitchAxis + 1}"
        )
        armBtn?.message = Component.translatable(
            "gui.fpv.arm_row",
            if (cfg.armSwitchAxis < 0) "-" else "${cfg.armSwitchAxis + 1}",
            Component.translatable(if (FpvClient.armed) "gui.fpv.armed" else "gui.fpv.disarmed"),
        )
    }

    // ---- rendering ----
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(g, mouseX, mouseY, delta)
        if (page == 1 || page == 2) {
            g.drawCenteredString(font, Component.translatable("gui.fpv.config"), width / 2, 6, 0xFFFFFF)
            if (page == 2) drawDerivedPerf(g)
            super.render(g, mouseX, mouseY, delta)
            return
        }
        val raw = FpvClient.input.rawAxes()
        val last = FpvClient.input.lastFrame()

        // Channel labels + live bars (values from the latest normalized frame).
        var y = ROW_Y0
        val barX = X_BAR
        val barW = (width - barX - 36).coerceAtLeast(40)
        val values = floatArrayOf(last.throttle, last.roll, last.pitch, last.yaw)
        for ((i, ch) in channels.withIndex()) {
            g.drawString(font, Component.translatable("channel.fpv.${channelKey(ch)}"), 12, y + 5, 0xFFFFFF)
            val v = values[i]
            val fromZero = ch == StickChannels.THROTTLE && !cfg.reversible3D
            drawBar(g, barX, y + 6, barW, v, fromZero)
            g.drawString(font, String.format("%+.2f", v), barX + barW + 3, y + 5, 0x55FF55)
            y += ROW_H
        }

        // Row labels for the two switch rows.
        g.drawString(font, Component.translatable("gui.fpv.mode_switch_label"), 12, y + 5, 0xAAAAAA)
        y += ROW_H
        g.drawString(font, Component.translatable("gui.fpv.arm_label"), 12, y + 5, 0xAAAAAA)

        // Data-driven AUX channels (sliders/dials/switches), live values.
        y += ROW_H
        g.drawString(font, Component.translatable("gui.fpv.aux_header"), 12, y + 5, 0xFF88CCFF.toInt())
        y += ROW_H
        val auxList = last.auxChannels
        if (auxList.isEmpty()) {
            g.drawString(font, Component.translatable("gui.fpv.aux_empty"), 12, y + 5, 0x888888)
        } else {
            for (a in auxList) {
                if (y > height - 60) break
                g.drawString(font, Component.literal(a.name), 12, y + 5, 0xFFFFFF)
                g.drawString(font, Component.literal(a.source), 70, y + 5, 0x888888)
                if (a.kind == "BUTTONS") {
                    val pos = if (a.position < 0) "-" else "${a.position + 1}/${a.positionCount}"
                    g.drawString(font, Component.literal("POS $pos"), barX + 40, y + 5, 0x55FF55)
                } else {
                    drawBar(g, barX, y + 6, barW, a.value, false)
                    g.drawString(font, String.format("%+.2f", a.value), barX + barW + 3, y + 5, 0x55FF55)
                }
                y += ROW_H
            }
        }

        // Data-driven stick crosshairs (raw values via slot bindings).
        drawSlotCross(g, width / 2 - 55, CROSS_CY, raw, StickSlot.LH, StickSlot.LV)
        drawSlotCross(g, width / 2 + 55, CROSS_CY, raw, StickSlot.RH, StickSlot.RV)

        // Auto-learn detection.
        if (learnTarget != OFF) {
            val center = FloatArray(raw.size)
            val detected = AxisLearner.detect(raw, center, boundAxes(raw))
            if (detected >= 0) {
                when (learnTarget) {
                    LEARN_MODE -> cfg.modeSwitchAxis = detected
                    LEARN_ARM -> cfg.armSwitchAxis = detected
                    else -> cfg.slotCalib[slotOf(learnTarget).ordinal].axisIndex = detected
                }
                learnTarget = OFF
                learnFlash = 1.5f
                refreshLabels()
            }
        }
        if (learnFlash > 0f) {
            learnFlash -= delta / 20f
            g.drawCenteredString(font, Component.translatable("gui.fpv.move_stick"), width / 2, 6, 0xFFFF55)
        } else if (learnTarget != OFF) {
            g.drawCenteredString(font, Component.translatable("gui.fpv.listening"), width / 2, 6, 0xFFFF55)
        }

        g.drawString(font, Component.translatable("gui.fpv.keyboard_section"), 12, height - 44, 0xAAAAAA)

        super.render(g, mouseX, mouseY, delta)
    }

    /** Raw axes already bound to any slot (excluded while learning). */
    private fun boundAxes(raw: FloatArray): Set<Int> {
        val used = HashSet<Int>()
        for (sc in cfg.slotCalib) if (sc.axisIndex in raw.indices) used += sc.axisIndex
        // When rebinding one channel, its own current axis is allowed to move.
        if (learnTarget != LEARN_MODE && learnTarget != LEARN_ARM) {
            val own = cfg.slotCalib[slotOf(learnTarget).ordinal].axisIndex
            used -= own
        }
        return used
    }

    private fun drawBar(g: GuiGraphics, x: Int, y: Int, w: Int, v: Float, fromZero: Boolean) {
        g.fill(x, y, x + w, y + 4, 0xFF222222.toInt())
        if (fromZero) {
            val fw = (v.coerceIn(0f, 1f) * w).toInt()
            g.fill(x, y, x + fw, y + 4, 0xFF55FF55.toInt())
        } else {
            val cx = x + w / 2
            val dx = (v.coerceIn(-1f, 1f) * w / 2f).toInt()
            if (dx >= 0) g.fill(cx, y, cx + dx, y + 4, 0xFF55FF55.toInt())
            else g.fill(cx + dx, y, cx, y + 4, 0xFF55FF55.toInt())
            g.fill(cx, y - 1, cx + 1, y + 5, 0xFFFFFFFF.toInt())
        }
    }

    /** Crosshair whose dot reads the raw axes bound to the given slots. */
    private fun drawSlotCross(
        g: GuiGraphics, cx: Int, cy: Int, raw: FloatArray,
        hSlot: StickSlot, vSlot: StickSlot,
    ) {
        val r = CROSS_R
        g.fill(cx - r, cy - 1, cx + r, cy + 1, 0xFFFFFFFF.toInt())
        g.fill(cx - 1, cy - r, cx + 1, cy + r, 0xFFFFFFFF.toInt())
        val hc = cfg.slotCalib[hSlot.ordinal]
        val vc = cfg.slotCalib[vSlot.ordinal]
        val hx = if (hc.axisIndex in raw.indices) raw[hc.axisIndex] else 0f
        val vy = if (vc.axisIndex in raw.indices) raw[vc.axisIndex] else 0f
        val px = (hx * r).toInt()
        val py = (-vy * r).toInt()
        g.fill(cx + px - 2, cy + py - 2, cx + px + 2, cy + py + 2, 0xFFFF5555.toInt())
    }

    private fun channelKey(ch: Int): String = when (ch) {
        StickChannels.THROTTLE -> "throttle"
        StickChannels.ROLL -> "roll"
        StickChannels.PITCH -> "pitch"
        else -> "yaw"
    }

    override fun onClose() {
        cfg.save()
        minecraft.setScreen(parent)
    }

    companion object {
        private const val OFF = -2
        private const val LEARN_MODE = 100
        private const val LEARN_ARM = 101

        // Layout tokens.
        private const val ROW_Y0 = 48
        private const val ROW_H = 20
        private const val X_AXIS = 62
        private const val X_CLEAR = 106
        private const val X_REV = 124
        private const val X_AUTO = 162
        private const val X_BAR = 200
        private const val SWITCH_W = 122
        private const val GAP = 6

        private const val CROSS_CY = 196
        private const val CROSS_R = 24

        private const val Y_BTN_A = 226
        private const val Y_BTN_B = 250
        private const val Y_BTN_C = 274
        private const val Y_RESET = 298
    }
}
