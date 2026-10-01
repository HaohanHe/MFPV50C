/*
 * FPV Craft - MIT
 * Pure, headless-testable ffmpeg command construction: token substitution + a hard
 * whitelist on the output filename so a hostile replay/craft name can never smuggle
 * extra command-line flags into the ffmpeg process.
 */
package dev.fpv.replay

object FfmpegCommand {

    /** Allowed characters in an output filename (the rest become '_'). */
    private val ALLOWED = Regex("[^A-Za-z0-9._-]")

    /** Sanitize a proposed file name to [A-Za-z0-9._-] only; no path separators, no '-'. */
    fun sanitizeFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return ALLOWED.replace(base, "_").trim('.').ifBlank { "replay_out" }
    }

    /**
     * Build the argv list from the user [template], substituting %WIDTH% %HEIGHT% %FPS%
     * %PIXELFMT% %FILENAME%. The filename token is inserted as its OWN argv element
     * (never concatenated), and is sanitized. Returns argv[0]=ffmpeg as the first element.
     */
    fun build(
        ffmpeg: String,
        template: String,
        width: Int, height: Int, fps: Int, pixFmt: String,
        filename: String,
    ): List<String> {
        val safeName = sanitizeFilename(filename)
        val filled = template
            .replace("%WIDTH%", width.toString())
            .replace("%HEIGHT%", height.toString())
            .replace("%FPS%", fps.toString())
            .replace("%PIXELFMT%", pixFmt)
        // Split on whitespace; keep %FILENAME% as its own element. Any literal spaces that
        // belong inside a token are not expected in the template, so plain split is safe.
        val tokens = filled.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        val out = ArrayList<String>()
        out += ffmpeg
        for (tok in tokens) {
            if (tok == "%FILENAME%") out += safeName else out += tok
        }
        // Guarantee the filename is present exactly once as a standalone arg.
        return out
    }
}
