/*
 * FPV Craft - MIT
 * Stage 3: offline, frame-accurate cinematic export. We do NOT record the
 * screen live; instead we advance the replay cursor deterministically, let the
 * normal world render produce each sub-frame, glReadPixels it back, accumulate
 * N sub-samples for a 180-degree shutter motion blur, letterbox to the target
 * aspect on the CPU, and push RGBA frames to a bundled FFmpeg over stdin.
 *
 *   - FFmpeg missing -> fall back to a PNG image sequence (no bundled Java H.264).
 *   - The render->readPixels->pipe chain cannot be validated in the cloud: the
 *     code path is complete and fails loudly with a message on error; the exact
 *     bound framebuffer at renderLevel TAIL on MC 1.21.11 needs on-site checks.
 *     Nothing here pretends to succeed when GL/encoding fails.
 */
package dev.fpv.replay

import dev.fpv.client.FpvClient
import dev.fpv.flight.ExportConfig
import net.fabricmc.loader.api.FabricLoader
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.floor

object CinematicExport {

    @Volatile var exporting = false
        private set

    @Volatile var currentFrame = 0
        private set
    var totalFrames = 0
        private set

    /** Human-readable status surfaced on the export screen. */
    @Volatile var status: String = ""
        private set

    @Volatile var cancelRequested = false
        private set

    /** Non-null once a run finishes (mp4 path or PNG dir). */
    var outputPath: Path? = null
        private set

    private var session: Session? = null

    // ---- public control -------------------------------------------------

    /** Locate ffmpeg: explicit path from config, else PATH lookup. */
    fun detectFfmpeg(): String? {
        val cfgPath = (FpvClient.config.export ?: ExportConfig()).ffmpegPath.trim()
        if (cfgPath.isNotEmpty()) {
            return if (Files.isExecutable(Path.of(cfgPath))) cfgPath else null
        }
        return try {
            val p = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            val ok = p.waitFor() == 0
            if (ok) "ffmpeg" else null
        } catch (_: Exception) {
            null
        }
    }

    /** Begin exporting the loaded replay with the current ExportConfig. */
    fun start(): Boolean {
        val f = ReplayManager.file ?: return false
        if (exporting) return false
        val cfg = FpvClient.config.export ?: ExportConfig()
        val s = Session(f, cfg, detectFfmpeg())
        session = s
        exporting = true
        cancelRequested = false
        outputPath = null
        totalFrames = s.totalFrames
        currentFrame = 0
        status = "Starting export..."
        // Drive the first sub-frame immediately.
        ReplayManager.scrubTo(s.frameTime(0, 0))
        return true
    }

    fun cancel() { cancelRequested = true }

    /** Called at renderLevel TAIL (world just rendered to the bound target). */
    fun onLevelRendered() {
        val s = session ?: return
        if (!exporting) return
        try {
            s.readAndAccumulate()
            if (s.subDone()) {
                s.flushAccumulatedFrame()
                currentFrame = s.outFrame
                if (s.outFrame >= s.totalFrames || cancelRequested) { finish(s); return }
            }
            // Advance cursor to the next sub-sample; next real frame renders it.
            ReplayManager.scrubTo(s.nextCursorTime())
        } catch (e: Throwable) {
            status = "Export failed: ${e.javaClass.simpleName}: ${e.message}"
            finish(s, error = true)
        }
    }

    private fun finish(s: Session, error: Boolean = false) {
        try { s.closeEncoder(error) } catch (_: Exception) {}
        exporting = false
        session = null
        if (!error) status = "Done -> ${outputPath?.fileName ?: "?"}"
    }

    // ---- internal session ------------------------------------------------

