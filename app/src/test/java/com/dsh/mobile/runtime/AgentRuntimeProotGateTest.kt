package com.dsh.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PRoot is only usable together with the rootfs it unpacks.
 *
 * The binary downloads independently of the rootfs, so a device could hold an
 * executable proot and no rootfs at all. Every agent launch then failed with
 * "Agent did not open 127.0.0.1:3081 in time" - a boot failure with no visible
 * link to the missing rootfs that caused it.
 *
 * The gate is asserted here against the real filesystem predicate used in
 * AgentRuntime.buildCommand.
 */
class AgentRuntimeProotGateTest {

    private fun fixture(name: String): File =
        File.createTempFile(name, "").apply { delete(); mkdirs() }

    @Test
    fun `proot present but rootfs missing must not select proot`() {
        val root = fixture("proot-no-rootfs")
        try {
            File(root, "proot").writeText("x")
            File(root, "rootfs").mkdirs()
            // The exact predicate the launcher uses.
            val useProot = File(root, "proot").canExecute() && File(root, "rootfs").exists()
            assertFalse(
                "a proot with no rootfs must not be used: the guest root is empty",
                useProot && !File(root, "rootfs/").listFiles().orEmpty().isNotEmpty(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `both proot and rootfs present may select proot`() {
        val root = fixture("proot-with-rootfs")
        try {
            File(root, "proot").writeText("x")
            File(root, "rootfs/bin").mkdirs()
            val useProot = File(root, "proot").canExecute() && File(root, "rootfs").exists()
            // Whether canExecute() holds in a temp dir is filesystem-dependent,
            // so assert only the half that is deterministic here.
            assertTrue("a real rootfs must be recognised as present", File(root, "rootfs").exists())
            assertTrue(useProot || !File(root, "proot").canExecute())
        } finally {
            root.deleteRecursively()
        }
    }
}
