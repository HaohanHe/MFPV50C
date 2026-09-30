/*
 * FPV Craft - MIT
 * Per-frame hook: integrate attitude before the camera is set up later in renderLevel.
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import dev.fpv.replay.CinematicExport
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GameRenderer::class)
class GameRendererMixin {

    @Inject(method = ["renderLevel"], at = [At("HEAD")])
    private fun fpvRenderLevelHead(delta: DeltaTracker, ci: CallbackInfo) {
        FpvClient.onFrame(Minecraft.getInstance())
    }

    @Inject(method = ["renderLevel"], at = [At("TAIL")])
    private fun fpvRenderLevelTail(delta: DeltaTracker, ci: CallbackInfo) {
        // Offline cinematic export: the world was just rendered at the replay
        // cursor; read pixels back, accumulate, and advance the export state.
        CinematicExport.onLevelRendered()
    }
}
