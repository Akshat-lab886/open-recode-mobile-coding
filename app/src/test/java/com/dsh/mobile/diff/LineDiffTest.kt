package com.dsh.mobile.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

    private fun added(lines: List<DiffLine>) =
        lines.filter { it.kind == DiffKind.ADDED }.map { it.text }

    private fun removed(lines: List<DiffLine>) =
        lines.filter { it.kind == DiffKind.REMOVED }.map { it.text }

    @Test
    fun `identical files produce only unchanged lines`() {
        val l = listOf("a", "b", "c")
        val out = LineDiff.diff(l, l)!!
        assertEquals(3, out.size)
        assertTrue(out.all { it.kind == DiffKind.UNCHANGED })
        assertEquals(0, LineDiff.changeCount(out))
    }

    @Test
    fun `empty to content is all added`() {
        val out = LineDiff.diff(emptyList(), listOf("x", "y"))!!
        assertEquals(listOf("x", "y"), added(out))
        assertEquals(emptyList<String>(), removed(out))
    }

    @Test
    fun `content to empty is all removed`() {
        val out = LineDiff.diff(listOf("x", "y"), emptyList())!!
        assertEquals(emptyList<String>(), added(out))
        assertEquals(listOf("x", "y"), removed(out))
    }

    @Test
    fun `an insertion keeps its neighbours unchanged`() {
        val out = LineDiff.diff(listOf("a", "c"), listOf("a", "b", "c"))!!
        assertEquals(listOf("a", "c"), out.filter { it.kind == DiffKind.UNCHANGED }.map { it.text })
        assertEquals(listOf("b"), added(out))
    }

    @Test
    fun `a deletion keeps its neighbours unchanged`() {
        val out = LineDiff.diff(listOf("a", "b", "c"), listOf("a", "c"))!!
        assertEquals(listOf("b"), removed(out))
        assertEquals(listOf("a", "c"), out.filter { it.kind == DiffKind.UNCHANGED }.map { it.text })
    }

    @Test
    fun `a replacement reads as removed then added`() {
        val out = LineDiff.diff(listOf("a", "OLD", "z"), listOf("a", "NEW", "z"))!!
        assertEquals(listOf("OLD"), removed(out))
        assertEquals(listOf("NEW"), added(out))
    }

    @Test
    fun `gutter numbers only exist on the side a line belongs to`() {
        val out = LineDiff.diff(listOf("a", "b"), listOf("a", "x"))!!
        for (line in out) {
            when (line.kind) {
                DiffKind.ADDED -> assertNull("added has no old number", line.oldNumber)
                DiffKind.REMOVED -> assertNull("removed has no new number", line.newNumber)
                DiffKind.UNCHANGED -> {
                    assertTrue(line.oldNumber != null && line.newNumber != null)
                }
            }
        }
    }

    @Test
    fun `both empty files produce an empty diff`() {
        assertEquals(emptyList<DiffLine>(), LineDiff.diff(emptyList(), emptyList())!!)
    }

    @Test
    fun `oversized input returns null instead of exhausting memory`() {
        val huge = List(LineDiff.MAX_LINES + 1) { "line $it" }
        assertNull(LineDiff.diff(huge, huge))
        assertNull(LineDiff.diff(emptyList(), huge))
    }

    @Test
    fun `exactly at the cap still diffs`() {
        val atCap = List(LineDiff.MAX_LINES) { "line $it" }
        assertEquals(LineDiff.MAX_LINES, LineDiff.diff(atCap, atCap)!!.size)
    }

    @Test
    fun `lines drops exactly one trailing newline`() {
        assertEquals(listOf("a", "b"), LineDiff.lines("a\nb\n"))
        // A real blank final line must survive: "a\nb\n\n" -> ["a","b",""].
        assertEquals(listOf("a", "b", ""), LineDiff.lines("a\nb\n\n"))
        assertEquals(emptyList<String>(), LineDiff.lines(""))
    }

    @Test
    fun `changeCount counts only real changes`() {
        val out = LineDiff.diff(listOf("a", "b"), listOf("a", "b", "c"))!!
        assertEquals(1, LineDiff.changeCount(out))
    }

    @Test
    fun `stat separates added from removed`() {
        val out = LineDiff.diff(listOf("a", "b", "c"), listOf("a", "x", "y", "c"))!!
        val stat = LineDiff.stat(out)
        assertEquals(2, stat.added)
        assertEquals(1, stat.removed)
        assertEquals(3, stat.total)
        assertEquals("+2 -1", stat.render())
    }

    @Test
    fun `an unchanged file renders as a dash not +0 -0`() {
        val stat = LineDiff.statOf("a\nb\n", "a\nb\n")!!
        assertTrue(stat.isEmpty)
        assertEquals("-", stat.render())
    }

    @Test
    fun `a brand new file counts every line as added`() {
        val stat = LineDiff.statOf(null, "a\nb\nc\n")!!
        assertEquals(3, stat.added)
        assertEquals(0, stat.removed)
    }

    @Test
    fun `a deleted file counts every line as removed`() {
        val stat = LineDiff.statOf("a\nb\n", null)!!
        assertEquals(0, stat.added)
        assertEquals(2, stat.removed)
    }

    @Test
    fun `statOf returns null above the size cap instead of guessing`() {
        val huge = "x\n".repeat(LineDiff.MAX_LINES + 1)
        org.junit.Assert.assertNull(LineDiff.statOf(huge, huge))
    }
}
