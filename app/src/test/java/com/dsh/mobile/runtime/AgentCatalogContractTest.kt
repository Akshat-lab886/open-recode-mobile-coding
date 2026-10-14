package com.dsh.mobile.runtime

import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentCatalog
import com.dsh.mobile.data.AgentMode
import com.dsh.mobile.data.ControlBundle
import com.dsh.mobile.data.ControlSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /**
     * npm-installed agents must be looked for where npm actually puts them.
     *
     * VERIFIED by running `npm install -g <pkg> --prefix <dir>` for real:
     *
     *   <dir>/bin/codex -> ../lib/node_modules/@openai/codex/bin/codex.js
     *   <dir>/bin/claude -> ../lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe
     *
     * The catalogue used to say `<dir>/claude/bin/claude` and `<dir>/codex/bin/codex`.
     * npm never creates those directories, so both the install check and the
     * launch check failed forever - which is exactly why only DSH ever ran, and
     * why every optional agent looked "not installed" no matter what the user
     * selected.
     */
    @Test
    fun `npm agents resolve to the npm prefix layout`() {
        // DSH ships as a bundled payload and has two legitimate layouts, so it
        // is deliberately exempt; this rule is about the optional npm agents.
        AgentCatalog.all
            .filter { it.install is com.dsh.mobile.data.InstallKind.NPM && it.id != "dsh" }
            .forEach { spec ->
                assertTrue(
                    "${spec.id} must live under the npm global bin/ directory, " +
                        "not a per-agent subdirectory npm never creates",
                    spec.relativeBinary.startsWith("bin/"),
                )
            }
    }

    @Test
    fun `optional agents use catalog paths rather than the legacy switch`() {
        val source = File("src/main/java/com/dsh/mobile/runtime/AgentRuntime.kt").readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        listOf("claude/bin/claude", "codex/bin/codex", "opencode/bin/opencode").forEach {
            assertFalse(
                "the legacy fallback still looks for $it, which npm never creates",
                source.contains("\"$it\""),
            )
        }
    }

    @Test
    fun `download size only charges agents not yet installed`() {
        assertEquals(0L, AgentCatalog.downloadBytes(setOf("codex"), setOf("codex")))
        assertEquals(
            AgentCatalog.specFor("dsh")!!.approxInstallBytes,
            AgentCatalog.downloadBytes(setOf("codex", "dsh"), setOf("codex")),
        )
        assertEquals(
            AgentCatalog.specFor("opencode")!!.approxInstallBytes +
                AgentCatalog.specFor("antigravity")!!.approxInstallBytes,
            AgentCatalog.downloadBytes(setOf("opencode", "antigravity"), emptySet()),
        )
    }

    @Test
    fun `ControlAdapter emits real codex argv not shebang flags`() {
        val a = AgentCatalog.ControlAdapter
        // Plan mode -> collaboration_mode="plan" (the real codex arg, verified in
        // codex's flag parser), NOT a fake flag.
        assertEquals(
            listOf("-c", "collaboration_mode=\"plan\""),
            a.argvFor("codex", ControlBundle(mode = "plan")),
        )
        // Full-network mode -> sandbox full + approval-mode disable (real codex args).
        val full = a.argvFor("codex", ControlBundle(mode = "full-network"))
        assertTrue(full.contains("--sandbox"))
        assertTrue(full.contains("workspace-full"))
        assertTrue(full.contains("--approval-mode"))
        assertTrue(full.contains("disable"))
        // Model-only override still carries the default approval mode.
        assertEquals(
            listOf("-m", "gpt-4", "--approval-mode", "strict"),
            a.argvFor("codex", ControlBundle(model = "gpt-4")),
        )
    }

    @Test
    fun `ControlAdapter emits real claude argv and skips unsupported`() {
        val a = AgentCatalog.ControlAdapter
        assertEquals(
            listOf("--dangerously-skip-permissions"),
            a.argvFor("claude", ControlBundle(mode = "bypass")),
        )
        assertEquals(
            listOf("--effort", "high"),
            a.argvFor("claude", ControlBundle(effort = "high")),
        )
        // dsh: preset handled separately; unsupported: no argv/env.
        assertTrue(a.argvFor("dsh", ControlBundle(mode = "ptc")).isEmpty())
        assertTrue(a.envFor("dsh", ControlBundle()).isEmpty())
        assertTrue(a.argvFor("hermes", ControlBundle()).isEmpty())
    }

    @Test
    fun `control surface only exposes real options per agent`() {
        val dshModes = AgentCatalog.specFor("dsh")!!.control.modes.map { it.value }
        assertEquals(listOf("ptc", "standard", "minimal", "cordis"), dshModes)
        val codex = AgentCatalog.specFor("codex")!!
        assertTrue(codex.control.modes.any { it.value == "plan" })
        assertTrue(codex.control.hasModel)
        val agy = AgentCatalog.specFor("antigravity")!!
        assertTrue(agy.control.modes.any { it.value == "plan" })
        assertTrue(agy.control.hasModel)
        assertTrue(agy.control.efforts.any { it.value == "high" })
        assertEquals(ControlSpec.None, AgentCatalog.specFor("claude")!!.control)
        assertEquals(ControlSpec.None, AgentCatalog.specFor("hermes")!!.control)
    }

    @Test
    fun `bundleForMode maps unified modes to each harness real flags`() {
        val a = AgentCatalog.ControlAdapter
        // codex: PLAN -> collaboration_mode plan; CODE_EXECUTION -> full-network.
        assertEquals(listOf("-c", "collaboration_mode=\"plan\""), a.argvFor("codex", a.bundleForMode("codex", AgentMode.PLAN)))
        assertTrue(a.argvFor("codex", a.bundleForMode("codex", AgentMode.CODE_EXECUTION)).contains("--sandbox"))
        // claude: code-exec -> bypass permissions + high effort (real flags).
        assertEquals(
            listOf("--effort", "high", "--dangerously-skip-permissions"),
            a.argvFor("claude", a.bundleForMode("claude", AgentMode.CODE_EXECUTION)),
        )
        // antigravity: plan -> --mode plan + --effort medium (bundle sets effort).
        assertEquals(
            listOf("--mode", "plan", "--effort", "medium"),
            a.argvFor("antigravity", a.bundleForMode("antigravity", AgentMode.PLAN)),
        )
        // dsh: preset path, no argv.
        assertTrue(a.argvFor("dsh", a.bundleForMode("dsh", AgentMode.PLAN)).isEmpty())
    }

    @Test
    fun `bundleForMode effort override is independent of mode`() {
        val a = AgentCatalog.ControlAdapter
        // claude PLAN has no effort by default (null); override forces --effort low.
        assertNull(a.bundleForMode("claude", AgentMode.PLAN).effort)
        assertEquals("low", a.bundleForMode("claude", AgentMode.PLAN, effort = "low").effort)
        // argv reflects the override, not the mode-derived default.
        assertEquals(
            listOf("--effort", "low", "--permission-mode", "plan"),
            a.argvFor("claude", a.bundleForMode("claude", AgentMode.PLAN, effort = "low")),
        )
    }

    @Test
    fun `DSH mode round-trips through presets and CREATIVE maps to cordis`() {
        val a = AgentCatalog.ControlAdapter
        val dsh = ActiveAgent.DEEPSEEK_HARNESS
        assertEquals("ptc", a.modeFor(dsh, AgentMode.CODE_EXECUTION))
        assertEquals("standard", a.modeFor(dsh, AgentMode.PLAN))
        assertEquals("minimal", a.modeFor(dsh, AgentMode.READ_ONLY))
        assertEquals("cordis", a.modeFor(dsh, AgentMode.CREATIVE))
        // Inverse round-trips for every preset.
        assertEquals(AgentMode.CODE_EXECUTION, a.modeFor(dsh, "ptc"))
        assertEquals(AgentMode.PLAN, a.modeFor(dsh, "standard"))
        assertEquals(AgentMode.READ_ONLY, a.modeFor(dsh, "minimal"))
        assertEquals(AgentMode.CREATIVE, a.modeFor(dsh, "cordis"))
    }
}
