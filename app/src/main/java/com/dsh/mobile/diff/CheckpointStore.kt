package com.dsh.mobile.diff

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Restorable workspace checkpoints, stored content-addressed.
 *
 * The existing [WorkspaceBaselineStore] answers "what changed?" but cannot answer
 * "put it back". It holds one flat file of every path's full content, rewritten
 * in its entirety on every save, so:
 *
 *  - it cannot restore a deleted file (the old content is there, but nothing
 *    records that it existed at a given point versus being added since),
 *  - a second checkpoint duplicates every unchanged file's bytes,
 *  - one bad write loses every checkpoint at once.
 *
 * This store separates the two concerns:
 *
 * ```
 * checkpoints/
 *   objects/<sha256>          file contents, written once, shared by all checkpoints
 *   trees/<id>.txt            one manifest: path, size, hash (or "large"/"binary")
 *   latest.txt                id of the most recent checkpoint
 * ```
 *
 * Content addressing is what makes this cheap: saving a checkpoint after a
 * one-file edit writes ONE new object and one small manifest, because every
 * unchanged file hashes to an object that already exists. It is also what makes
 * restore safe - an object is immutable, so restoring cannot corrupt another
 * checkpoint that shares it.
 *
 * Deliberately NOT git: Android has no git binary and the reference app bundles
 * a PRoot userspace to get one. This is the honest subset that needs no
 * dependency, and it is a store the user can delete a directory to clear.
 */
class CheckpointStore(private val root: File) {

    private val objects: File get() = File(root, "objects")
    private val trees: File get() = File(root, "trees")
    private val latestFile: File get() = File(root, "latest.txt")

    /** One saved checkpoint. */
    data class Checkpoint(
        val id: String,
        val createdAtMillis: Long,
        val fileCount: Int,
        /** Files that could not be captured (too large or binary). */
        val skippedCount: Int,
    )

    /** One entry in a checkpoint's manifest. */
    data class Entry(
        val path: String,
        val sizeBytes: Long,
        /** Content hash, or null when the content was not stored. */
        val hash: String?,
        /** Why the content was not stored, when [hash] is null. */
        val skippedReason: String? = null,
    )

    /**
     * Saves a checkpoint of [snapshot]. Returns it, or null on failure.
     *
     * The id is derived from the CONTENT of the manifest, so saving an unchanged
     * workspace twice produces the same id and writes nothing new. That makes a
     * checkpoint on every agent turn cheap enough to actually do.
     */
    fun save(snapshot: Map<String, FileSnapshot>, nowMillis: Long = System.currentTimeMillis()): Checkpoint? {
        return try {
            objects.mkdirs()
            trees.mkdirs()
            val entries = ArrayList<Entry>(snapshot.size)
            for ((path, snap) in snapshot.toSortedMap()) {
                val text = snap.content
                if (text == null) {
                    entries += Entry(path, snap.sizeBytes, null, if (snap.tooLarge) "large" else "binary")
                    continue
                }
                val hash = sha256(text.toByteArray())
                val target = File(objects, hash)
                // Content-addressed: the object either exists or is written once.
                if (!target.isFile) writeAtomically(target, text.toByteArray())
                entries += Entry(path, snap.sizeBytes, hash)
            }
            val manifest = buildString {
                for (e in entries) {
                    append(e.path.replace("\t", " ")).append('\t')
                    append(e.sizeBytes).append('\t')
                    append(e.hash ?: "-").append('\t')
                    append(e.skippedReason ?: "-").append('\n')
                }
            }
            val id = sha256(manifest.toByteArray()).take(16)
            val treeFile = File(trees, "$id.txt")
            if (!treeFile.isFile) writeAtomically(treeFile, manifest.toByteArray())
            writeAtomically(latestFile, id.toByteArray())
            Checkpoint(
                id = id,
                createdAtMillis = nowMillis,
                fileCount = entries.count { it.hash != null },
                skippedCount = entries.count { it.hash == null },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "checkpoint save failed", t)
            null
        }
    }