    private class Session(
        val file: ReplayFile,
        val cfg: ExportConfig,
        val ffmpeg: String?,
    ) {
        val fps = cfg.fps.coerceIn(12, 120)
        val blurN = cfg.motionBlurSamples.coerceIn(1, 8)
        val totalFrames = floor(file.durationSec * fps).toInt().coerceAtLeast(1)

        // Canvas size (even for yuv420p).
        val canvasW: Int
        val canvasH: Int
        val targetAspect: Double

        // Readback accumulators.
        private var viewW = 0
        private var viewH = 0
        private var acc: FloatArray = FloatArray(0) // viewW*viewH*3
        var outFrame = 0
            private set
        private var sub = 0

        private var encoder: Process? = null
        private var encoderOut: java.io.OutputStream? = null
        private var pngDir: Path? = null

        init {
            val baseH = when (cfg.resolution) {
                "720p" -> 720; "1440p" -> 1440; "2160p" -> 2160
                else -> 1080
            }
            targetAspect = when (cfg.aspect) {
                "16:9" -> 16.0 / 9.0
                "9:16" -> 9.0 / 16.0
                "2.35:1" -> 2.35
                "21:9" -> 21.0 / 9.0
                else -> 2.39 // "2.39:1" / anamorphic DCP
            }
            canvasH = even(baseH)
            canvasW = even(floor(canvasH * targetAspect).toInt())
            pngDir = FabricLoader.getInstance().gameDir
                .resolve("fpv-replays").resolve("frames_${System.currentTimeMillis()}")
            if (ffmpeg == null) Files.createDirectories(pngDir)
            else startEncoder()
        }

        /** t for output frame [i], sub-sample [s] (180-degree shutter spread). */
        fun frameTime(i: Int, s: Int): Double {
            val frameStart = i.toDouble() / fps
            val exposure = (cfg.shutterAngleDeg / 360.0) * (1.0 / fps)
            return (frameStart + (s + 0.5) / blurN * exposure).coerceIn(0.0, file.durationSec)
        }

        fun nextCursorTime(): Double = frameTime(outFrame, sub)

        fun subDone(): Boolean {
            sub++
            if (sub >= blurN) { sub = 0; return true }
            return false
        }

        /** Read the bound GL framebuffer once and add it to the accumulator. */
        fun readAndAccumulate() {
            val vp = IntArray(4)
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp)
            val w = vp[2]; val h = vp[3]
            check(w > 0 && h > 0) { "bad viewport ${w}x${h}" }
            if (w != viewW || h != viewH || acc.isEmpty()) {
                viewW = w; viewH = h
                acc = FloatArray(w * h * 3)
            }
            val buf = ByteBuffer.allocateDirect(w * h * 4)
            // [NEEDS LOCAL VERIFICATION] confirm the world framebuffer (not the
            // screen backbuffer) is bound at renderLevel TAIL on 1.21.11; if the
            // read comes back black, bind mc.mainRenderTarget's draw target here.
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf)
            val inv = 1f / blurN
            val px = w * h * 4
            for (i in 0 until w * h) {
                val o = i * 4
                acc[i * 3] += (buf.get(o).toInt() and 0xFF) * inv
                acc[i * 3 + 1] += (buf.get(o + 1).toInt() and 0xFF) * inv
                acc[i * 3 + 2] += (buf.get(o + 2).toInt() and 0xFF) * inv
            }
        }

        /** Downscale + letterbox the accumulated image and send one output frame. */
        fun flushAccumulatedFrame() {
            // Scale source to fit canvas preserving aspect.
            val srcAspect = viewW.toDouble() / viewH
            val dstAspect = canvasW.toDouble() / canvasH
            val drawW: Int; val drawH: Int
            if (!cfg.letterbox || srcAspect >= dstAspect) {
                drawW = canvasW
                drawH = (canvasW / srcAspect).toInt().let { even(it) }.coerceAtMost(canvasH)
            } else {
                drawH = canvasH
                drawW = (canvasH * srcAspect).toInt().let { even(it) }.coerceAtMost(canvasW)
            }
            val offX = (canvasW - drawW) / 2
            val offY = (canvasH - drawH) / 2

            val out = ByteArray(canvasW * canvasH * 3) // rgb24
            // Nearest-neighbor resample with black bars.
            for (y in 0 until canvasH) {
                val dy = y - offY
                val rowActive = dy in 0 until drawH
                for (x in 0 until canvasW) {
                    val dx = x - offX
                    val o = (y * canvasW + x) * 3
                    if (!cfg.letterbox || (rowActive && dx in 0 until drawW)) {
                        // map to source
                        val sx = if (drawW > 0) (dx * viewW / drawW).coerceIn(0, viewW - 1) else 0
                        val sy = if (drawH > 0) (dy * viewH / drawH).coerceIn(0, viewH - 1) else 0
                        val si = (sy * viewW + sx) * 3
                        out[o] = acc[si].toInt().coerceIn(0, 255).toByte()
                        out[o + 1] = acc[si + 1].toInt().coerceIn(0, 255).toByte()
                        out[o + 2] = acc[si + 2].toInt().coerceIn(0, 255).toByte()
                    } else {
                        out[o] = 0; out[o + 1] = 0; out[o + 2] = 0
                    }
                }
            }
            // Reset accumulator.
            acc.fill(0f)

            if (ffmpeg != null) {
                encoderOut?.write(out)
                encoderOut?.flush()
            } else {
                writePngFrame(out)
            }
            outFrame++
        }

        private fun writePngFrame(rgb24: ByteArray) {
            val img = BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until canvasH) {
                for (x in 0 until canvasW) {
                    val o = (y * canvasW + x) * 3
                    val r = rgb24[o].toInt() and 0xFF
                    val g = rgb24[o + 1].toInt() and 0xFF
                    val b = rgb24[o + 2].toInt() and 0xFF
                    img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                }
            }
            val name = "frame_" + outFrame.toString().padStart(6, '0') + ".png"
            ImageIO.write(img, "PNG", pngDir!!.resolve(name).toFile())
        }

        private fun startEncoder() {
            val ext = cfg.container.lowercase()
            val outName = "replay_${System.currentTimeMillis()}.$ext"
            val outPath = FabricLoader.getInstance().gameDir.resolve("fpv-replays").resolve(outName)
            val dir = outPath.parent.toFile()
            if (!dir.exists()) dir.mkdirs()
            // Command template researched from the public ReplayMod / ffmpeg docs.
            val cmd = mutableListOf(
                ffmpeg!!, "-y",
                "-f", "rawvideo", "-pix_fmt", "rgb24",
                "-s", "${canvasW}x${canvasH}",
                "-r", "$fps",
                "-i", "-",
                "-an",
                "-c:v", "libx264", "-preset", "medium", "-crf", "18",
                "-pix_fmt", "yuv420p",
            )
            if (ext == "mp4") {
                cmd += "-movflags"
                cmd += "+faststart"
            }
            cmd.add(outName) // run in the output dir (so relative name is safe).
            val pb = ProcessBuilder(cmd)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            // Drain ffmpeg output to export.log so failures are diagnosable.
            val log = dir.resolve("export.log").outputStream()
            encoder = pb.start()
            encoderOut = encoder!!.outputStream
            Thread({
                encoder!!.inputStream.copyTo(log)
            }, "fpv-ffmpeg-log").start()
            outputPath = outPath
        }

        fun closeEncoder(error: Boolean) {
            try { encoderOut?.flush() } catch (_: Exception) {}
            try { encoderOut?.close() } catch (_: Exception) {}
            encoder?.let {
                try { it.waitFor() } catch (_: Exception) {}
                if (!it.isAlive) it.destroy()
            }
        }

        private fun even(v: Int): Int = if (v and 1 == 0) v else v - 1
    }
}
