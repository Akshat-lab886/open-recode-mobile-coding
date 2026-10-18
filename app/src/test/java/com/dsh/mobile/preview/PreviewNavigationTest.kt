package com.dsh.mobile.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreviewNavigationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(path: String): File =
        File(tmp.root, path).apply { parentFile?.mkdirs(); writeText("x") }

    @Test
    fun `a file inside the workspace is followed`() {
        val page = write("index.html")
        assertTrue(PreviewNavigation.allow("file://${page.absolutePath}", tmp.root))
    }

    @Test
    fun `a sibling page in the workspace is followed`() {
        write("about.html")
        assertTrue(PreviewNavigation.allow("file://${File(tmp.root, "about.html").absolutePath}", tmp.root))
    }

    @Test
    fun `a percent-encoded path with a space is decoded and followed`() {
        val page = write("my page.html")
        assertTrue(PreviewNavigation.allow("file://${page.absolutePath.replace(" ", "%20")}", tmp.root))
    }

    @Test
    fun `a non-file scheme is blocked`() {
        assertFalse(PreviewNavigation.allow("https://example.com", tmp.root))
        assertFalse(PreviewNavigation.allow("http://example.com", tmp.root))
        assertFalse(PreviewNavigation.allow("javascript:alert(1)", tmp.root))
        assertFalse(PreviewNavigation.allow("content://media/x", tmp.root))
    }

    @Test
    fun `traversal out of the workspace is blocked`() {
        write("index.html")
        val outside = File(tmp.root.parentFile, "secret.html")
        assertFalse(PreviewNavigation.allow("file://${outside.absolutePath}", tmp.root))
    }

    @Test
    fun `a file that does not exist is blocked`() {
        assertFalse(PreviewNavigation.allow("file://${File(tmp.root, "ghost.html").absolutePath}", tmp.root))
    }

    @Test
    fun `a directory is not a page`() {
        File(tmp.root, "sub").mkdirs()
        assertFalse(PreviewNavigation.allow("file://${File(tmp.root, "sub").absolutePath}", tmp.root))
    }

    @Test
    fun `null and blank urls are blocked`() {
        assertFalse(PreviewNavigation.allow(null, tmp.root))
        assertFalse(PreviewNavigation.allow("", tmp.root))
        assertFalse(PreviewNavigation.allow("   ", tmp.root))
    }

    @Test
    fun `the workspace root itself is not a navigable page`() {
        assertFalse(PreviewNavigation.allow("file://${tmp.root.absolutePath}", tmp.root))
    }
}
