/*
 * FPV Craft - MIT
 * Translational flight dynamics (lift / hover / gravity / drag / ground effect /
 * battery derate) as a Minecraft-free, headless-testable pure-logic class. The
 * LivingEntityMixin only gathers inputs (attitude, throttle, current velocity,
 * height-above-ground, battery derate) and writes back the result + move(); all
 * the physics lives here so it can be unit-tested on a plain JVM.
 *
 * Lift points along the BODY-UP axis  up = attitude * (0,1,0)  (published
 * quadcopter convention: thrust is normal to the prop disk). At level attitude
 * up = (0,1,0) so totalThrustN(t_h) = m*g -> net vertical force ~0 (hover).
 * Tilting the disk decomposes lift into a horizontal component (translation)
 * and a reduced vertical component (natural altitude loss), exactly as a real
 * multirotor. Rigid-body / aerodynamic open-form math modeled on the public
 * gym-pybullet-drones / drone-models equations (MIT); coefficients are
 * clean-room engineering starting values pending real-machine tuning.
 */
package dev.fpv.flight

import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

/** Upper clamp for the client-side BOOST gate thrust multiplier. */
private const val BOOST_MAX_SCALE = 1.5f

class TranslationalDynamics(val af: AirframeProfile) {

    companion object {
        /** Vanilla gravity, blocks/tick^2. */
        const val GRAVITY = 0.05f
        /** m/s^2 -> blocks/tick^2, anchored so 9.81 m/s^2 == legacy 0.05. */
        const val TICK_ACCEL = GRAVITY / 9.81f
        /** Reference mass (default freestyle) so drag reproduces legacy feel. */
        const val REF_MASS_KG = 0.65f
    }

    /**
     * @return the per-tick velocity DELTA (blocks/tick) to add to current velocity.
     * @param attitude integrated body->world quaternion (up = attitude*(0,1,0))
     * @param t throttle, 0..1 (or signed when reversible3D)
     * @param vx,vy,vz current velocity, blocks/tick
     * @param aglBlocks height above local ground; <=0 or disabled => no ground effect
     * @param derate battery voltage derate (vbat/nominalV), 0..1
     * @param reversible3D signed throttle; negative reverses thrust
     */
    fun step(
        attitude: Quaternionf,
        tIn: Float,
        vx: Double, vy: Double, vz: Double,
        aglBlocks: Float,
        derate: Float,
        reversible3D: Boolean,
        threeDDeadband: Float,
        boostScale: Float = 1f,
    ): Vector3f {
        var t = tIn
        if (reversible3D && abs(t) < threeDDeadband) t = 0f
        val tMag = abs(t)
        val dir = if (t < 0f && reversible3D) -1f else 1f

        // Body-up lift direction.
        val up = Vector3f(0f, 1f, 0f).rotate(attitude)
        if (!up.isFinite) return Vector3f(0f, -GRAVITY, 0f)

        // Thrust magnitude: totalThrust * groundEffect * battery derate.
        val gf = groundFactor(aglBlocks)
        val mass = af.massKg.coerceAtLeast(1e-3f)
        val thrustAccelMps2 = af.totalThrustN(tMag) * gf * derate.coerceIn(0f, 1.5f) *
            boostScale.coerceIn(1f, BOOST_MAX_SCALE) / mass
        val thrustDelta = thrustAccelMps2 * TICK_ACCEL * dir

        var dx = up.x() * thrustDelta
        var dy = up.y() * thrustDelta - GRAVITY * (af.gravity / 9.81f)
        var dz = up.z() * thrustDelta

        // ---- Aerodynamic drag: acts in every direction, not just after throttle cut ----
        val massScale = REF_MASS_KG / mass
        val airScale = (af.airDrag / 0.40f) * af.relativeAirspeed
        val speed = sqrt(vx * vx + vy * vy + vz * vz).toFloat()

        // 1. World-frame linear (viscous) drag, isotropic.
        if (speed > 1e-4f && af.linearDrag > 0f) {
            val lin = af.linearDrag * speed * massScale * airScale
            dx -= lin * (vx.toFloat() / speed)
            dy -= lin * (vy.toFloat() / speed)
            dz -= lin * (vz.toFloat() / speed)
        }

        // Body-frame airspeed for the anisotropic terms.
        val vb = toBody(vx.toFloat(), vy.toFloat(), vz.toFloat(), attitude)

        // 2. Body-frame quadratic airframe drag (lateral/forward/vertical differ);
        //    independent of rotor rpm, so it still decelerates a zero-throttle glide.
        val qs = massScale * airScale
        val fx = -qs * af.frameDragSide * vb.x * abs(vb.x)
        val fy = -qs * af.frameDragVert * vb.y * abs(vb.y)
        val fz = -qs * af.frameDragFwd * vb.z * abs(vb.z)
        val frameDragW = Vector3f(fx, fy, fz).rotate(attitude)
        dx += frameDragW.x(); dy += frameDragW.y(); dz += frameDragW.z()

        // 3. Prop-speed body drag: F = -diag(cxy,cxy,cz) * sumRpm * vBody; scales
        //    with rotor speed (vanishes as the rotors spool down). Body -> world.
        val rpmScale = tMag * af.motorCount
        val px = -af.bodyDragXY * rpmScale * vb.x
        val py = -af.bodyDragZ * rpmScale * vb.y
        val pz = -af.bodyDragXY * rpmScale * vb.z
        val propDragW = Vector3f(px, py, pz).rotate(attitude)
        dx += propDragW.x(); dy += propDragW.y(); dz += propDragW.z()

        val out = Vector3f(dx, dy, dz)
        return if (!out.isFinite) Vector3f(0f, -GRAVITY, 0f) else out
    }

    /** Ground-effect thrust multiplier: 1 + gain*exp(-agl/height). 1 when off/near-ground edge. */
    fun groundFactor(aglBlocks: Float): Float {
        if (!af.groundEffectEnabled) return 1f
        if (aglBlocks <= 0f) return 1f + af.groundEffectGain
        val h = af.groundEffectHeightBlocks.coerceAtLeast(0.1f)
        return 1f + af.groundEffectGain * exp(-(aglBlocks / h).toDouble()).toFloat()
    }

    private fun toBody(wx: Float, wy: Float, wz: Float, attitude: Quaternionf): Vector3f {
        val inv = Quaternionf(attitude).invert()
        return Vector3f(wx, wy, wz).rotate(inv)
    }
}

private val Vector3f.isFinite: Boolean
    get() = x.isFinite() && y.isFinite() && z.isFinite()
