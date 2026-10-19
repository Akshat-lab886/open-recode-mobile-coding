package com.dsh.mobile.terminal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * These run a REAL `sh`, so they verify the session against the same shell the
 * device uses rather than a mock of it. Each behaviour below was measured on a
 * live process before the implementation was written.
 */
class ShellSessionTest {

    @get:Rule val tmp = TemporaryFolder()

    private var session: ShellSession? = null

    private fun session(): ShellSession =
        ShellSession(tmp.root).also { session = it }

    @After
    fun tearDown() {
        session?.close()
    }

    @Test
    fun `runs a command and reports its output`() {
        val r = session().run("echo hello")
        assertEquals("hello", r.output.trim())
        assertEquals(0, r.exitCode)
        assertFalse(r.timedOut)
    }

    @Test
    fun `the working directory PERSISTS across commands`() {
        // The whole point of a session. ShellRunner could not do this.
        val s = session()
        s.run("mkdir -p sub && cd sub")
        val expected = java.io.File(tmp.root, "sub").canonicalPath
        assertEquals(expected, s.run("pwd").output.trim())
    }

    @Test
    fun `exported variables PERSIST across commands`() {
        val s = session()
        s.run("export DSH_TEST_VAR=kept")
        assertEquals("kept", s.run("echo \$DSH_TEST_VAR").output.trim())
    }

    @Test
    fun `shell functions persist`() {
        val s = session()
        s.run("greet() { echo hi-\$1; }")
        assertEquals("hi-bob", s.run("greet bob").output.trim())
    }

    @Test
    fun `a syntax error does NOT kill the session`() {
        // Measured: sending a malformed line straight to `sh` exits the process
        // with 2 and every later command is lost. `eval` contains it.
        val s = session()
        val bad = s.run("if [ 1 ; then")
        assertNotEquals("session must survive a syntax error", 0, bad.exitCode)
        assertEquals("still-alive", s.run("echo still-alive").output.trim())
    }

    @Test
    fun `a syntax error does not corrupt the next command`() {
        val s = session()
        s.run("if [ 1 ; then")
        s.run("for x in")
        assertEquals("clean", s.run("echo clean").output.trim())
    }

    @Test
    fun `exit codes come from the user command not the wrapper`() {
        val s = session()
        assertEquals(0, s.run("true").exitCode)
        assertEquals(1, s.run("false").exitCode)
        assertEquals(3, s.run("(exit 3)").exitCode)
        assertEquals(0, s.run("true").exitCode)
    }

    @Test
    fun `a bare exit ends the session and the next command restarts it`() {
        // `exit` terminates the child: that is the shell's own semantics, not a
        // bug to work around. What matters is that the NEXT command still works,
        // so the tab is not left permanently dead.
        val s = session()
        s.run("exit 3")
        assertFalse("the child really did exit", s.isAlive)
        val after = s.run("echo back")
        assertTrue("the session must come back", after.restarted)
        assertEquals("back", after.output.trim())
    }

    @Test
    fun `stderr is captured with stdout`() {
        val r = session().run("echo out; echo err 1>&2")
        assertTrue(r.output.contains("out"))
        assertTrue(r.output.contains("err"))
    }

    @Test
    fun `a command that READS STDIN does not swallow the next command`() {
        // The desync bug. Without </dev/null the `read` consumes the following
        // command line and its sentinel, and every later command returns the
        // previous one's output - permanently.
        val s = session()
        s.run("read line || true")
        assertEquals("intact", s.run("echo intact").output.trim())
        assertEquals("intact2", s.run("echo intact2").output.trim())
    }

    @Test
    fun `cat does not hang or eat the session`() {
        val s = session()
        val r = s.run("cat", timeoutMs = 3_000)
        assertFalse("cat must see EOF, not block forever", r.timedOut)
        assertEquals("after", s.run("echo after").output.trim())
    }

