package com.dsh.mobile.diff

import android.util.Log
import java.io.File
import java.util.Base64

/**
 * Persists a workspace baseline snapshot so the Diffs tab can answer "what
 * changed since then?" across app restarts.
 *
 * Format is one record per line: base64(path) TAB flag TAB size TAB
 * base64(content). Base64 on both the path and the content is deliberate:
 * file contents contain newlines, tabs and non-UTF8 bytes, all of which would
 * corrupt a delimiter-separated format.
 *
 * A corrupt or partial file is treated as "no baseline" rather than an error:
 * losing the baseline degrades the Diffs tab to an empty state, but throwing
 * here would take down the whole app on a bad write.
 */
class WorkspaceBaselineStore(private val file: File) {

    private companion object {
        const val TAG = "WorkspaceBaseline"
        const val FLAG_LARGE = "L"
        const val FLAG_TEXT = "T"
    }

    fun exists(): Boolean = file.isFile

    /** Writes [snapshot] atomically-ish: a temp file, then a rename. */
    fun save(snapshot: Map<String, FileSnapshot>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { out ->
                for ((path, snap) in snapshot) {
                    val b64Path = Base64.getEncoder().encodeToString(path.toByteArray())
                    val flag = if (snap.tooLarge) FLAG_LARGE else FLAG_TEXT
                    val b64Body =
                        if (snap.tooLarge) "" else Base64.getEncoder().encodeToString((snap.content ?: "").toByteArray())
                    out.write("$b64Path\t$flag\t${snap.sizeBytes}\t$b64Body\n")
                }
            }
            if (!tmp.renameTo(file)) {
                // renameTo can fail across filesystems; fall back to a direct copy.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "baseline save failed", e)
        }
    }

    /** Loads the baseline, or an empty map when absent or unreadable. */
    fun load(): Map<String, FileSnapshot> {
        if (!file.isFile) return emptyMap()
        val out = LinkedHashMap<String, FileSnapshot>()
        try {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parts = line.split("\t", limit = 4)
                if (parts.size < 3) return@forEachLine
                val path = String(Base64.getDecoder().decode(parts[0]))
                val large = parts[1] == FLAG_LARGE
                val size = parts[2].toLongOrNull() ?: 0L
                val content =
                    if (large || parts.size < 4) null
                    else String(Base64.getDecoder().decode(parts[3]))
                out[path] = FileSnapshot(content, size, tooLarge = large)
            }
        } catch (e: Exception) {
            Log.w(TAG, "baseline load failed, treating as empty", e)
            return emptyMap()
        }
        return out
    }

    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}
