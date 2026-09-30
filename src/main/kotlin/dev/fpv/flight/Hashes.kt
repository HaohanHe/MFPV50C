/*
 * FPV Craft - MIT
 * Small hashing helper for the blackbox: a stable SHA-256 fingerprint of the
 * active config, so a telemetry/blackbox file records exactly which settings
 * were in use. Standard library only.
 */
package dev.fpv.flight

import java.security.MessageDigest

object Hashes {
    fun sha256Hex(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
