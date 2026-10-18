package com.dsh.mobile.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceBaselineStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = WorkspaceBaselineStore(File(tmp.root, "baseline.txt"))

    @Test
    fun `missing baseline loads empty and reports absent`() {
        assertEquals(emptyMap<String, FileSnapshot>(), store().load())
        assertTrue(!store().exists())
    }

    @Test
    fun `round trip preserves paths and contents`() {
        val s = store()
        s.save(
            linkedMapOf(
                "src/Main.kt" to FileSnapshot("fun main() {}", 12),
                "a b/weird\tname.txt" to FileSnapshot("tab\there", 9),
            )
        )
        val back = s.load()
        assertEquals(setOf("src/Main.kt", "a b/weird\tname.txt"), back.keys)
        assertEquals("fun main() {}", back.getValue("src/Main.kt").content)
        assertEquals("tab\there", back.getValue("a b/weird\tname.txt").content)
        assertTrue(s.exists())
    }

    @Test
    fun `multiline content survives the round trip`() {
        val s = store()
        val body = "line1\nline2\n\nline4"
        s.save(mapOf("x.txt" to FileSnapshot(body, body.length.toLong())))
        assertEquals(body, s.load().getValue("x.txt").content)
    }

    @Test
    fun `tooLarge entries keep their flag and no content`() {
        val s = store()
        s.save(mapOf("big.bin" to FileSnapshot(null, 999_999, tooLarge = true)))
        val back = s.load().getValue("big.bin")
        assertTrue(back.tooLarge)
        assertNull(back.content)
        assertEquals(999_999L, back.sizeBytes)
    }

    @Test
    fun `a corrupt baseline degrades to empty instead of throwing`() {
        val s = store()
        s.save(mapOf("a.txt" to FileSnapshot("x", 1)))
        File(tmp.root, "baseline.txt").writeText("!!! not base64 !!!\t\t\t")
        assertEquals(emptyMap<String, FileSnapshot>(), s.load())
    }

    @Test
    fun `clear removes the baseline`() {
        val s = store()
        s.save(mapOf("a.txt" to FileSnapshot("x", 1)))
        assertTrue(s.exists())
        s.clear()
        assertTrue(!s.exists())
    }
}
