/*
 * FPV Craft - MIT
 *
 * Pure-logic separation of the motion-blur (slow-shutter) pass from the OSD/HUD
 * overlay, so the render layering can be unit-tested on a plain JVM:
 *
 *   1. scene pixels are accumulated into a history buffer (motion blur).
 *   2. OSD/HUD pixels are NEVER added to that history.
 *   3. the final image = blurred-scene + OSD composited ON TOP each output frame.
 *
 * This mirrors the intended GPU pipeline: render the 3D world to the scene target,
 * blur only that target, then draw the OSD as the top-most overlay AFTER the blur.
 * The real GL readback (CinematicExport) reads the world main target at
 * renderLevel TAIL, i.e. BEFORE Gui.render() draws the OSD, so the exported
 * history already excludes the HUD; this class makes that contract explicit and
 * provable. [NEEDS LOCAL VERIFICATION] on the exact bound FBO / Gui ordering on a
 * real GL backend; nothing here touches GL.
 */
package dev.fpv.flight

class MotionBlurCompositor(private val samples: Int) {

    private val n = samples.coerceAtLeast(1)
    private var acc = FloatArray(0)
    private var count = 0

    /** Reset the history (start a new output frame). */
    fun reset() {
        count = 0
        if (acc.isNotEmpty()) acc.fill(0f)
    }

    /**
     * Accumulate ONE scene sub-sample into the motion-blur history. OSD must NOT
     * call this; only the blurred scene frames do.
     */
    fun accumulateScene(scene: FloatArray) {
        if (acc.size != scene.size) acc = FloatArray(scene.size)
        val w = 1f / n
        for (i in scene.indices) acc[i] += scene[i] * w
        count++
    }

    /** True once enough sub-samples have been accumulated for one output frame. */
    fun frameReady(): Boolean = count >= n

    /**
     * Composite the final output: blurred scene underneath, OSD on top.
     * OSD pixels (non-zero alpha) REPLACE the scene; they never enter the history.
     * @param osd  packed RGBA (alpha>0 means an OSD pixel) same size as scene*4.
     * @return RGB of the composited frame (size scene RGB).
     */
    fun composite(osd: FloatArray): FloatArray {
        // acc holds averaged scene RGB (size = pixels*3). osd is pixels*4 RGBA.
        val px = acc.size / 3
        val out = FloatArray(px * 3)
        for (p in 0 until px) {
            val a = osd[p * 4 + 3]
            if (a > 0.01f) {
                out[p * 3] = osd[p * 4]
                out[p * 3 + 1] = osd[p * 4 + 1]
                out[p * 3 + 2] = osd[p * 4 + 2]
            } else {
                out[p * 3] = acc[p * 3]
                out[p * 3 + 1] = acc[p * 3 + 1]
                out[p * 3 + 2] = acc[p * 3 + 2]
            }
        }
        return out
    }

    /** For tests: the accumulated blurred-scene pixel at index (RGB flat). */
    fun scenePixel(i: Int): Float = acc[i]
}
