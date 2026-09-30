/*
 * FPV Craft - MIT
 * Config safety engine, decoupled from FabricLoader so it can be exercised by a
 * headless JVM self-test against any root directory. Implements:
 *   - parse + migrate + sanity check
 *   - timestamped, rotated backups before each overwrite
 *   - quarantine (never delete) of an unparseable config
 *   - robust load: main -> newest good backup -> safe defaults, with a notice.
 * Bad files are never deleted and recovery is never silent.
 */
package dev.fpv.flight

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Comparator

object ConfigSafety {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun mainPath(root: Path): Path = root.resolve("fpvcraft.json")
    fun backupDir(root: Path): Path = root.resolve("fpv/backups")
    fun quarantineDir(root: Path): Path = root.resolve("fpv/quarantine")

    /** Parse + migrate + sanity-check one file; null when it cannot be trusted. */
    fun tryParse(p: Path): FpvConfig? = try {
        val c = gson.fromJson(Files.readString(p), FpvConfig::class.java) ?: return null
        c.migrate()
        if (c.isSane()) c else null
    } catch (_: Exception) {
        null
    }

    /** Copy the current main file into a timestamped backup, then rotate to N. */
    fun rotateBackup(root: Path) {
        val p = mainPath(root)
        if (!Files.exists(p)) return
        val dir = backupDir(root); Files.createDirectories(dir)
        val b = dir.resolve("fpvcraft-${LocalDateTime.now().format(STAMP)}.json")
        AtomicFiles.writeText(b, Files.readString(p))
        val all = Files.list(dir).use { s ->
            s.filter { it.fileName.toString().startsWith("fpvcraft-") }
                .sorted(Comparator.reverseOrder()).toList()
        }
        all.drop(Defaults.CONFIG_BACKUP_KEEP).forEach { runCatching { Files.delete(it) } }
    }

    /** Move a bad main file aside (never delete it) for later inspection. */
    fun quarantine(root: Path) {
        val p = mainPath(root)
        if (!Files.exists(p)) return
        val dir = quarantineDir(root); Files.createDirectories(dir)
        runCatching {
            Files.move(p, dir.resolve("fpvcraft-${LocalDateTime.now().format(STAMP)}.json"),
                StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Backups newest-first (the name encodes the timestamp). */
    fun listBackups(root: Path): List<Path> {
        val dir = backupDir(root)
        if (!Files.exists(dir)) return emptyList()
        return Files.list(dir).use { s ->
            s.filter { it.fileName.toString().startsWith("fpvcraft-") }
                .sorted(Comparator.reverseOrder()).toList()
        }
    }

    /** Result of [robustLoad]: the config plus a non-empty notice when recovery happened. */
    data class LoadResult(val config: FpvConfig, val notice: String)

    /**
     * Robust load for [root]. Main file when good; else quarantine it and fall
     * back to the newest parseable backup; only then built-in safe defaults.
     */
    fun robustLoad(root: Path): LoadResult {
        val p = mainPath(root)
        if (Files.exists(p)) {
            val main = tryParse(p)
            if (main != null) return LoadResult(main, "")
            quarantine(root)
        }
        for (b in listBackups(root)) {
            val c = tryParse(b)
            if (c != null) return LoadResult(
                c, "配置已回退到备份 ${b.fileName}，原文件已隔离"
            )
        }
        val hadMain = Files.exists(p)
        return LoadResult(
            FpvConfig(),
            if (hadMain) "未找到可用配置，已使用安全默认，原文件已隔离" else "",
        )
    }
}
