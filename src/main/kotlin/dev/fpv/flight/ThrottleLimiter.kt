/*
 * FPV Craft - MIT
 *
 * Throttle limiter: optional cap on the normalized throttle channel, clean-room
 * implementation of the published OFF / SCALE / CLIP behaviors.
 *
 *  - OFF   : pass through unchanged (default).
 *  - SCALE : multiply by percent/100 (linear down-scale of the whole range).
 *  - CLIP  : never exceed percent/100 (cut off the top of the range).
 *
 * In reversible-3D mode throttle is signed (-1..1, center = zero): the limit
 * applies to the magnitude while the sign is preserved.
 */
package dev.fpv.flight

import kotlin.math.abs
import kotlin.math.sign

object ThrottleLimiter {

    /**
     * @param throttle normalized throttle, 0..1 (or -1..1 in 3D mode)
     * @param cfg      throttle-limit configuration
     * @param threeD   true when reversible-3D throttle semantics are active
     * @return limited throttle, same sign convention as the input
     */
    fun apply(throttle: Float, cfg: ThrottleLimitConfig, threeD: Boolean = false): Float {
        val t = if (throttle.isFinite()) throttle else 0f
        val limit = (cfg.percent / 100f).coerceIn(0f, 1f)

        return when (cfg.type) {
            // SCALE: signed range scales linearly; sign survives naturally.
            "SCALE" -> t * limit

            // CLIP: cap magnitude at the limit, keep the sign.
            "CLIP" -> if (threeD) {
                val mag = abs(t)
                sign(t) * if (mag > limit) limit else mag
            } else {
                if (t > limit) limit else t
            }

            // OFF: pass through.
            else -> t
        }
    }
}
