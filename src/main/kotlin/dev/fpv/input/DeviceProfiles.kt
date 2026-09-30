/*
 * FPV Craft - MIT
 * Builds a stable fingerprint for the attached transmitter and manages the set
 * of persisted per-device profiles. GLFW gives us name + GUID; on Windows the
 * GUID encodes VID/PID (little-endian at byte offsets 4 and 8) when present.
 */
package dev.fpv.input

object DeviceProfiles {

    /** Stable key for the current device. */
    fun fingerprintOf(name: String, guid: String): String {
        val (vid, pid) = vidPid(guid)
        val vidPart = if (vid != 0) "%04x" else "????"
        val pidPart = if (pid != 0) "%04x" else "????"
        return "$vidPart:$pidPart|$name"
    }

    /**
     * Parse VID/PID out of a GLFW joystick GUID hex string. Returns (0,0) when
     * the GUID does not follow the Windows HID layout (best effort, never throws).
     */
    fun vidPid(guid: String): Pair<Int, Int> {
        val h = guid.trim()
        if (h.length < 16) return 0 to 0
        return try {
            // Bytes 4..7 = VID, 8..11 = PID, little-endian within the 32-bit word.
            val vidWord = h.substring(8, 16).toLong(16)
            val pidWord = h.substring(16, 24).toLong(16)
            (vidWord.toInt() and 0xFFFF) to (pidWord.toInt() and 0xFFFF)
        } catch (e: NumberFormatException) {
            0 to 0
        }
    }
}
