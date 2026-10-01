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
import com.mojang.blaze3d.opengl.GlTextureView
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
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

    /** Aspect ratio of a named aspect string (data-driven; defaults to 2.39:1). */
    fun aspectOf(aspect: String): Double = when (aspect) {
        "16:9" -> 16.0 / 9.0
        "9:16" -> 9.0 / 16.0
        "2.35:1" -> 2.35
        "21:9" -> 21.0 / 9.0
        "4:5" -> 4.0 / 5.0
        "1:1" -> 1.0
        else -> 2.39
    }

    /** Even canvas (w,h) for a named resolution + aspect (yuv420p-safe). Headless-testable. */
    fun canvasFor(resolution: String, aspect: String): Pair<Int, Int> {
        val baseH = when (resolution) {
            "720p" -> 720; "1440p" -> 1440; "2160p" -> 2160
            else -> 1080
        }
        var h = baseH; if (h and 1 == 1) h -= 1
        var w = floor(h * aspectOf(aspect)).toInt(); if (w and 1 == 1) w -= 1
        return w to h
    }

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
        val f = ReplayManager.file ?: run {
            status = "No replay loaded"
            return false
        }
        if (exporting) return false
        val cfg = FpvClient.config.export ?: ExportConfig()
        val s = try {
            Session(f, cfg, detectFfmpeg())
        } catch (e: Throwable) {
            // e.g. ffmpegPath points at a non-executable / process spawn failure.
            // Report loudly instead of letting the exception escape into the GUI
            // button callback and pretending the run started.
            status = "Export init failed: ${e.javaClass.simpleName}: ${e.message}"
            return false
        }
        session = s
        exporting = true
        cancelRequested = false
        totalFrames = s.totalFrames
        outputPath = s.resolvedOutput
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
        // closeEncoder returns the ffmpeg process exit code (-1 if we never spawned
        // one / an exception was already in flight). A nonzero code means the
        // mp4/mkv is corrupt or missing: surface it instead of printing "Done".
        val exitCode = try { s.closeEncoder(error) } catch (_: Exception) { -1 }
        exporting = false
        session = null
        when {
            error -> Unit // status already set by the caller
            exitCode != 0 ->
                status = "Encode failed (ffmpeg exit $exitCode); see export.log next to the output"
            else -> status = "Done -> ${outputPath?.fileName ?: "?"}"
        }
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

        /** Lazily-created READ-only FBO that we attach the main color texture to. */
        private var readFbo = 0

        /** Where the run lands: .mp4/.mkv (ffmpeg) or a PNG frame dir (fallback). */
        lateinit var resolvedOutput: Path
            private set

        init {
            val baseH = when (cfg.resolution) {
                "720p" -> 720; "1440p" -> 1440; "2160p" -> 2160
                else -> 1080
            }
            targetAspect = aspectOf(cfg.aspect)
            canvasH = even(baseH)
            canvasW = even(floor(canvasH * targetAspect).toInt())
            pngDir = FabricLoader.getInstance().gameDir
                .resolve("fpv-replays").resolve("frames_${System.currentTimeMillis()}")
            if (ffmpeg == null) {
                Files.createDirectories(pngDir)
                resolvedOutput = pngDir!!
            } else {
                startEncoder()
            }
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

        /**
         * Read the VANILLA MAIN framebuffer color attachment (the texture the
         * world was just rendered into at renderLevel TAIL, before the GUI) and
         * add it to the accumulator. We attach that same GPU texture to our own
         * read-only FBO rather than creating a new render target (which nothing
         * would draw into -> black frames). On a non-GL backend we fall back to
         * whatever framebuffer is currently bound.
         */
        fun readAndAccumulate() {
            val mainTarget = Minecraft.getInstance().mainRenderTarget
            val w = mainTarget.width
            val h = mainTarget.height
            check(w > 0 && h > 0) { "bad main target ${w}x${h}" }
            if (w != viewW || h != viewH || acc.isEmpty()) {
                viewW = w; viewH = h
                acc = FloatArray(w * h * 3)
            }
            val buf = ByteBuffer.allocateDirect(w * h * 4)

            // Best-effort: resolve the GL texture id of the main color attachment.
            val colorView = mainTarget.getColorTextureView()
            val glTexId = if (colorView is GlTextureView) colorView.texture().glId() else -1

            val prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
            if (glTexId >= 0) {
                if (readFbo == 0) readFbo = GL30.glGenFramebuffers()
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo)
                GL30.glFramebufferTexture2D(
                    GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, glTexId, 0,
                )
                val complete = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER)
                check(complete == GL30.GL_FRAMEBUFFER_COMPLETE) {
                    "main color texture not framebuffer-complete (0x${Integer.toHexString(complete)})"
                }
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0)
            }
            // else: non-GL backend -> read the currently bound target as-is.

            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo)

            val inv = 1f / blurN
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
                        val syTop = if (drawH > 0) (dy * viewH / drawH).coerceIn(0, viewH - 1) else 0
                        // glReadPixels is bottom-up; flip to top-down for the image.
                        val sy = viewH - 1 - syTop
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
                log.close()
            }, "fpv-ffmpeg-log").start()
            resolvedOutput = outPath
        }

        /** Close stdin, wait for ffmpeg, and return its exit code (0 = clean). */
        fun closeEncoder(error: Boolean): Int {
            try { encoderOut?.flush() } catch (_: Exception) {}
            try { encoderOut?.close() } catch (_: Exception) {}
            val proc = encoder ?: return 0
            return try {
                proc.waitFor()
                if (!error && CinematicExport.cancelRequested) 0 else proc.exitValue()
            } catch (_: Exception) {
                proc.destroy()
                -1
            }
        }

        private fun even(v: Int): Int = if (v and 1 == 0) v else v - 1
    }
}
