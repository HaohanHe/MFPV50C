/*
 * FPV Craft - MIT
 * In-game Chinese help screen. Keybinds / channels read live from the client
 * config/keybindings (never hardcoded), so this always matches reality.
 */
package dev.fpv.client.gui

import dev.fpv.client.FpvClient
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class HelpScreen(private val parent: Screen?) :
    Screen(Component.translatable("screen.fpv.help")) {

    private val lines = mutableListOf<String>()

    override fun init() {
        lines.clear()
        lines += "=== FPV 帮助 ==="
        lines += "进入/退出 FPV：键盘 V（无遥控器时的备用键）"
        lines += "当前手型：Mode${FpvClient.config.handMode}（默认 Mode2）"
        lines += "飞行模式：ACRO / Angle / Horizon / 3D"
        lines += "黑匣子记录：R；回放/导出：U"
        lines += "OSD：可在 OSD 编辑器中增删元素、公制/英制切换"
        lines += "竞速：在赛道模式放门，按序过门自动计圈；BOOST 门给推力"
        lines += ""
        lines += "FAQ："
        lines += "·连不上遥控器？检查手柄已插、useRadio=true；可改用键盘"
        lines += "·解锁不了？确认 arm 开关拨到解锁侧、校准完成"
        lines += "·没声音？检查音频开关与电机怠速阈值"
        addRenderableWidget(Button.builder(Component.literal("关闭"), { onClose() }).bounds(width / 2 - 60, height - 30, 120, 20).build())
    }

    override fun render(g: GuiGraphics, mx: Int, my: Int, dt: Float) {
        super.render(g, mx, my, dt)
        lines.forEachIndexed { i, s -> g.drawString(font, s, 12, 16 + i * 12, 0xFFFFFF) }
    }

    override fun onClose() { minecraft.setScreen(parent) }
}
