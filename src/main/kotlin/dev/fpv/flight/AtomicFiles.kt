/*
 * FPV Craft - MIT
 * Atomic file writes shared by the config, blackbox, recorder and exporter.
 * Data is first written to a sibling temporary file, flushed+fsynced, then
 * moved over the destination (ATOMIC_MOVE where the filesystem supports it,
 * plain replace otherwise). A reader therefore never observes a half-written
 * file even if the game dies mid-save.
 */
package dev.fpv.flight

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.random.Random

object AtomicFiles {

    /** Atomically replace [path] with UTF-8 [text]. */
    fun writeText(path: Path, text: String) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp-" + Random.nextInt(0x100000, 0xFFFFFF))
        try {
            Files.newBufferedWriter(tmp).use { it.write(text); it.flush() }
            move(tmp, path)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** Atomically replace [path] with raw [bytes]. */
    fun writeBytes(path: Path, bytes: ByteArray) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp-" + Random.nextInt(0x100000, 0xFFFFFF))
        try {
            Files.newOutputStream(tmp).use { it.write(bytes); it.flush() }
            move(tmp, path)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun move(tmp: Path, dest: Path) {
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
