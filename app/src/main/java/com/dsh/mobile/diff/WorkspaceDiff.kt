package com.dsh.mobile.diff

import java.io.File

/**
 * What we captured about one workspace file.
 *
 * [content] is null when the file is larger than the capture cap or is not
 * decodable text; [tooLarge] distinguishes "we chose not to store it" from
 * "we read it and it was empty", because the first still lets the UI say
 * "modified" while the second can show a real diff.
 */
data class FileSnapshot(
    val content: String?,
    val sizeBytes: Long,
    val tooLarge: Boolean = false,
)

/** The pure result of comparing a baseline against the workspace as it is now. */
data class WorkspaceChanges(
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
    val unchanged: List<String>,
) {
    /** True when the agent changed nothing - the Diffs tab's empty state. */
    val isEmpty: Boolean get() = added.isEmpty() && modified.isEmpty() && deleted.isEmpty()

    /** Total files touched, i.e. what a "N files changed" header shows. */
    val changedCount: Int get() = added.size + modified.size + deleted.size
}

/**
 * Workspace snapshotting and comparison.
 *
 * Android has no git, so "what did the agent change?" is answered by keeping a
 * baseline copy of the workspace and diffing against it. [scan] builds the
 * snapshot, [compare] is the pure comparison the tests pin down.
 */
object WorkspaceDiff {

    /** Files above this are listed as changed but never held in memory. */
    const val MAX_CAPTURE_BYTES: Long = 256L * 1024

    /** Stop walking past this depth; a runaway tree must not hang the UI. */
    const val MAX_DEPTH: Int = 12

    /**
     * Directories never scanned. These are the agent runtime's own guts and
     * build output: gigabytes, never user work, and scanning them would make
     * the tab appear to hang.
     */
    val SKIP_DIRS: Set<String> = setOf(
        ".git", "node_modules", "build", ".gradle", ".idea", ".vscode",
        ".cxx", ".gradle-home", "dsh-runtime", ".next", "dist", "__pycache__",
        ".venv", "venv", ".cache", "target", ".DS_Store",
    )

    /** True if [name] is a directory we should not descend into. */
    fun shouldSkipDir(name: String): Boolean =
        name in SKIP_DIRS || name.startsWith(".") && name.length > 1 && name !in setOf(".", "..")

    /**
     * Walks [root] and returns path -> snapshot, keyed by the path RELATIVE to
     * [root] so the baseline stays valid if the app's data directory moves
     * (the app's install path is not stable across reinstalls).
     */
    fun scan(root: File, maxCaptureBytes: Long = MAX_CAPTURE_BYTES): Map<String, FileSnapshot> {
        val out = LinkedHashMap<String, FileSnapshot>()
        if (!root.isDirectory) return out
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) continue
            val children = dir.listFiles() ?: continue
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) {
                    if (!shouldSkipDir(child.name)) stack.addLast(child to depth + 1)
                } else if (child.isFile) {
                    val size = child.length()
                    if (size > maxCaptureBytes) {
                        out[relativePath(root, child)] = FileSnapshot(null, size, tooLarge = true)
                    } else {
                        val text = try {
                            child.readText()
                        } catch (_: Exception) {
                            null // unreadable or binary - listed but not diffable
                        }
                        out[relativePath(root, child)] = FileSnapshot(text, size)
                    }
                }
            }
        }
        return out
    }

    /** [file]'s path relative to [root], with '/' separators. */
    fun relativePath(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart('/')

    /**
     * Compares a [baseline] snapshot to the [current] one.
     *
     * "Modified" is content inequality, NOT mtime: a touched-but-unchanged file
     * is not a change the user cares about, and comparing mtimes would report
     * every file an agent merely read.
     */
    fun compare(
        baseline: Map<String, FileSnapshot>,
        current: Map<String, FileSnapshot>,
    ): WorkspaceChanges {
        val added = current.keys.filter { it !in baseline }.sorted()
        val deleted = baseline.keys.filter { it !in current }.sorted()
        val modified = ArrayList<String>()
        val unchanged = ArrayList<String>()
        for (path in current.keys.sorted()) {
            if (path !in baseline) continue
            val b = baseline.getValue(path)
            val c = current.getValue(path)
            if (b.tooLarge || c.tooLarge) {
                // We never held the bytes, so we cannot tell. Treat a size
                // change as modified and an identical size as unchanged.
                if (b.sizeBytes != c.sizeBytes) modified += path else unchanged += path
            } else if (b.content != c.content) {
                modified += path
            } else {
                unchanged += path
            }
        }
        return WorkspaceChanges(added, modified, deleted, unchanged)
    }

    /** The line diff for one changed file, or null if not diffable. */
    fun diffFile(baseline: FileSnapshot?, current: FileSnapshot?): List<DiffLine>? {
        val old = baseline?.content?.let { LineDiff.lines(it) } ?: emptyList()
        val new = current?.content?.let { LineDiff.lines(it) } ?: emptyList()
        if (baseline?.tooLarge == true || current?.tooLarge == true) return null
        return LineDiff.diff(old, new)
    }
}
