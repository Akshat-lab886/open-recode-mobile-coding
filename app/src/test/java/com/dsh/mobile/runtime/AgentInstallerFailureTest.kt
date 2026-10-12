package com.dsh.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed install has to say why.
 *
 * The installer captured npm's entire output and then discarded it, reporting
 * only "npm exited 1". That is what let a missing shared library
 * (`CANNOT LINK EXECUTABLE "libnode.so": library "libz.so.1" not found`)
 * present as a generic failure for every optional agent, with nothing in the
 * UI or the logs to point at the real cause.
 */
class AgentInstallerFailureTest {

    @Test
    fun `npm error text is carried into the failure message`() {
        val msg = AgentInstaller.Companion.describeFailure(
            exit = 1,
            landed = false,
            log = """
                npm notice
                npm error code ELIFECYCLE
                npm error errno 127
                npm error /data/.../npm: line 1: exec: node: not found
            """.trimIndent(),
        )
        assertTrue("must name the exit status", msg.contains("exited 1"))
        assertTrue("must carry npm's own words: $msg", msg.contains("node: not found"))
    }

    @Test
    fun `linker failure is visible rather than hidden`() {
        val msg = AgentInstaller.Companion.describeFailure(
            exit = 127,
            landed = false,
            log = "CANNOT LINK EXECUTABLE \"libnode.so\": library \"libz.so.1\" not found",
        )
        assertTrue("the linker error is the whole point: $msg", msg.contains("libz.so.1"))
    }

    @Test
    fun `missing binary with a clean exit is still reported`() {
        val msg = AgentInstaller.Companion.describeFailure(exit = 0, landed = false, log = "")
        assertTrue(msg.contains("missing"))
    }

    @Test
    fun `empty log does not produce a dangling separator`() {
        val msg = AgentInstaller.Companion.describeFailure(exit = 1, landed = false, log = "   \n  \n")
        assertEquals("npm exited 1", msg)
    }
}
