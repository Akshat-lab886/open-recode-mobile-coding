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

    @Test
    fun `unofficial packages are labelled`() {
        val notes = AgentCatalog.all.filter { it.provenanceNote.isNotBlank() }
        assertTrue(
            "the unofficial packages must be labelled, not passed off as official",
            notes.isNotEmpty(),
        )
        notes.forEach {
            assertTrue(
                "${it.id} note must name the problem",
                it.provenanceNote.contains("Unofficial") ||
                    it.provenanceNote.contains("Placeholder"),
            )
        }
    }

    @Test
    fun `argv verification flags are honest`() {
        // Only dsh/claude/codex flags have been checked against a real --help.
        assertEquals(
            listOf("antigravity", "hermes", "opencode"),
            AgentCatalog.all.filterNot { it.argvVerified }.map { it.id }.sorted(),
        )
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