/*
 * FPV Craft - MIT
 *
 * Module 1 optics math (clean-room, generic wide-lens distortion). The actual GLSL shader
 * mirrors these formulas; they live here as pure functions so headless can pin the contract:
 *   center stays put, edge displacement is monotonic, off == identity, vignette darkens edges.
 */
package dev.fpv.flight

object OpticsMath {

    /**
     * Radial barrel/pincushion UV remap: uv' = c + (uv - c) * (1 + k1*r^2 + k2*r^4),
     * where r = length(uv - c) in centered [-0.5,0.5] UV space.
     *
     * @return [outX, outY] sampled UV.
     */
    fun barrelRemap(uvX: Float, uvY: Float, k1: Float, k2: Float): FloatArray {
        val cx = uvX - 0.5f
        val cy = uvY - 0.5f
        val r2 = cx * cx + cy * cy
        val scale = 1f + k1 * r2 + k2 * r2 * r2
        return floatArrayOf(0.5f + cx * scale, 0.5f + cy * scale)
    }

    /**
     * Vignette multiplier 0..1 (1 = no darkening at center, <1 at the edges).
     * vigUV is centered [-0.5,0.5]; strength 0..1.
     */
    fun vignette(uvX: Float, uvY: Float, strength: Float): Float {
        val dx = (uvX - 0.5f) * 1.1f
        val dy = uvY - 0.5f
        val vig = 1f - (dx * dx + dy * dy) * (1.8f * strength)
        return vig.coerceIn(0f, 1f)
    }
}
