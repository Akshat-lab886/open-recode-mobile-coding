package com.dsh.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MarkdownTest {

    private inline fun <reified T> List<MarkdownBlock>.only(): T {
        assertEquals("expected exactly one block, got $this", 1, size)
        assertTrue("expected ${T::class.simpleName}, got ${this[0]}", this[0] is T)
        return this[0] as T
    }

    @Test
    fun `plain prose becomes one paragraph`() {
        val p = Markdown.parse("hello there").only<MarkdownBlock.Paragraph>()
        assertEquals("hello there", p.text)
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val blocks = Markdown.parse("one\n\ntwo")
        assertEquals(2, blocks.size)
        assertEquals("one", (blocks[0] as MarkdownBlock.Paragraph).text)
        assertEquals("two", (blocks[1] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `wrapped lines stay in one paragraph`() {
        val blocks = Markdown.parse("one\ntwo")
        assertEquals(1, blocks.size)
        assertEquals("one\ntwo", (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `headers capture their level and text`() {
        val h = Markdown.parse("### Steps").only<MarkdownBlock.Header>()
        assertEquals(3, h.level)
        assertEquals("Steps", h.text)
    }

    @Test
    fun `six hashes is a header and seven is not`() {
        assertEquals(6, Markdown.parse("###### deep").only<MarkdownBlock.Header>().level)
        assertTrue(Markdown.parse("####### nope")[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `a hash without a space is not a header`() {
        // "#1 priority" is prose, and treating it as a heading loses the text.
        assertTrue(Markdown.parse("#1 priority")[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `a fenced block captures language and body`() {
        val c = Markdown.parse("```kotlin\nval x = 1\n```").only<MarkdownBlock.CodeBlock>()
        assertEquals("kotlin", c.language)
        assertEquals("val x = 1", c.code)
    }

    @Test
    fun `a fenced block without a language still parses`() {
        val c = Markdown.parse("```\nplain\n```").only<MarkdownBlock.CodeBlock>()
        assertEquals("", c.language)
        assertEquals("plain", c.code)
    }

    @Test
    fun `an UNTERMINATED fence is still code, not a broken paragraph`() {
        // This is the streaming case: the reply is mid-code-block. Literal
        // backticks appearing in the bubble while the agent is still typing is
        // exactly the artefact this guards against.
        val c = Markdown.parse("here:\n```kotlin\nval x = 1").let {
            it.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        }
        assertEquals("kotlin", c.language)
        assertEquals("val x = 1", c.code)
    }

    @Test
    fun `hasOpenFence tracks a fence that is still open`() {
        assertTrue(Markdown.hasOpenFence("```kotlin\nval x = 1"))
        assertFalse(Markdown.hasOpenFence("```kotlin\nval x = 1\n```"))
        assertFalse(Markdown.hasOpenFence("no code here"))
    }

    @Test
    fun `markdown inside a code block is not interpreted`() {
        val c = Markdown.parse("```\n# not a header\n- not a bullet\n```").only<MarkdownBlock.CodeBlock>()
        assertEquals("# not a header\n- not a bullet", c.code)
    }

    @Test
    fun `bullets record their indent depth`() {
        val blocks = Markdown.parse("- one\n  - two\n    - three")
        assertEquals(listOf(0, 1, 2), blocks.map { (it as MarkdownBlock.Bullet).depth })
        assertEquals("one", (blocks[0] as MarkdownBlock.Bullet).text)
    }

    @Test
    fun `all three bullet markers work`() {
        assertEquals(3, Markdown.parse("- a\n* b\n+ c").size)
    }

    @Test
    fun `numbered items keep their original numbers`() {
        // Renumbering would silently rewrite a list that intentionally starts at 3.
        val blocks = Markdown.parse("3. third\n4. fourth")
        assertEquals(listOf("3", "4"), blocks.map { (it as MarkdownBlock.Numbered).number })
    }

    @Test
    fun `a separator line becomes a divider`() {
        assertTrue(Markdown.parse("---")[0] is MarkdownBlock.Divider)
        assertTrue(Markdown.parse("***")[0] is MarkdownBlock.Divider)
    }

    @Test
    fun `two dashes are a bullet not a divider`() {
        // "--" is not a thematic break, and guessing costs a lost bullet.
        assertTrue(Markdown.parse("-- x")[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `a heading after prose starts a new block`() {
        val blocks = Markdown.parse("intro\n## Next")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(blocks[1] is MarkdownBlock.Header)
    }

    @Test
    fun `a code block splits the prose around it`() {
        val blocks = Markdown.parse("before\n```\ncode\n```\nafter")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(blocks[1] is MarkdownBlock.CodeBlock)
        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `empty input yields no blocks`() {
        assertTrue(Markdown.parse("").isEmpty())
    }

    @Test
    fun `a trailing newline does not invent an empty paragraph`() {
        assertEquals(1, Markdown.parse("done\n").size)
    }

    @Test
    fun `oversized input is clipped rather than parsed`() {
        val huge = "x".repeat(Markdown.MAX_INPUT_CHARS + 5_000)
        val blocks = Markdown.parse(huge)
        assertTrue(Markdown.toPlainText(blocks).length <= Markdown.MAX_INPUT_CHARS)
    }

    @Test
    fun `toPlainText renders headings, lists and code`() {
        val text = Markdown.toPlainText(Markdown.parse("# H\n- b\n```\nc\n```"))
        assertTrue(text.contains("H"))
        assertTrue(text.contains("- b"))
        assertTrue(text.contains("c"))
    }
}
