/*
 * FPV Craft - MIT
 * Central runtime safety validators. Every value that enters integration or
 * rendering passes through here: NaN/Inf in rates, attitude, body rates or
 * velocity is caught and replaced with a safe value so a single bad number can
 * never propagate into the attitude integral or the camera and cause a flyaway
 * or crash. Pure helpers; the per-scene fallback decision lives at the call site.
 */
package dev.fpv.flight

import org.joml.Quaternionf
import org.joml.Vector3f

object SafetyGuards {

    fun finite(v: Float): Boolean = v.isFinite()

    fun finite(v: Vector3f): Boolean =
        v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    fun finite(q: Quaternionf): Boolean =
        q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite()

    /** Clamp a normalized channel/rate into [-1,1]; non-finite -> 0 (neutral). */
    fun channel(v: Float): Float =
        if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f

    /**
     * Return a safe unit quaternion: non-finite / zero-norm -> identity,
     * otherwise normalized. Never yields NaN.
     */
    fun sanitize(q: Quaternionf, out: Quaternionf = Quaternionf()): Quaternionf {
        if (!finite(q) || (q.x == 0f && q.y == 0f && q.z == 0f && q.w == 0f))
            return out.identity()
        return out.set(q).normalize()
    }

    /** Replace a non-finite vector with [fallback]. */
    fun sanitize(v: Vector3f, fallback: Vector3f = Vector3f()): Vector3f =
        if (finite(v)) v else v.set(fallback)
}
