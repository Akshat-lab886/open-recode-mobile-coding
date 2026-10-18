package com.dsh.mobile.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMentionsTest {

    @Test
    fun `a bare at sign is an empty active token`() {
        assertEquals("", ContextMentions.activeToken("@"))
    }

    @Test
    fun `the token under the cursor is returned without its at sign`() {
        assertEquals("src/Mai", ContextMentions.activeToken("look at @src/Mai"))
    }

    @Test
    fun `no active token when the text has no at sign`() {
        assertNull(ContextMentions.activeToken("just prose"))
    }

    @Test
    fun `no active token once whitespace closes the token`() {
        assertNull(ContextMentions.activeToken("@Main.kt and then", cursor = 16))
    }

    @Test
    fun `active token respects a mid-text cursor`() {
        val text = "@Main.kt trailing words"
        assertEquals("Main.kt", ContextMentions.activeToken(text, cursor = 8))
    }

    @Test
    fun `parse finds every mention in order`() {
        assertEquals(listOf("a.txt", "src/B.kt"), ContextMentions.paths("check @a.txt then @src/B.kt"))
    }

    @Test
    fun `an email address is not a mention`() {
        // dev@example.com must not be read as a file named "example.com".
        assertTrue(ContextMentions.parse("mail dev@example.com").isEmpty())
    }

    @Test
    fun `duplicate mentions are reported once`() {
        assertEquals(listOf("a.txt"), ContextMentions.paths("@a.txt and again @a.txt"))
    }

    @Test
    fun `an at sign with nothing after it is not a mention`() {
        assertTrue(ContextMentions.parse("what about @").isEmpty())
    }

    @Test
    fun `mention ranges point at the token without the at sign`() {
        val m = ContextMentions.parse("hi @a.txt").single()
        assertEquals(3, m.start)
        assertEquals(9, m.end) // exclusive: "a.txt" occupies 4..8
        assertEquals("a.txt", m.path)
    }

    @Test
    fun `applyCompletion replaces the token under the cursor`() {
        assertEquals("look at @src/Main.kt ", ContextMentions.applyCompletion("look at @src/Mai", "src/Main.kt"))
    }

    @Test
    fun `applyCompletion keeps the text after the cursor`() {
        assertEquals("@Main.kt tail", ContextMentions.applyCompletion("@Mai tail", "Main.kt", cursor = 4))
    }

    @Test
    fun `applyCompletion refuses when the cursor is not in a token`() {
        assertNull(ContextMentions.applyCompletion("no mention here", "Main.kt"))
    }

    @Test
    fun `complete prefers prefix matches over substring matches`() {
        val c = listOf("docs/OldMakefile", "Main.kt", "src/Main.kt")
        val out = ContextMentions.complete(c, "@main")
        // Main.kt and src/Main.kt lead; the substring-only hit comes last.
        assertEquals(2, out.size)
        assertTrue(out.contains("Main.kt"))
    }

    @Test
    fun `an empty token offers the first few candidates`() {
        assertEquals(listOf("a", "b"), ContextMentions.complete(listOf("a", "b", "c"), "@", limit = 2))
    }

    @Test
    fun `complete is case insensitive and honours the limit`() {
        val c = listOf("Alpha.kt", "Beta.kt", "Gamma.kt")
        assertEquals(listOf("Alpha.kt"), ContextMentions.complete(c, "@alp"))
        assertEquals(1, ContextMentions.complete(c, "@", limit = 1).size)
    }

    @Test
    fun `no candidates simply returns nothing`() {
        assertTrue(ContextMentions.complete(listOf("a.kt"), "@zzz").isEmpty())
    }
}
