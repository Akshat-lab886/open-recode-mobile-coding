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
}
