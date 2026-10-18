package com.dsh.mobile.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellRunnerTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `captures stdout`() {
        val r = ShellRunner.run("echo hello", tmp.root)
        assertEquals("hello", r.stdout.trim())
        assertEquals(0, r.exitCode)
        assertFalse(r.timedOut)
    }

    @Test
    fun `captures stderr without losing stdout`() {
        // The deadlock case: writing a lot to stderr while stdout is unread.
        val r = ShellRunner.run("echo out; echo err 1>&2", tmp.root)
        assertEquals("out", r.stdout.trim())
        assertEquals("err", r.stderr.trim())
    }

    @Test
    fun `a large stderr stream does not deadlock`() {
        // If both pipes were drained on one thread this would hang until the
        // test timeout - the exact bug the two-thread pump exists to prevent.
        val r = ShellRunner.run("head -c 60000 /dev/zero | tr '\\0' 'e' 1>&2", tmp.root, timeoutMs = 20_000)
        assertFalse(r.timedOut)
        assertTrue(r.stderr.length > 0)
    }

    @Test
    fun `propagates a non-zero exit code`() {
        val r = ShellRunner.run("exit 3", tmp.root)
        assertEquals(3, r.exitCode)
    }

    @Test
    fun `runs in the requested working directory`() {
        val dir = tmp.newFolder("work")
        val r = ShellRunner.run("pwd", dir)
        assertTrue(r.stdout.contains("work"))
    }

    @Test
    fun `a blank command is a no-op`() {
        val r = ShellRunner.run("   ", tmp.root)
        assertEquals(0, r.exitCode)
        assertEquals("", r.stdout)
    }

    @Test
    fun `output is capped so a runaway stream cannot exhaust memory`() {
        val r = ShellRunner.run("head -c 2000000 /dev/zero | tr '\\0' 'a'", tmp.root, timeoutMs = 30_000)
        assertTrue("output was ${r.stdout.length}", r.stdout.length <= ShellRunner.MAX_OUTPUT_BYTES)
    }

    @Test
    fun `a hung command is killed and reported as a timeout`() {
        val r = ShellRunner.run("sleep 30", tmp.root, timeoutMs = 1_500)
        assertTrue(r.timedOut)
        assertEquals(124, r.exitCode)
    }

    @Test
    fun `combined puts stderr after stdout`() {
        val r = ShellRunner.run("echo one; echo two 1>&2", tmp.root)
        assertTrue(r.combined.indexOf("one") < r.combined.indexOf("two"))
    }

    @Test
    fun `isSubmittable rejects blanks and embedded newlines`() {
        assertFalse(ShellRunner.isSubmittable(""))
        assertFalse(ShellRunner.isSubmittable("   "))
        assertFalse(ShellRunner.isSubmittable("echo a\necho b"))
        assertFalse(ShellRunner.isSubmittable("echo a\rb"))
        assertTrue(ShellRunner.isSubmittable("ls -la"))
    }

    @Test
    fun `append keeps output under the per-entry cap and says so`() {
        val big = "x".repeat(TerminalTranscript.MAX_OUTPUT_CHARS + 500)
        val out = TerminalTranscript.append(emptyList(), TerminalEntry("cat big", big, false))
        val text = out.single().output
        assertTrue(text.length < big.length)
        assertTrue("an entry must never exceed the documented cap", text.length <= TerminalTranscript.MAX_OUTPUT_CHARS)
        assertTrue("truncation must be visible", text.contains("truncated"))
    }

    @Test
    fun `short output is left alone`() {
        val out = TerminalTranscript.append(emptyList(), TerminalEntry("ls", "a\nb", false))
        assertEquals("a\nb", out.single().output)
    }

    @Test
    fun `the transcript keeps only the newest entries`() {
        var acc = emptyList<TerminalEntry>()
        repeat(TerminalTranscript.MAX_ENTRIES + 50) { i -> acc = TerminalTranscript.append(acc, TerminalEntry("cmd$i", "o", false)) }
        assertEquals(TerminalTranscript.MAX_ENTRIES, acc.size)
        // The survivors are the newest ones, in order.
        assertEquals("cmd50", acc.first().command)
        assertEquals("cmd${TerminalTranscript.MAX_ENTRIES + 49}", acc.last().command)
    }

    @Test
    fun `memory is bounded no matter how long the session runs`() {
        // 200 entries x 16KB cap is the ceiling regardless of input size.
        var acc = emptyList<TerminalEntry>()
        val huge = "y".repeat(200_000)
        repeat(500) { acc = TerminalTranscript.append(acc, TerminalEntry("c$it", huge, false)) }
        assertTrue("retained ${acc.sumOf { it.output.length }} chars", acc.sumOf { it.output.length } <=
            TerminalTranscript.MAX_ENTRIES * TerminalTranscript.MAX_OUTPUT_CHARS)
    }

    @Test
    fun `failed commands keep their flag`() {
        val out = TerminalTranscript.append(emptyList(), TerminalEntry("boom", "err", true))
        assertTrue(out.single().failed)
    }
}
