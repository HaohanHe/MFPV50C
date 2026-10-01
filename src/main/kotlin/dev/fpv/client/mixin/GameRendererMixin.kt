/*
 * FPV Craft - MIT
 * Per-frame hook: integrate attitude before the camera is set up later in renderLevel, and
 * drive the FPV post-process chain. The chain Identifier("fpv","fpv_post") resolves to
 * assets/fpv/shaders/post/fpv_post.json (ShaderManager prefixes shaders/post); enabling uses
 * GameRenderer's own private setPostEffect via @Shadow, disabling uses public clearPostEffect().
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import dev.fpv.replay.CinematicExport
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.resources.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GameRenderer::class)
abstract class GameRendererMixin {

    @Shadow protected abstract fun setPostEffect(id: Identifier)
    @Shadow public abstract fun clearPostEffect()

    @Unique private val fpvPostId: Identifier = Identifier.fromNamespaceAndPath("fpv", "fpv_post")
    @Unique private var fpvApplied: Boolean = false

    @Inject(method = ["renderLevel"], at = [At("HEAD")])
    private fun fpvRenderLevelHead(delta: DeltaTracker, ci: CallbackInfo) {
        FpvClient.onFrame(Minecraft.getInstance())
    }

    @Inject(method = ["renderLevel"], at = [At("TAIL")])
    private fun fpvRenderLevelTail(delta: DeltaTracker, ci: CallbackInfo) {
        CinematicExport.onLevelRendered()
    }

    @Inject(method = ["render"], at = [At("HEAD")])
    private fun fpvPostChain(delta: DeltaTracker, tick: Boolean, ci: CallbackInfo) {
        val want = FpvClient.immersionPostActive()
        if (want && !fpvApplied) {
            setPostEffect(fpvPostId)
            fpvApplied = true
        } else if (!want && fpvApplied) {
            clearPostEffect()
            fpvApplied = false
        }
    }
}
