/*
 * FPV Craft - MIT
 */
package dev.fpv.client.mixin

import dev.fpv.client.osd.FpvOsd
import dev.fpv.race.RaceManager
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiGraphics
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Gui::class)
class GuiMixin {

    @Inject(method = ["render"], at = [At("TAIL")])
    private fun fpvRenderTail(ctx: GuiGraphics, delta: DeltaTracker, ci: CallbackInfo) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (player != null && !mc.options.hideGui) {
            FpvOsd.draw(ctx)
            RaceManager.drawHud(ctx)
        }
    }
}