    /** The newest checkpoint, or null when none exists. */
    fun latest(): Checkpoint? {
        val id = runCatching { latestFile.readText().trim() }.getOrNull()
        return if (id.isNullOrEmpty()) null else load(id)
    }

    /** Reads a checkpoint's manifest, or null when absent or unreadable. */
    fun load(id: String): Checkpoint? {
        val treeFile = File(trees, "$id.txt")
        if (!treeFile.isFile) return null
        return try {
            val entries = treeFile.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val hash = parts[2].takeIf { it != "-" }
                Entry(
                    path = parts[0],
                    sizeBytes = parts[1].toLongOrNull() ?: 0L,
                    hash = hash,
                    skippedReason = parts.getOrNull(3)?.takeIf { it != "-" },
                )
            }
            Checkpoint(
                id = id,
                createdAtMillis = treeFile.lastModified(),
                fileCount = entries.count { it.hash != null },
                skippedCount = entries.count { it.hash == null },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "checkpoint load failed", t)
            null
        }
    }

    /** All checkpoint ids, newest first. */
    fun list(): List<String> =
        trees.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name.removeSuffix(".txt") }
            ?: emptyList()

    /** The manifest entries for [id]. */
    fun entries(id: String): List<Entry> {
        val treeFile = File(trees, "$id.txt")
        if (!treeFile.isFile) return emptyList()
        return runCatching {
            treeFile.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                Entry(parts[0], parts[1].toLongOrNull() ?: 0L, parts[2].takeIf { it != "-" })
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Restores [id] into [workspace].
     *
     * Files added since the checkpoint are REMOVED, because "restore" means the
     * workspace looks as it did, not "add back what was deleted". Files whose
     * content was never captured are left alone rather than deleted: the store
     * cannot restore them, so destroying them would be worse than leaving them.
     *
     * Returns the paths that were written, or an empty list on failure.
     */
    fun restore(id: String, workspace: File): List<String> {
        val entries = entries(id)
        if (entries.isEmpty()) return emptyList()
        val wanted = entries.associateBy { it.path }
        val written = ArrayList<String>()
        return try {
            for (entry in entries) {
                val hash = entry.hash ?: continue
                val source = File(objects, hash)
                if (!source.isFile) continue
                val target = File(workspace, entry.path)
                target.parentFile?.mkdirs()
                writeAtomically(target, source.readBytes())
                written += entry.path
            }
            // Remove files that did not exist at this checkpoint, but only where
            // the store actually knows the file's state.
            WorkspaceDiff.scanPaths(workspace).forEach { path ->
                if (path !in wanted) {
                    runCatching { File(workspace, path).delete() }
                }
            }
            written
        } catch (t: Throwable) {
            Log.w(TAG, "checkpoint restore failed", t)
            written
        }
    }

    /** Deletes everything this store owns. */
    fun clear() {
        runCatching { root.deleteRecursively() }
    }

    /** Total bytes held by stored objects; the cost of the checkpoint history. */
    fun diskUsageBytes(): Long =
        objects.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Drops checkpoint manifests beyond [keep], and any object no remaining
     * manifest references.
     *
     * Garbage collection matters here: objects are shared, so they cannot be
     * deleted with the checkpoint that introduced them.
     */
    fun prune(keep: Int) {
        val ids = list()
        if (ids.size <= keep) return
        val doomed = ids.drop(keep)
        val surviving = ids.take(keep)
        doomed.forEach { runCatching { File(trees, "$it.txt").delete() } }
        val referenced = HashSet<String>()
        for (id in surviving) entries(id).forEach { e -> e.hash?.let { referenced += it } }
        objects.listFiles()?.forEach { obj ->
            if (obj.name !in referenced) runCatching { obj.delete() }
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; copy and drop the temp.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "CheckpointStore"

        /** How many checkpoints to keep by default. */
        const val DEFAULT_KEEP: Int = 20

        /** Stable content hash. SHA-256 is overkill for dedup but costs nothing here. */
        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append("%02x".format(b))
            return sb.toString()
        }
    }
}
