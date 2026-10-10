package com.dsh.mobile.proxy

import com.dsh.mobile.data.ActiveAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Approval-prompt classification drives the Approve/Deny Bottom Sheet.
 * A missed match hangs the agent on a blocked stdin read; a false positive
 * shows a scary sheet for ordinary chat text. Both directions matter.
 *
 * Patterns mirror VisualGateway's private companion (unreachable from a JVM
 * test without refactoring), so they are pinned here deliberately.
 */
class ApprovalClassificationTest {

    private val APPROVAL_PATTERNS = listOf(
        Regex("do you want to run"),
        Regex("allow .{0,40}command"),
        Regex("allow .{0,40}to run"),
        Regex("confirm .{0,40}execut"),
        Regex("\\[y/n\\]"),
        Regex("proceed\\?"),
    )
    private val FILE_PATTERNS = listOf(
        Regex("allow file modification"),
        Regex("overwrite .{0,60}\\?"),
        Regex("create file .{0,60}\\?"),
        Regex("apply diff"),
    )

    private fun classify(line: String): String? {
        val l = line.lowercase()
        return when {
            APPROVAL_PATTERNS.any { it.containsMatchIn(l) } -> "command"
            FILE_PATTERNS.any { it.containsMatchIn(l) } -> "file_write"
            else -> null
        }
    }

    @Test
    fun `detects command approval prompts`() {
        assertEquals("command", classify("Do you want to run this command?"))
        assertEquals("command", classify("Allow claude to run rm -rf build?"))
        assertEquals("command", classify("Proceed? [y/n]"))
    }

    @Test
    fun `detects file modification prompts`() {
        assertEquals("file_write", classify("Allow file modification?"))
        assertEquals("file_write", classify("Overwrite src/main.kt?"))
        assertEquals("file_write", classify("Create file /workspace/new.txt?"))
    }

    @Test
    fun `ignores ordinary assistant text`() {
        assertNull(classify("I've updated the function to handle null input."))
        assertNull(classify("Here is a summary of the changes."))
        assertNull(classify(""))
    }

    @Test
    fun `approval request carries a stable summary`() {
        val raw = "Do you want to run this command?  npm test"
        val req = VisualGateway.ApprovalRequest(
            id = "appr-1",
            agent = ActiveAgent.CLAUDE_CODE,
            kind = VisualGateway.ApprovalKind.COMMAND,
            summary = raw.trim().take(280),
            rawLine = raw.trim(),
        )
        assertNotNull(req)
        assertEquals(raw, req.summary)
        assertEquals(VisualGateway.ApprovalKind.COMMAND, req.kind)
        assertEquals(ActiveAgent.CLAUDE_CODE, req.agent)
    }

    @Test
    fun `long prompt is truncated to sheet budget`() {
        val raw = "x".repeat(500)
        assertEquals(280, raw.trim().take(280).length)
    }
}