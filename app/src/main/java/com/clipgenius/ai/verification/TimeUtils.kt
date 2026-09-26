package com.clipgenius.ai.verification

/**
 * Deterministic timestamp parser and formatter.
 * Handles "MM:SS", "HH:MM:SS", and decimal variants.
 * Strictly guarantees: Invalid strings return null, NEVER crash.
 */
object TimeUtils {

    /**
     * Parses a timestamp string formatted as "MM:SS" or "HH:MM:SS" into milliseconds.
     * Supports optional fractional seconds (e.g., "01:23.456").
     * Returns null for any invalid, negative, or malformed time representation.
     */
    fun parseToMs(timeStr: String?): Long? {
        if (timeStr.isNullOrBlank()) return null
        val trimmed = timeStr.trim()
        val parts = trimmed.split(":")

        return try {
            when (parts.size) {
                2 -> {
                    // MM:SS or MM:SS.sss
                    val mins = parts[0].toLongOrNull() ?: return null
                    if (mins < 0) return null

                    val secParts = parts[1].split(".")
                    val secs = secParts[0].toLongOrNull() ?: return null
                    if (secs < 0 || secs >= 60) return null

                    val millis = if (secParts.size > 1) {
                        secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: return null
                    } else 0L

                    (mins * 60 + secs) * 1000L + millis
                }
                3 -> {
                    // HH:MM:SS or HH:MM:SS.sss
                    val hours = parts[0].toLongOrNull() ?: return null
                    val mins = parts[1].toLongOrNull() ?: return null
                    if (hours < 0 || mins < 0 || mins >= 60) return null

                    val secParts = parts[2].split(".")
                    val secs = secParts[0].toLongOrNull() ?: return null
                    if (secs < 0 || secs >= 60) return null

                    val millis = if (secParts.size > 1) {
                        secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: return null
                    } else 0L

                    ((hours * 3600 + mins * 60 + secs) * 1000L) + millis
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Formats milliseconds into "MM:SS" (or "HH:MM:SS" if >= 1 hour or forceHours is true).
     * Handles null and negative values gracefully.
     */
    fun formatMs(ms: Long?, forceHours: Boolean = false): String {
        if (ms == null || ms < 0L) return "00:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0 || forceHours) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