    @Test
    fun `a dollar sign survives the wrapper and is expanded by the COMMAND`() {
        // The wrapper must not expand $ itself: `echo $VAR` has to arrive intact
        // so the user's shell expands it. If the wrapper expanded it early the
        // command would see a baked-in value instead of the live variable.
        val s = session()
        s.run("export DSH_DOLLAR=live")
        assertEquals("live", s.run("echo \$DSH_DOLLAR").output.trim())
        // Single quotes make a $ literal, which is the shell's own rule and must
        // be preserved through the wrapper.
        val literal = s.run("echo '\$NOT_A_VAR'")
        assertEquals("\$NOT_A_VAR", literal.output.trim())
    }

    @Test
    fun `backticks are not executed by the outer shell`() {
        val s = session()
        val r = s.run("echo '`echo INJECTED`'")
        assertTrue("command substitution must not run early: ${r.output}", r.output.contains("INJECTED"))
    }

    @Test
    fun `quoting survives`() {
        val s = session()
        assertEquals("a  b", s.run("echo 'a  b'").output.trim())
        assertEquals("one two", s.run("printf '%s %s' one two").output.trim())
    }

    @Test
    fun `multi-line output is preserved`() {
        val r = session().run("printf 'l1\\nl2\\nl3\\n'")
        val lines = r.output.trim().lines()
        assertEquals(listOf("l1", "l2", "l3"), lines)
    }

    @Test
    fun `a timeout abandons the command but keeps the session`() {
        val s = session()
        val r = s.run("sleep 5", timeoutMs = 400)
        assertTrue(r.timedOut)
        assertEquals(124, r.exitCode)
        // The session must still work afterwards.
        assertEquals("recovered", s.run("echo recovered", timeoutMs = 5_000).output.trim())
    }

    @Test
    fun `output is capped and says so`() {
        val s = ShellSession(tmp.root, outputLimitChars = 200)
        session = s
        val r = s.run("for i in \$(seq 1 200); do echo 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'; done")
        assertTrue("must be capped, was ${r.output.length}", r.output.length <= 260)
        assertTrue("truncation must be visible", r.output.contains("truncated"))
    }

    @Test
    fun `a blank command is a no-op`() {
        val r = session().run("   ")
        assertEquals(0, r.exitCode)
        assertEquals("", r.output)
    }

    @Test
    fun `closing makes further commands fail rather than hang`() {
        val s = session()
        s.run("echo before")
        s.close()
        val r = s.run("echo after")
        assertNotEquals(0, r.exitCode)
    }

    @Test
    fun `isAlive reflects the child`() {
        val s = session()
        s.run("echo x")
        assertTrue(s.isAlive)
        s.close()
        assertFalse(s.isAlive)
    }
}

class ShellSessionQuoteTest {

    @Test
    fun `a double quote is escaped`() {
        assertEquals("\"say \\\"hi\\\"\"", ShellSession.quoteForDoubleQuotes("say \"hi\""))
    }

    @Test
    fun `a dollar is escaped so it is not expanded early`() {
        assertEquals("\"\\\$HOME\"", ShellSession.quoteForDoubleQuotes("\$HOME"))
    }

    @Test
    fun `a backslash is escaped`() {
        assertEquals("\"a\\\\\\\\b\"", ShellSession.quoteForDoubleQuotes("a\\\\b"))
    }

    @Test
    fun `a backtick is escaped`() {
        assertEquals("\"\\`id\\`\"", ShellSession.quoteForDoubleQuotes("`id`"))
    }

    @Test
    fun `a newline becomes an escape so the wrapper stays one line`() {
        val quoted = ShellSession.quoteForDoubleQuotes("a\nb")
        assertFalse("must not contain a real newline", quoted.contains('\n'))
        assertTrue(quoted.contains("\\n"))
    }

    @Test
    fun `an empty command still quotes`() {
        assertEquals("\"\"", ShellSession.quoteForDoubleQuotes(""))
    }
}
