/*
 * FPV Craft - MIT
 * First-use onboarding wizard. Drives the pure OnboardingFlow state machine; the
 * calibration step delegates to the existing graphical CalibrationScreen. All
 * keybinds / channels read from config (never hardcoded).
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import dev.fpv.flight.OnboardingFlow
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class OnboardingScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.onboarding")) {

    private var step = OnboardingFlow.STEP_DETECT

    override fun init() {
        val cx = width / 2
        fun btn(text: String, x: Int, action: () -> Unit) = addRenderableWidget(
            Button.builder(Component.literal(text), { action() }).bounds(x, height - 32, 120, 20).build()
        )
        when (step) {
            OnboardingFlow.STEP_DETECT -> {
                btn("下一步", cx - 130) { step = OnboardingFlow.next(step); rebuild() }
                btn("跳过", cx + 10) { finish(); }
            }
            OnboardingFlow.STEP_HAND -> {
                btn("Mode1", cx - 260) { FpvClient.config.handMode = 1; rebuild() }
                btn("Mode2", cx - 130) { FpvClient.config.handMode = 2; rebuild() }
                btn("Mode3", cx + 0) { FpvClient.config.handMode = 3; rebuild() }
                btn("下一步", cx + 130) { step = OnboardingFlow.next(step); rebuild() }
            }
            OnboardingFlow.STEP_CALIBRATE -> {
                btn("开始校准", cx - 130) {
                    minecraft?.setScreen(CalibrationScreen(this))
                }
                btn("下一步", cx + 10) { step = OnboardingFlow.next(step); rebuild() }
            }
            OnboardingFlow.STEP_ARM -> {
                btn("下一步", cx - 130) { step = OnboardingFlow.next(step); rebuild() }
                btn("跳过", cx + 10) { finish() }
            }
            OnboardingFlow.STEP_FLY -> {
                btn("完成", cx - 60) { finish() }
            }
        }
        if (step != OnboardingFlow.STEP_DETECT && step != OnboardingFlow.STEP_FLY) {
            btn("上一步", cx - 260) { step = OnboardingFlow.prev(step); rebuild() }
        }
    }

    private fun finish() {
        FpvClient.config.onboardingCompleted = true
        FpvClient.config.save()
        onClose()
    }

    private fun rebuild() { clearWidgets(); init() }

    override fun render(g: GuiGraphics, mx: Int, my: Int, dt: Float) {
        super.render(g, mx, my, dt)
        val cx = width / 2
        g.drawCenteredString(font, "新手引导  第 ${step + 1}/5 步", cx, 20, 0xFFFFFF)
        when (step) {
            OnboardingFlow.STEP_DETECT -> g.drawCenteredString(font, "检测遥控器（无设备时可用键盘 V 键进入 FPV）", cx, 60, 0xFFFF88)
            OnboardingFlow.STEP_HAND -> g.drawCenteredString(font, "选择手型（当前 Mode${FpvClient.config.handMode}）", cx, 60, 0xFFFF88)
            OnboardingFlow.STEP_CALIBRATE -> g.drawCenteredString(font, "校准：开始后逐轴打满行程，实时高亮当前轴", cx, 60, 0xFFFF88)
            OnboardingFlow.STEP_ARM -> g.drawCenteredString(font, "解锁：拨 arm 开关（SF），电机怠速即就绪", cx, 60, 0xFFFF88)
            OnboardingFlow.STEP_FLY -> g.drawCenteredString(font, "轻推油门起飞，松油回落。祝飞行愉快！", cx, 60, 0x88FF88)
        }
    }

    override fun onClose() { minecraft?.setScreen(parent) }
}
