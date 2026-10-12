package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The complaint these pin down: agent installation used to be unconditional on
 * first boot, and the work re-ran on every app open. Installation is now
 * opt-in and happens exactly once.
 *
 * The decision is tested through [AgentInstallStore.pendingLogic] rather than
 * the store itself: DataStore needs a real Android Context, and this is the
 * logic that must not regress.
 */
class AgentInstallStoreTest {

    private val dsh = AgentCatalog.specFor("dsh")!!
    private val claude = AgentCatalog.specFor("claude")!!

    private fun pending(
        selected: Set<String>,
        completed: Set<String> = emptySet(),
        onDisk: (AgentSpec) -> Boolean = { false },
    ): List<String> =
        AgentInstallStore.pendingLogic(selected, completed, java.io.File("/nonexistent"), onDisk)
            .map { it.id }

    // -- the core requirement ------------------------------------------------

    @Test
    fun `nothing installs until the user selects`() {
        assertTrue(
            "no agent may install without being selected",
            pending(emptySet()).isEmpty(),
        )
    }

    @Test
    fun `a completed install never runs again`() {
        val selected = setOf("dsh", "claude")
        assertTrue(
            "reopening the app must not reinstall anything",
            pending(selected, completed = selected, onDisk = { true }).isEmpty(),
        )
    }

    @Test
    fun `reopening after selection but before completion retries`() {
        val selected = setOf("claude")
        assertEquals(
            "a not-yet-installed agent must install on the first run",
            listOf("claude"),
            pending(selected, completed = emptySet(), onDisk = { false }),
        )
        assertEquals(
            "an interrupted install must retry rather than be treated as done",
            listOf("claude"),
            pending(selected, completed = selected, onDisk = { false }),
        )
    }

    @Test
    fun `deleting an installed agent makes it installable again`() {
        assertEquals(
            "a recorded install with no binary must be repaired, not trusted",
            listOf("dsh"),
            pending(setOf("dsh"), completed = setOf("dsh"), onDisk = { false }),
        )
    }

    @Test
    fun `only selected agents are offered`() {
        val result = pending(setOf("dsh"))
        assertEquals(listOf("dsh"), result)
        assertFalse(
            "unselected agents must never be installed",
            result.contains("claude"),
        )
    }

    @Test
    fun `every catalogued agent is selectable`() {
        val all = AgentCatalog.all.map { it.id }.toSet()
        assertEquals(
            "every catalogued agent must be selectable",
            all.toList(),
            pending(all),
        )
    }

    @Test
    fun `selecting everything then completing installs each exactly once`() {
        val all = AgentCatalog.all.map { it.id }.toSet()
        assertEquals(
            "first run installs all selected",
            all.size,
            pending(all).size,
        )
        assertTrue(
            "second run must install nothing",
            pending(all, completed = all, onDisk = { true }).isEmpty(),
        )
    }

    // -- honesty guards ------------------------------------------------------

    /**
     * The honesty guard, corrected against verified registry data.
     *
     * Two of these were wrong when first written and the test caught it:
     *  - `antigravity` on npm is NOT Google's. It is version 0.0.0, described
     *    as "placeholder for the haters", and its bin is `kirox`. The real
     *    Antigravity CLI is a Go binary from GitHub releases. So the catalogue
     *    must NOT install it from npm at all.
     *  - `hermes-agent` on npm is an unofficial third-party bridge (103 KB)
     *    that then pulls a 1-3 GB runtime, not a small CLI.
     */
    @Test
    fun `unofficial or closed-source packages are labelled`() {
        val hermes = AgentCatalog.specFor("hermes")!!
        val anti = AgentCatalog.specFor("antigravity")!!
        assertTrue(
            "the unofficial Hermes bridge must say so",
            hermes.provenanceNote.contains("Unofficial"),
        )
        assertTrue(
            "closed-source Antigravity must say so",
            anti.provenanceNote.contains("Closed source"),
        )
        assertTrue(
            "Hermes' real size must be shown, not a flattering 90 MB guess",
            hermes.approxInstallBytes >= 1024L * 1024 * 1024,
        )
    }

    @Test
    fun `antigravity is not installed from npm`() {
        val anti = AgentCatalog.specFor("antigravity")!!
        assertTrue(
            "Antigravity must come from the real release tarball, not npm",
            anti.install is com.dsh.mobile.data.InstallKind.Tarball,
        )
        val url = (anti.install as com.dsh.mobile.data.InstallKind.Tarball).urlTemplate
        // Google hosts the CLI on its own CDN; the GitHub release assets are
        // glibc-linked and cannot execute inside the app sandbox. The Android
        // build is Bionic (PT_INTERP=/system/bin/linker64), which is what the
        // agent actually needs here since it runs natively, without proot.
        assertTrue("must come from Google's own CDN: $url", url.startsWith("https://storage.googleapis.com/antigravity-public/antigravity-cli/"))
        assertTrue("must request the Android asset: $url", url.contains("android-arm"))
    }

    @Test
    fun `npm-installed agents name their real package`() {
        AgentCatalog.all.forEach { spec ->
            if (spec.install !is com.dsh.mobile.data.InstallKind.NPM) return@forEach
            assertTrue("${spec.id} has no npm package", spec.npmPackage.isNotBlank())
            assertFalse(
                "no agent may be installed from the npm 'antigravity' placeholder",
                spec.npmPackage.equals("antigravity", ignoreCase = true),
            )
        }
    }

    @Test
    fun `argv verification flags are honest`() {
        // Only dsh and codex have been checked against a real --help - and
        // codex's was checked on this device, against the installed binary.
        //
        // claude moved here deliberately: its flags cannot be verified because
        // the CLI cannot run at all on Android, so claiming otherwise would be
        // a flag nobody could ever earn.
        assertEquals(
            listOf("antigravity", "claude", "hermes", "opencode"),
            AgentCatalog.all.filterNot { it.argvVerified }.map { it.id }.sorted(),
        )
    }

    @Test
    fun `agents that cannot run on android are not offered as installable`() {
        // A dead install is worse than an absent one: npm reports success and
        // leaves a 500-byte stub for Claude Code. It must not be selectable.
        val claude = AgentCatalog.all.first { it.id == "claude" }
        assertTrue("claude must not be offered", claude.install is InstallKind.Unsupported)
        assertFalse("claude must not be offered for install", claude.available)
        assertFalse("claude must be absent from the installable list", AgentCatalog.installable.any { it.id == "claude" })
        assertTrue(
            "the reason must be shown rather than silently dropped",
            (claude.install as InstallKind.Unsupported).reason.isNotBlank(),
        )
        // Codex is the opposite case and must stay offered.
        assertTrue(AgentCatalog.installable.any { it.id == "codex" })
    }

    @Test
    fun `install cost is available for the picker`() {
        assertTrue(AgentCatalog.totalInstallBytes > 0)
        AgentCatalog.all.forEach {
            assertTrue("${it.id} has no size estimate", it.approxInstallBytes > 0)
        }
    }

    @Test
    fun `catalogue sanity`() {
        assertTrue(dsh.npmPackage.isNotBlank())
        assertTrue(claude.npmPackage.isNotBlank())
        assertTrue("claude runs behind the gateway", claude.needsGateway)
    }
}