/*
 * FPV Craft - MIT
 * Local track storage. Tracks are plain JSON files in
 * <gameDir>/fpv-tracks/<name>.json and can be shared directly.
 */
package dev.fpv.race

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object TrackStore {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun dir(): Path = FabricLoader.getInstance().gameDir.resolve("fpv-tracks")

    /** Sanitise a user-supplied name into a safe file stem. */
    fun safeName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_")
        return cleaned.ifBlank { "track" }.take(48)
    }

    private fun fileFor(name: String): Path = dir().resolve("${safeName(name)}.json")

    fun listTracks(): List<String> {
        val d = dir()
        if (!Files.isDirectory(d)) return emptyList()
        return Files.list(d)
            .filter { it.toString().endsWith(".json") }
            .map { it.fileName.toString().removeSuffix(".json") }
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }

    fun save(doc: TrackDoc): Boolean = try {
        Files.createDirectories(dir())
        saveTo(fileFor(doc.name), doc)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Serialize [doc] and atomically write it to [path] (sibling temp file +
     * atomic move) via the shared [dev.fpv.flight.AtomicFiles]. A reader can
     * never observe a half-written track even if the process dies mid-save, and
     * a failed write leaves the previous file intact rather than truncating it.
     * Split off from [save] so it is testable headless without FabricLoader.
     */
    @JvmStatic
    fun saveTo(path: Path, doc: TrackDoc) {
        dev.fpv.flight.AtomicFiles.writeText(path, gson.toJson(doc))
    }

    fun load(name: String): TrackDoc? = try {
        val p = fileFor(name)
        if (!Files.exists(p)) null
        else gson.fromJson(Files.readString(p), TrackDoc::class.java)
    } catch (e: Exception) {
        null
    }

    fun delete(name: String): Boolean = try {
        Files.deleteIfExists(fileFor(name))
    } catch (e: Exception) {
        false
    }

    /** Absolute path of a track file (for the "share" affordance in logs/UI). */
    fun pathOf(name: String): Path = fileFor(name).toAbsolutePath().normalize()
}
