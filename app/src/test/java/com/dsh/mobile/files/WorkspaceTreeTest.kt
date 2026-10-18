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

    @Test
    fun `findByExtension matches case-insensitively and ignores the dot`() {
        write("a.HTML")
        write("b.htm")
        write("c.txt")
        val found = WorkspaceTree.findByExtension(tmp.root, setOf("html", ".htm")).map { it.name }
        assertEquals(setOf("a.HTML", "b.htm"), found.toSet())
    }

    @Test
    fun `findByExtension descends into folders but skips noise`() {
        write("dist/out.html")
        write("node_modules/p/i.html")
        write("site/index.html")
        val found = WorkspaceTree.findByExtension(tmp.root, WorkspaceTree.PREVIEWABLE).map { it.name }
        assertEquals(listOf("index.html"), found)
    }

    @Test
    fun `findByExtension returns newest first`() {
        val old = write("old.html"); old.setLastModified(1_000_000L)
        val mid = write("mid.html"); mid.setLastModified(2_000_000L)
        val new = write("new.html"); new.setLastModified(3_000_000L)
        assertEquals(
            listOf("new.html", "mid.html", "old.html"),
            WorkspaceTree.findByExtension(tmp.root, WorkspaceTree.PREVIEWABLE).map { it.name },
        )
    }

    @Test
    fun `findByExtension honours the limit and handles empty input`() {
        repeat(6) { write("f$it.html") }
        assertEquals(2, WorkspaceTree.findByExtension(tmp.root, WorkspaceTree.PREVIEWABLE, limit = 2).size)
        assertTrue(WorkspaceTree.findByExtension(tmp.root, emptySet()).isEmpty())
    }

    @Test
    fun `artifact extensions include reports and images`() {
        assertTrue("md" in WorkspaceTree.ARTIFACT_EXTENSIONS)
        assertTrue("png" in WorkspaceTree.ARTIFACT_EXTENSIONS)
        write("plan.md"); write("out.txt")
        val found = WorkspaceTree.findByExtension(tmp.root, WorkspaceTree.ARTIFACT_EXTENSIONS).map { it.name }
        assertEquals(setOf("plan.md", "out.txt"), found.toSet())
    }

    private fun entry(name: String, path: String = name, dir: Boolean = false) =
        FileEntry(name, path, dir, 0L, 0L)

    @Test
    fun `an empty query returns everything unchanged`() {
        val e = listOf(entry("a.kt"), entry("b.md"))
        assertEquals(e, WorkspaceTree.filter(e, "   "))
    }

    @Test
    fun `filter matches the name case-insensitively`() {
        val e = listOf(entry("Main.kt"), entry("readme.md"))
        assertEquals(listOf("Main.kt"), WorkspaceTree.filter(e, "main").map { it.name })
    }

    @Test
    fun `filter also matches the path so nested files are findable`() {
        val e = listOf(entry("App.kt", "app/src/main/kotlin/App.kt"))
        assertEquals(1, WorkspaceTree.filter(e, "kotlin").size)
    }

    @Test
    fun `ext query filters by extension and keeps directories`() {
        val e = listOf(entry("src", dir = true), entry("a.kt"), entry("b.kt"), entry("c.md"))
        assertEquals(
            listOf("src", "a.kt", "b.kt"),
            WorkspaceTree.filter(e, "ext:kt").map { it.name },
        )
    }

    @Test
    fun `ext query tolerates a leading dot`() {
        val e = listOf(entry("a.kt"))
        assertEquals(1, WorkspaceTree.filter(e, "ext:.kt").size)
    }

    @Test
    fun `isExtensionQuery recognises the prefix only`() {
        assertTrue(WorkspaceTree.isExtensionQuery("ext:kt"))
        assertTrue(WorkspaceTree.isExtensionQuery("EXT:KT"))
        assertTrue(!WorkspaceTree.isExtensionQuery("kt"))
    }

    @Test
    fun `filter preserves the incoming ordering`() {
        val e = listOf(entry("z.kt"), entry("a.kt"))
        assertEquals(listOf("z.kt", "a.kt"), WorkspaceTree.filter(e, ".kt").map { it.name })
    }

    @Test
    fun `a query matching nothing returns an empty list`() {
        assertTrue(WorkspaceTree.filter(listOf(entry("a.kt")), "zzz").isEmpty())
    }

    @Test
    fun `imageMime recognises common image extensions`() {
        assertEquals("image/png", WorkspaceTree.imageMime("a/b/shot.PNG"))
        assertEquals("image/jpeg", WorkspaceTree.imageMime("x.jpg"))
        assertEquals("image/jpeg", WorkspaceTree.imageMime("x.jpeg"))
        assertEquals("image/gif", WorkspaceTree.imageMime("x.gif"))
        assertEquals("image/webp", WorkspaceTree.imageMime("x.webp"))
    }

    @Test
    fun `imageMime is null for non-images and extensionless paths`() {
        assertNull(WorkspaceTree.imageMime("notes.md"))
        assertNull(WorkspaceTree.imageMime("Makefile"))
        assertNull(WorkspaceTree.imageMime("dir.kt"))
    }
}
