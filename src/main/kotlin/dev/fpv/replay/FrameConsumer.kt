/*
 * FPV Craft - MIT
 * Export encoding abstraction: decouples "rendered one frame" from "where it goes".
 * Two concrete consumers: an ffmpeg stdin pipe and a PNG image sequence fallback.
 * The exporter is therefore agnostic to the encoder; adding a new one (WebM, ProRes,
 * a dummy for tests) means implementing this interface.
 */
package dev.fpv.replay

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

interface FrameConsumer {
    /** Write one rgb24 frame of size w*h. */
    fun consume(rgb24: ByteArray, w: Int, h: Int)

    /** Finish up; return the process/exit code (0 = clean; -1 if N/A). */
    fun close(): Int

    /** Where the output lands (for the UI toast). */
    val output: Path
}

/** PNG image-sequence fallback (used when ffmpeg is missing / disabled). Frames numbered continuously. */
class PngFrameConsumer(private val dir: Path) : FrameConsumer {
    private var n = 0
    override val output: Path = dir

    init { Files.createDirectories(dir) }

    override fun consume(rgb24: ByteArray, w: Int, h: Int) {
        n++
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = (y * w + x) * 3
                val r = rgb24[o].toInt() and 0xFF
                val g = rgb24[o + 1].toInt() and 0xFF
                val b = rgb24[o + 2].toInt() and 0xFF
                img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        val name = "frame_" + n.toString().padStart(6, '0') + ".png"
        ImageIO.write(img, "PNG", dir.resolve(name).toFile())
    }

    override fun close(): Int = 0
}
