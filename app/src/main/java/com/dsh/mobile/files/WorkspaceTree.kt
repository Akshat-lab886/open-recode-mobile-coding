package com.dsh.mobile.files

import com.dsh.mobile.diff.WorkspaceDiff
import java.io.File

/** One row in the file browser. */
data class FileEntry(
    val name: String,
    /** Path relative to the workspace root; '' means the root itself. */
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/** A directory's children plus where to go back up to. */
data class DirListing(
    /** Parent path relative to the root, or null when already at the root. */
    val parentPath: String?,
    val dirPath: String,
    val entries: List<FileEntry>,
)

/**
 * Directory listing for the Files tab.
 *
 * Reuses [WorkspaceDiff.shouldSkipDir] so the browser and the diff agree about
 * what is noise: if the Diffs tab never shows node_modules, the Files tab
 * should not offer to open it either.
 */
object WorkspaceTree {

    /** Cap on rows so a 50k-entry directory cannot hang the list. */
    const val MAX_ENTRIES: Int = 500

    /** Text files up to this size can be previewed in the browser. */
    const val MAX_PREVIEW_BYTES: Long = 512L * 1024

    /**
     * Lists [relativeDir] inside [root].
     *
     * Returns an EMPTY listing (not an exception) for a missing or unreadable
     * directory, because the user navigating up out of a folder the agent just
     * deleted should see an empty pane, not a crash.
     */
    fun list(root: File, relativeDir: String = "", maxEntries: Int = MAX_ENTRIES): DirListing {
        val dir = if (relativeDir.isBlank()) root else File(root, relativeDir)
        if (!dir.isDirectory) {
            return DirListing(parentOf(relativeDir), relativeDir, emptyList())
        }
        val children = dir.listFiles() ?: return DirListing(parentOf(relativeDir), relativeDir, emptyList())

        val entries = children
            .filter { !(it.isDirectory && WorkspaceDiff.shouldSkipDir(it.name)) }
            .sortedWith(
                // Directories first, then case-insensitive by name. A plain
                // alphabetical sort buries every folder under the files, which
                // is backwards for a browser.
                compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            .take(maxEntries)
            .map { child ->
                FileEntry(
                    name = child.name,
                    path = if (relativeDir.isBlank()) child.name else "$relativeDir/${child.name}",
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isDirectory) 0L else child.length(),
                    modifiedAt = child.lastModified(),
                )
            }
        return DirListing(parentOf(relativeDir), relativeDir, entries)
    }

    /**
     * The parent of a workspace-relative path, or null at the root.
     *
     * Written by hand rather than with substring/split so the root case is
     * explicit: "a/b" -> "a", "a" -> "", "" -> null.
     */
    fun parentOf(relativeDir: String): String? = when {
        relativeDir.isBlank() -> null
        !relativeDir.contains('/') -> ""
        else -> relativeDir.substringBeforeLast('/')
    }

    /** Breadcrumb segments for a path: ["src", "main"] for "src/main". */
    fun crumbs(relativeDir: String): List<String> =
        if (relativeDir.isBlank()) emptyList() else relativeDir.split('/').filter { it.isNotEmpty() }

    /** Resolves a workspace-relative path, refusing anything that escapes the root. */
    fun resolve(root: File, relativePath: String): File? {
        val candidate = File(root, relativePath)
        // Path traversal guard: ".." must never walk above the workspace.
        val normalized = candidate.canonicalPath
        val rootPath = root.canonicalPath
        return if (normalized == rootPath || normalized.startsWith(rootPath + File.separator)) candidate else null
    }

    /** Reads a text preview, or null when the file is missing, huge or binary. */
    fun preview(file: File, maxBytes: Long = MAX_PREVIEW_BYTES): String? {
        if (!file.isFile || file.length() > maxBytes) return null
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        // A NUL byte means binary; rendering it as text produces mojibake.
        if (bytes.any { it == 0.toByte() }) return null
        return String(bytes)
    }

    /** "1.2 KB" style label; null-ish sizes render as a dash rather than "0 B". */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
