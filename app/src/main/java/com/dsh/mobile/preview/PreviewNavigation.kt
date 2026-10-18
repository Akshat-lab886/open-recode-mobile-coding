package com.dsh.mobile.preview

import java.io.File

/**
 * Decides which links a Preview page may follow.
 *
 * The first version returned "block everything", which was safe but wrong: a
 * preview of a real site has internal links (nav, another page in the same
 * folder) and blocking those makes the preview useless for anything that is not
 * a single self-contained file.
 *
 * The rule is simple and testable: follow links that resolve to a real file
 * INSIDE the workspace, block everything else.
 */
object PreviewNavigation {

    /** True when [url] is a file:// URL inside [workspace] that exists. */
    fun allow(url: String?, workspace: File): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("file://")) return false
        val path = url.removePrefix("file://")
        if (path.isBlank()) return false

        // Decode %20 etc. before touching the filesystem; a space in a file name
        // arrives percent-encoded in a href.
        val decoded = try {
            java.net.URLDecoder.decode(path, "UTF-8")
        } catch (_: Exception) {
            return false
        }
        val candidate = File(decoded)
        if (!candidate.isFile) return false

        // Containment check on canonical paths, so neither ".." nor a symlink
        // can walk the preview out of the workspace.
        val root = try {
            workspace.canonicalPath
        } catch (_: Exception) {
            return false
        }
        val target = try {
            candidate.canonicalPath
        } catch (_: Exception) {
            return false
        }
        return target == root || target.startsWith(root + File.separator)
    }
}
