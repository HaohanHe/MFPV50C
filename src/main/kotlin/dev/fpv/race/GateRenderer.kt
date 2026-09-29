/*
 * FPV Craft - MIT
 * Draws the gate wireframes and the ghost marker into the world. Uses the 1.21.11
 * immediate-mode line pipeline exactly like vanilla gizmos: camera-relative
 * vertices through the shared MultiBufferSource under AFTER_ENTITIES (which has a
 * valid PoseStack and fires before vanilla gizmo finalisation). No entities.
 */
package dev.fpv.race

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

object GateRenderer {

    private const val ARGB_GREY = 0xFF666666.toInt()
    private const val ARGB_CURRENT = 0xFFFFFF55.toInt()
    private const val ARGB_PASSED = 0xFF55FF55.toInt()
    private const val ARGB_GHOST = 0xFF55FFFF.toInt()

    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register { ctx ->
            val mc = Minecraft.getInstance()
            if (mc.level == null) return@register
            val cam = mc.gameRenderer.mainCamera
            val camPos = cam.position()

            val consumers: MultiBufferSource = ctx.consumers()
            val vc = consumers.getBuffer(RenderTypes.lines())
            val pose: PoseStack.Pose = ctx.matrices().last()

            val next = RaceManager.nextGateIndex()
            val timing = RaceManager.isTiming()

            for (g in RaceManager.track.gates) {
                val color = when {
                    !timing -> ARGB_GREY
                    g.index < next -> ARGB_PASSED
                    g.index == next -> ARGB_CURRENT
                    else -> ARGB_GREY
                }
                drawRect(vc, pose, g, camPos, color)
                // Gate number floating at the centre (billboard text).
                Gizmos.billboardText(
                    g.index.toString(),
                    g.center(),
                    TextGizmo.Style.forColorAndCentered(color).withScale(0.5f),
                )
            }

            drawGhost(vc, pose, camPos)
        }
    }

    private fun drawRect(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose,
        gate: GateDef,
        cam: Vec3,
        color: Int,
    ) {
        val c = gate.corners()
        for (i in c.indices) {
            line(vc, pose, c[i], c[(i + 1) % c.size], cam, color)
        }
        // Centre cross so the gate centre is easy to see.
        val mid = { a: Vec3, b: Vec3 -> a.add(b).scale(0.5) }
        line(vc, pose, mid(c[0], c[2]), mid(c[1], c[3]), cam, color)
    }

    private fun line(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose,
        a: Vec3,
        b: Vec3,
        cam: Vec3,
        color: Int,
    ) {
        val ax = (a.x - cam.x).toFloat()
        val ay = (a.y - cam.y).toFloat()
        val az = (a.z - cam.z).toFloat()
        val bx = (b.x - cam.x).toFloat()
        val by = (b.y - cam.y).toFloat()
        val bz = (b.z - cam.z).toFloat()
        val nx = bx - ax
        val ny = by - ay
        val nz = bz - az
        vc.addVertex(pose, ax, ay, az).setNormal(pose, nx, ny, nz).setColor(color).setLineWidth(2.0f)
        vc.addVertex(pose, bx, by, bz).setNormal(pose, nx, ny, nz).setColor(color).setLineWidth(2.0f)
    }

    private fun drawGhost(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose,
        cam: Vec3,
    ) {
        val pos = RaceManager.ghostPos ?: return
        val q: Quaternionf = RaceManager.ghostAttitude ?: return
        // Local forward = -Z (camera convention), up = +Y.
        val fwd = org.joml.Vector3f(0f, 0f, -1f).rotate(q)
        val up = org.joml.Vector3f(0f, 1f, 0f).rotate(q)
        val right = org.joml.Vector3f(1f, 0f, 0f).rotate(q)
        val p = Vec3(pos.x, pos.y, pos.z)
        val nose = Vec3(
            pos.x + fwd.x * 0.9, pos.y + fwd.y * 0.9, pos.z + fwd.z * 0.9,
        )
        val wl = Vec3(
            pos.x - right.x * 0.25 - fwd.x * 0.4,
            pos.y - right.y * 0.25 - fwd.y * 0.4,
            pos.z - right.z * 0.25 - fwd.z * 0.4,
        )
        val wr = Vec3(
            pos.x + right.x * 0.25 - fwd.x * 0.4,
            pos.y + right.y * 0.25 - fwd.y * 0.4,
            pos.z + right.z * 0.25 - fwd.z * 0.4,
        )
        line(vc, pose, p, nose, cam, ARGB_GHOST)
        line(vc, pose, wl, wr, cam, ARGB_GHOST)
    }
}
