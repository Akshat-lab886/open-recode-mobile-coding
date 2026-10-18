package com.dsh.mobile.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceDiffTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(path: String, text: String): File =
        File(tmp.root, path).apply { parentFile?.mkdirs(); writeText(text) }

    /** Runs the writes, then returns the workspace root to scan. */
    private fun files(block: () -> Unit): File {
        block()
        return tmp.root
    }

    @Test
    fun `scan reads files keyed by relative path`() {
        write("src/Main.kt", "fun main() {}")
        write("README.md", "# hi")
        val snap = WorkspaceDiff.scan(tmp.root)
        assertEquals(setOf("src/Main.kt", "README.md"), snap.keys)
        assertEquals("fun main() {}", snap.getValue("src/Main.kt").content)
    }

    @Test
    fun `scan skips build and runtime directories`() {
        write("app.kt", "x")
        write("node_modules/pkg/index.js", "y")
        write("build/out.txt", "z")
        write(".git/config", "g")
        val snap = WorkspaceDiff.scan(tmp.root)
        assertEquals(setOf("app.kt"), snap.keys)
    }

    @Test
    fun `hidden directories are skipped but hidden files are not`() {
        write(".env", "SECRET=1")
        write(".cache/x", "y")
        val snap = WorkspaceDiff.scan(tmp.root)
        assertEquals(setOf(".env"), snap.keys)
    }

    @Test
    fun `scan on a missing root returns empty rather than throwing`() {
        assertTrue(WorkspaceDiff.scan(File(tmp.root, "nope")).isEmpty())
    }

    @Test
    fun `oversized files are listed as tooLarge and not read`() {
        val big = File(tmp.root, "big.bin")
        big.writeBytes(ByteArray(2048))
        val snap = WorkspaceDiff.scan(tmp.root, maxCaptureBytes = 1024)
        assertTrue(snap.getValue("big.bin").tooLarge)
        assertNull(snap.getValue("big.bin").content)
        assertEquals(2048L, snap.getValue("big.bin").sizeBytes)
    }

    @Test
    fun `compare finds added modified and deleted`() {
        // Scan twice with real mutations between, so the two snapshots are
        // genuinely different points in time rather than two views of one tree.
        write("keep.txt", "same")
        write("edit.txt", "old")
        write("gone.txt", "bye")
        val baseline = WorkspaceDiff.scan(tmp.root)

        write("edit.txt", "new")
        write("fresh.txt", "hello")
        File(tmp.root, "gone.txt").delete()
        val current = WorkspaceDiff.scan(tmp.root)

        val c = WorkspaceDiff.compare(baseline, current)
        assertEquals(listOf("fresh.txt"), c.added)
        assertEquals(listOf("edit.txt"), c.modified)
        assertEquals(listOf("gone.txt"), c.deleted)
        assertEquals(listOf("keep.txt"), c.unchanged)
        assertFalse(c.isEmpty)
        assertEquals(3, c.changedCount)
    }

    @Test
    fun `identical workspaces report no changes`() {
        val a = WorkspaceDiff.scan(files { write("a.txt", "1") })
        val b = WorkspaceDiff.scan(files { write("a.txt", "1") })
        assertTrue(WorkspaceDiff.compare(a, b).isEmpty)
    }

    @Test
    fun `a touched but unchanged file is NOT reported as modified`() {
        val baseline = WorkspaceDiff.scan(files { write("a.txt", "1") })
        val current = WorkspaceDiff.scan(files { write("a.txt", "1") })
        assertTrue(WorkspaceDiff.compare(baseline, current).modified.isEmpty())
    }

    @Test
    fun `an empty baseline reports everything as added`() {
        val current = WorkspaceDiff.scan(files { write("a.txt", "1"); write("b.txt", "2") })
        val c = WorkspaceDiff.compare(emptyMap(), current)
        assertEquals(listOf("a.txt", "b.txt"), c.added)
        assertTrue(c.modified.isEmpty())
    }

    @Test
    fun `tooLarge files compare by size`() {
        val big = File(tmp.root, "big.bin")
        big.writeBytes(ByteArray(4096))
        val baseline = WorkspaceDiff.scan(tmp.root, maxCaptureBytes = 1024)
        big.writeBytes(ByteArray(8192))
        val current = WorkspaceDiff.scan(tmp.root, maxCaptureBytes = 1024)
        assertEquals(listOf("big.bin"), WorkspaceDiff.compare(baseline, current).modified)
    }

    @Test
    fun `diffFile shows added lines for a new file`() {
        val lines = WorkspaceDiff.diffFile(null, FileSnapshot("hello\n", 6))!!
        assertEquals(listOf("hello"), lines.filter { it.kind == DiffKind.ADDED }.map { it.text })
    }

    @Test
    fun `diffFile shows removed lines for a deleted file`() {
        val lines = WorkspaceDiff.diffFile(FileSnapshot("bye\n", 4), null)!!
        assertEquals(listOf("bye"), lines.filter { it.kind == DiffKind.REMOVED }.map { it.text })
    }

    @Test
    fun `diffFile returns null for a tooLarge file`() {
        assertNull(WorkspaceDiff.diffFile(FileSnapshot(null, 10, tooLarge = true), FileSnapshot(null, 20, tooLarge = true)))
    }
}
