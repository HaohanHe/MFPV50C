/*
 * FPV Craft - MIT
 *
 * Detects server rubber-band setbacks: when the server sends an ABSOLUTE position
 * correction far from the client prediction, that is a teleport/snap (not an
 * ordinary small correction). Feed the pure SetbackDetector, then notify the
 * shared event bus; LocalPlayerMixin consumes it and calls ServerCompatLogic
 * .onSetback() (wider firework interval + softened turn). Single-player never
 * trips this (isLocalServer returns early).
 *
 * Target: net.minecraft.client.multiplayer.ClientPacketListener#handleMovePlayer
 * (the mojmap handler for ClientboundPlayerPositionPacket; confirmed via javap).
 */
package dev.fpv.client.mixin

import dev.fpv.client.FpvClient
import dev.fpv.flight.SetbackDetector
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientPacketListener::class)
class ClientPacketListenerMixin {

    @Inject(method = ["handleMovePlayer"], at = [At("HEAD")])
    private fun fpvDetectSetback(packet: ClientboundPlayerPositionPacket, ci: CallbackInfo) {
        runCatching {
            val mc = Minecraft.getInstance()
            if (mc.isLocalServer) return@runCatching          // single-player: no setbacks
            val player = mc.player ?: return@runCatching
            val cfg = FpvClient.config
            val sc = cfg.serverCompat ?: return@runCatching
            if (!sc.antiKick || !sc.compatEnabled) return@runCatching

            val change = packet.change()
            val relativesEmpty = packet.relatives().isEmpty()
            val dist = change.position().distanceTo(player.position())
            // Cooldown is enforced at consume time (LocalPlayerMixin), not here.
            if (SetbackDetector.isSetback(dist, relativesEmpty, sc.setbackThresholdBlocks,
                    Long.MAX_VALUE, 0L)) {
                FpvClient.notifyServerSetback(dist)
            }
        }
    }
}
