/*
 * FPV Craft - MIT
 * Draws gate wireframes (RECTANGLE / ARCH / RING), the start/finish lines, the
 * landing-zone cylinder and the ghost marker into the world. Uses the 1.21.11
 * immediate line pipeline exactly like vanilla gizmos: camera-relative vertices
 * through the shared MultiBufferSource under AFTER_ENTITIES. No entities.
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
import kotlin.math.cos
import kotlin.math.sin

object GateRenderer {

    private const val ARGB_GREY = 0xFF666666.toInt()
    private const val ARGB_CURRENT = 0xFFFFFF55.toInt()
    private const val ARGB_PASSED = 0xFF55FF55.toInt()
    private const val ARGB_GHOST = 0xFF55FFFF.toInt()
    private const val ARGB_START = 0xFF5599FF.toInt()
    private const val ARGB_LANDING = 0xFF55FF99.toInt()

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
            val timing = RaceManager.phase == RacePhase.FLYING ||
                RaceManager.phase == RacePhase.ARMED

            // Gates.
            for (g in RaceManager.track.gates) {
                val color = when {
                    !timing -> ARGB_GREY
                    g.index < next -> ARGB_PASSED
                    g.index == next -> ARGB_CURRENT
                    else -> ARGB_GREY
                }
                when (g.gateShape()) {
                    GateShape.RING -> drawRing(vc, pose, g, camPos, color)
                    GateShape.ARCH -> drawRect(vc, pose, g, camPos, color, arched = true)
                    else -> drawRect(vc, pose, g, camPos, color, arched = false)
                }
                Gizmos.billboardText(
                    g.index.toString(), g.center(),
                    TextGizmo.Style.forColorAndCentered(color).withScale(0.5f),
                )
            }

            // Start / finish markers.
            drawMarkerRing(vc, pose, RaceManager.track.start.pos(), 1.5, ARGB_START, camPos, "START")
            val f = RaceManager.track.finish
            if (f.x != 0.0 || f.y != 0.0 || f.z != 0.0) {
                drawMarkerRing(vc, pose, f.pos(), 1.5, ARGB_START, camPos, "FINISH")
            }

            // Landing zone cylinder (a few horizontal circles + vertical post).
            drawLandingZone(vc, pose, camPos)

            drawGhost(vc, pose, camPos)
        }
    }

    private fun drawRect(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose, gate: GateDef, cam: Vec3, color: Int, arched: Boolean,
    ) {
        val c = gate.corners()
        for (i in c.indices) line(vc, pose, c[i], c[(i + 1) % c.size], cam, color)
        line(vc, pose, mid(c[0], c[2]), mid(c[1], c[3]), cam, color)
        if (arched) {
            // Arched top: a semicircle across the top edge.
            val topL = c[1]; val topR = c[0]
            val r = gate.right()
            val up = gate.up()
            val base = topL.add(topR).scale(0.5)
            val radius = gate.width / 2.0
            var prev = topL
            val steps = 8
            for (i in 1..steps) {
                val a = Math.PI * i / steps
                val p = base.add(up.scale(radius * sin(a))).add(r.scale(radius * cos(a)))
                line(vc, pose, prev, p, cam, color)
                prev = p
            }
        }
    }

    private fun drawRing(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose, gate: GateDef, cam: Vec3, color: Int,
    ) {
        // Avoid-obstacle marker: a horizontal circle (the free-space boundary).
        val center = gate.center()
        val r = gate.width / 2.0
        var prev: Vec3? = null
        val steps = 16
        for (i in 0..steps) {
            val a = 2.0 * Math.PI * i / steps
            val p = Vec3(center.x + r * cos(a), center.y, center.z + r * sin(a))
            if (prev != null) line(vc, pose, prev, p, cam, color)
            prev = p
        }
    }

    private fun drawMarkerRing(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose, at: Vec3, radius: Double, color: Int, cam: Vec3, label: String,
    ) {
        var prev: Vec3? = null
        val steps = 16
        for (i in 0..steps) {
            val a = 2.0 * Math.PI * i / steps
            val p = Vec3(at.x + radius * cos(a), at.y, at.z + radius * sin(a))
            if (prev != null) line(vc, pose, prev, p, cam, color)
            prev = p
        }
        Gizmos.billboardText(label, at.add(0.0, radius + 0.5, 0.0), TextGizmo.Style.forColorAndCentered(color).withScale(0.6f))
    }

    private fun drawLandingZone(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose, cam: Vec3,
    ) {
        val lz = RaceManager.track.landingZone
        val c = lz.center()
        val r = lz.radius
        var prev: Vec3? = null
        val steps = 20
        for (i in 0..steps) {
            val a = 2.0 * Math.PI * i / steps
            val p = Vec3(c.x + r * cos(a), c.y, c.z + r * sin(a))
            if (prev != null) line(vc, pose, prev, p, cam, ARGB_LANDING)
            prev = p
        }
        Gizmos.billboardText("LAND", c.add(0.0, 1.5, 0.0), TextGizmo.Style.forColorAndCentered(ARGB_LANDING).withScale(0.5f))
    }

    private fun line(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose, a: Vec3, b: Vec3, cam: Vec3, color: Int,
    ) {
        val ax = (a.x - cam.x).toFloat(); val ay = (a.y - cam.y).toFloat(); val az = (a.z - cam.z).toFloat()
        val bx = (b.x - cam.x).toFloat(); val by = (b.y - cam.y).toFloat(); val bz = (b.z - cam.z).toFloat()
        vc.addVertex(pose, ax, ay, az).setNormal(pose, bx - ax, by - ay, bz - az).setColor(color).setLineWidth(2.0f)
        vc.addVertex(pose, bx, by, bz).setNormal(pose, bx - ax, by - ay, bz - az).setColor(color).setLineWidth(2.0f)
    }

    private fun mid(a: Vec3, b: Vec3) = a.add(b).scale(0.5)

    private fun drawGhost(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: PoseStack.Pose, cam: Vec3,
    ) {
        val pos = RaceManager.ghostPos ?: return
        val q: Quaternionf = RaceManager.ghostAttitude ?: return
        val fwd = org.joml.Vector3f(0f, 0f, -1f).rotate(q)
        val right = org.joml.Vector3f(1f, 0f, 0f).rotate(q)
        val p = pos
        val nose = Vec3(pos.x + fwd.x * 0.9, pos.y + fwd.y * 0.9, pos.z + fwd.z * 0.9)
        val wl = Vec3(pos.x - right.x * 0.25 - fwd.x * 0.4, pos.y - right.y * 0.25 - fwd.y * 0.4, pos.z - right.z * 0.25 - fwd.z * 0.4)
        val wr = Vec3(pos.x + right.x * 0.25 - fwd.x * 0.4, pos.y + right.y * 0.25 - fwd.y * 0.4, pos.z + right.z * 0.25 - fwd.z * 0.4)
        line(vc, pose, p, nose, cam, ARGB_GHOST)
        line(vc, pose, wl, wr, cam, ARGB_GHOST)
    }
}
