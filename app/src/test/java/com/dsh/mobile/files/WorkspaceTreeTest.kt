package com.dsh.mobile.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceTreeTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(path: String, text: String = "x"): File =
        File(tmp.root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun `directories are listed before files`() {
        write("zeta.txt")
        write("alpha.txt")
        write("src/Main.kt")
        val entries = WorkspaceTree.list(tmp.root).entries
        assertEquals(listOf("src", "alpha.txt", "zeta.txt"), entries.map { it.name })
    }

    @Test
    fun `skip directories never appear`() {
        write("keep.txt")
        write("node_modules/pkg/i.js")
        write(".git/config")
        write("build/out.txt")
        assertEquals(listOf("keep.txt"), WorkspaceTree.list(tmp.root).entries.map { it.name })
    }

    @Test
    fun `hidden files are still shown even though hidden dirs are skipped`() {
        write(".env")
        assertEquals(listOf(".env"), WorkspaceTree.list(tmp.root).entries.map { it.name })
    }

    @Test
    fun `nested paths are built from the relative dir`() {
        write("src/main/App.kt")
        val entry = WorkspaceTree.list(tmp.root, "src/main").entries.single()
        assertEquals("src/main/App.kt", entry.path)
        assertEquals("App.kt", entry.name)
        assertTrue(!entry.isDirectory)
        assertTrue(entry.sizeBytes > 0)
    }

    @Test
    fun `a missing directory yields an empty listing rather than throwing`() {
        val listing = WorkspaceTree.list(tmp.root, "nope/deeper")
        assertTrue(listing.entries.isEmpty())
    }

    @Test
    fun `parentOf walks up and stops at the root`() {
        assertNull(WorkspaceTree.parentOf(""))
        assertEquals("", WorkspaceTree.parentOf("a"))
        assertEquals("a", WorkspaceTree.parentOf("a/b"))
        assertEquals("a/b", WorkspaceTree.parentOf("a/b/c"))
    }

    @Test
    fun `the root listing reports no parent`() {
        assertNull(WorkspaceTree.list(tmp.root).parentPath)
        assertEquals("", WorkspaceTree.list(tmp.root, "a").parentPath)
    }

    @Test
    fun `crumbs split a path for the breadcrumb trail`() {
        assertEquals(emptyList<String>(), WorkspaceTree.crumbs(""))
        assertEquals(listOf("src"), WorkspaceTree.crumbs("src"))
        assertEquals(listOf("src", "main"), WorkspaceTree.crumbs("src/main"))
    }

    @Test
    fun `entry count is capped`() {
        repeat(10) { write("f$it.txt") }
        assertEquals(3, WorkspaceTree.list(tmp.root, maxEntries = 3).entries.size)
    }

    @Test
    fun `resolve refuses to escape the workspace root`() {
        write("inside.txt")
        assertEquals(File(tmp.root, "inside.txt"), WorkspaceTree.resolve(tmp.root, "inside.txt"))
        assertNull(WorkspaceTree.resolve(tmp.root, "../../etc/passwd"))
        assertNull(WorkspaceTree.resolve(tmp.root, "a/../../../etc"))
    }

    @Test
    fun `resolve accepts the root itself`() {
        assertEquals(tmp.root, WorkspaceTree.resolve(tmp.root, ""))
    }

    @Test
    fun `preview returns text for a normal file`() {
        val f = write("a.txt", "hello")
        assertEquals("hello", WorkspaceTree.preview(f))
    }

    @Test
    fun `preview returns null for binary files`() {
        val f = File(tmp.root, "a.bin")
        f.writeBytes(byteArrayOf(1, 0, 2, 3))
        assertNull(WorkspaceTree.preview(f))
    }

    @Test
    fun `preview returns null for files over the cap and for directories`() {
        val big = File(tmp.root, "big.txt")
        big.writeText("x".repeat(100))
        assertNull(WorkspaceTree.preview(big, maxBytes = 10))
        assertNull(WorkspaceTree.preview(tmp.root))
    }

    @Test
    fun `formatSize is human readable at every scale`() {
        assertEquals("0 B", WorkspaceTree.formatSize(0))
        assertEquals("512 B", WorkspaceTree.formatSize(512))
        assertEquals("1.0 KB", WorkspaceTree.formatSize(1024))
        assertEquals("1.0 MB", WorkspaceTree.formatSize(1024L * 1024))
    }
}
