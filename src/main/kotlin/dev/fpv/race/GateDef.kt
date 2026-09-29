/*
 * FPV Craft - MIT
 * Gate definition + pure geometry (plane / rectangle intersection).
 *
 * A gate is a vertical (well: oriented) rectangle whose plane normal is the
 * direction a drone must fly through it. Orientation is stored as yaw/pitch in
 * degrees (easy to author and share in JSON); the world-space orthonormal frame
 * (forward = plane normal, right = gate horizontal axis, up = gate vertical
 * axis) is derived lazily and cached.
 */
package dev.fpv.race

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

/**
 * One check gate.
 *
 * @param x gate centre world X
 * @param y gate centre world Y
 * @param z gate centre world Z
 * @param yawDeg gate heading, MC degrees (0 = south/+Z, 90 = west/-X ...). The
 *   normal (direction you fly through) is built from yaw/pitch exactly like a
 *   player look vector, so "add gate at eye" faces the way you look.
 * @param pitchDeg gate tilt, MC degrees (positive looks up).
 * @param width opening width along the gate's local right axis (blocks).
 * @param height opening height along the gate's local up axis (blocks).
 * @param index order index (0 = start/finish).
 */
data class GateDef(
    var x: Double,
    var y: Double,
    var z: Double,
    var yawDeg: Float,
    var pitchDeg: Float,
    var width: Float,
    var height: Float,
    var index: Int,
) {
    fun center(): Vec3 = Vec3(x, y, z)

    /** Plane normal = direction you fly through. MC look-vector convention. */
    fun forward(): Vec3 {
        val y = Math.toRadians(yawDeg.toDouble())
        val p = Math.toRadians(pitchDeg.toDouble())
        val cp = cos(p)
        return Vec3(-sin(y) * cp, -sin(p), cos(y) * cp).normalize()
    }

    /** Local horizontal axis (gate width direction). */
    fun right(): Vec3 {
        val n = forward()
        // world up = (0,1,0); right = forward × up, then re-orthogonalise up.
        var r = n.cross(Vec3(0.0, 1.0, 0.0))
        if (r.lengthSqr() < 1.0e-6) r = Vec3(1.0, 0.0, 0.0) // gate pitched straight up/down
        return r.normalize()
    }

    /** Local vertical axis (gate height direction). */
    fun up(): Vec3 = right().cross(forward()).normalize()

    private fun halfW() = width / 2.0
    private fun halfH() = height / 2.0

    /** The 4 corners of the opening, in world space. */
    fun corners(): List<Vec3> {
        val c = center()
        val r = right()
        val u = up()
        val hw = halfW()
        val hh = halfH()
        return listOf(
            c.add(r.scale(hw)).add(u.scale(hh)),   // top-right
            c.add(r.scale(-hw)).add(u.scale(hh)),  // top-left
            c.add(r.scale(-hw)).add(u.scale(-hh)), // bottom-left
            c.add(r.scale(hw)).add(u.scale(-hh)),  // bottom-right
        )
    }

    /**
     * Segment-plane crossing test. Returns the crossing point (world) when the
     * segment [a,b] straddles the gate plane AND the point falls inside the
     * opening rectangle; null otherwise.
     */
    fun intersect(a: Vec3, b: Vec3): Vec3? {
        val n = forward()
        val c = center()
        val da = n.dot(c.subtract(a))
        val db = n.dot(c.subtract(b))
        // Must actually cross the plane (sign change), not merely touch.
        if (da * db >= 0.0) return null
        val t = da / (da - db)
        if (t < 0.0 || t > 1.0) return null
        val p = a.add(b.subtract(a).scale(t))
        val rel = p.subtract(c)
        val alongRight = rel.dot(right())
        val alongUp = rel.dot(up())
        if (Math.abs(alongRight) > halfW() || Math.abs(alongUp) > halfH()) return null
        return p
    }

    /** Orientation as a JOML quaternion (matches FlightController.engage convention). */
    fun attitudeQuaternion(): Quaternionf = Quaternionf().rotationYXZ(
        (Math.PI.toFloat() - Math.toRadians(yawDeg.toDouble()).toFloat()),
        (-Math.toRadians(pitchDeg.toDouble()).toFloat()),
        0f,
    )

    fun forwardF(): Vector3f = Vector3f(forward().x.toFloat(), forward().y.toFloat(), forward().z.toFloat())
}
