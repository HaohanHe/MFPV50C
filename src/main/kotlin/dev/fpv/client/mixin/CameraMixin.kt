/*
 * FPV Craft - MIT
 * Replaces the camera rotation with the virtual drone's full attitude,
 * which is what makes roll visible to the world rendering.
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import net.minecraft.client.Camera
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import org.joml.Quaternionf
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Camera::class)
class CameraMixin {

    @Shadow
    @Final
    private var rotation: Quaternionf? = null

    @Inject(method = ["setup"], at = [At("TAIL")])
    private fun fpvSetupTail(
        level: Level,
        entity: Entity,
        detached: Boolean,
        mirrored: Boolean,
        partialTick: Float,
        ci: CallbackInfo,
    ) {
        if (FpvClient.flight.ready) {
            rotation?.set(FpvClient.flight.attitude)
        }
    }
}
