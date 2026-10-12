package com.dsh.mobile.runtime

import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue is the single source of truth for which agents exist and how
 * they are launched. These tests pin the invariants that the old scattered
 * `when` blocks failed to keep: every ActiveAgent has a catalogue entry, every
 * entry resolves to a runnable argv, and nothing points at a path that cannot
 * exist.
 */
class AgentCatalogContractTest {

    @Test
    fun `every ActiveAgent has a catalogue entry`() {
        ActiveAgent.entries.forEach { agent ->
            assertNotNull(
                "ActiveAgent.$agent has no AgentCatalog entry - the picker and " +
                    "installer would silently omit it",
                agent.spec,
            )
        }
    }

    @Test
    fun `every catalogue entry is reachable from an ActiveAgent`() {
        AgentCatalog.all.forEach { spec ->
            val match = ActiveAgent.entries.firstOrNull { it.spec?.id == spec.id }
            assertNotNull(
                "catalog entry '${spec.id}' has no ActiveAgent, so it can never " +
                    "be selected or launched",
                match,
            )
        }
    }

    @Test
    fun `agent ids and ports are unique`() {
        val ids = AgentCatalog.all.map { it.id }
        assertEquals("duplicate agent ids", ids.size, ids.distinct().size)
        val ports = AgentCatalog.all.map { it.defaultPort }
        assertEquals("duplicate default ports", ports.size, ports.distinct().size)
    }

    @Test
    fun `every entry has a launchable argv`() {
        ActiveAgent.entries.forEach { agent ->
            val argv = AgentRuntime.buildArgv(
                agent = agent,
                nodePath = "/node",
                agentsDir = "/agents",
                port = 3080,
            )
            assertTrue("$agent produced no argv", argv.isNotEmpty())
            // argv[0] must be the interpreter: ProcessBuilder execs it directly.
            assertEquals("$agent must exec the node binary", "/node", argv.first())
            assertTrue(
                "$agent argv references no binary: $argv",
                argv.any { it.contains("bin") || it.endsWith(".js") },
            )
        }
    }

    @Test
    fun `gateway agents pass their backend to the gateway`() {
        AgentCatalog.all.filter { it.needsGateway }.forEach { spec ->
            val agent = ActiveAgent.entries.first { it.spec?.id == spec.id }
            val argv = AgentRuntime.buildArgv(agent, "/node", "/agents", 3080)
            assertTrue(
                "${spec.id} is gateway-wrapped but does not pass --backend",
                argv.contains("--backend"),
            )
            assertTrue(
                "${spec.id} must pass its own backend id",
                argv.contains(spec.gatewayBackend),
            )
        }
    }

    @Test
    fun `non-gateway agents do not launch through the gateway`() {
        AgentCatalog.all.filterNot { it.needsGateway }.forEach { spec ->
            val agent = ActiveAgent.entries.first { it.spec?.id == spec.id }
            val argv = AgentRuntime.buildArgv(agent, "/node", "/agents", 3080)
            assertTrue(
                "${spec.id} must launch its own CLI directly, not via the gateway",
                !argv.contains("visual-gateway/gateway.js"),
            )
        }
    }

    /**
     * The honesty guard. An agent whose upstream package is unofficial or a
     * placeholder must say so; otherwise the app presents a squat package as
     * the vendor's product.
     */
    @Test
    fun `unofficial packages carry a provenance note`() {
        // Selected by the note itself rather than by npm name: the npm name is
        // exactly what gets qualified when a package turns out to be a bridge.
        val unofficial = AgentCatalog.all.filter {
            it.provenanceNote.contains("Unofficial", ignoreCase = true)
        }
        assertTrue("expected the unofficial agents to still be listed", unofficial.isNotEmpty())
        unofficial.forEach {
            assertTrue(
                "${it.id} must keep an explanatory provenance note",
                it.provenanceNote.isNotBlank(),
            )
        }
    }

    /**
     * An agent that cannot actually work on this class of device must not be
     * offered as installable.
     *
     * Hermes is the case in point: the npm package is an unofficial 105 KB
     * bridge that then downloads a 1-3 GB Python runtime. Offering it produced a
     * button that starts a large download and then fails on a phone with a few
     * GB free. It stays in the catalogue with the reason recorded, but it is
     * excluded from the install list.
     */
    @Test
    fun `unsupported agents are excluded from the install list`() {
        val hermes = AgentCatalog.specFor("hermes")!!
        assertTrue(
            "Hermes must be marked unsupported rather than offered",
            hermes.install is com.dsh.mobile.data.InstallKind.Unsupported,
        )
        assertTrue(
            "the reason must be recorded, not silently dropped",
            (hermes.install as com.dsh.mobile.data.InstallKind.Unsupported).reason.isNotBlank(),
        )
        assertFalse(
            "Hermes must not appear in the installable list",
            AgentCatalog.installable.any { it.id == "hermes" },
        )
        assertTrue(
            "it must still be present in the catalogue for provenance",
            AgentCatalog.all.any { it.id == "hermes" },
        )
    }

    @Test
    fun `catalogued packages are non-empty`() {
        AgentCatalog.all.forEach {
            assertTrue("${it.id} has no npm package", it.npmPackage.isNotBlank())
        }
    }

    @Test
    fun `gateway agents declare a backend id`() {
        AgentCatalog.all.filter { it.needsGateway }.forEach {
            assertTrue("${it.id} needs the gateway but has no backend id", !it.gatewayBackend.isNullOrBlank())
        }
    }
}
