package com.dsh.mobile.diagnostics

import java.io.File

/**
 * Files that must never be read into a diagnostics bundle.
 *
 * Enforced centrally rather than at call sites so that adding a new log source
 * cannot accidentally start exporting credentials. `~/.dsh/.credentials.yaml`
 * holds real provider credentials; a user who pastes an exported log into a
 * public bug report would leak them.
 */
object SensitivePaths {

    private val DENIED_NAME_MARKERS = listOf(
        "credential", "secret", "password", "passwd", "token", "apikey", "api_key",
    )

    private val DENIED_SUFFIXES = listOf(".key", ".pem", ".p12", ".pfx", ".jks", ".keystore")

    /**
     * True when [file] must not be tailed or exported.
     *
     * Rejects dotfiles (`.credentials.yaml`, `.anonymous-user-id`), credential-ish
     * names, and private-key material.
     */
    fun isDenied(file: File): Boolean {
        val name = file.name.lowercase()
        if (name.startsWith(".")) return true
        if (DENIED_NAME_MARKERS.any { name.contains(it) }) return true
        return DENIED_SUFFIXES.any { name.endsWith(it) }
    }

    /** Convenience for a plain path string (relative paths allowed). */
    fun isDeniedPath(path: String): Boolean = isDenied(File(path))
}