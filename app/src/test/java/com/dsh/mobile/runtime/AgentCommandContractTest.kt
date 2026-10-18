package com.dsh.mobile.runtime

import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentCatalog
import com.dsh.mobile.data.AgentMode
import com.dsh.mobile.data.ControlBundle
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the agent argv.
 *
 * The bug this pins down: DSH was invoked as `dsh web --host … --workspace
 * /workspace`. Against the real CLI that is wrong three times over —
 *
 *  1. `web` is a *profile*, not a subcommand, so `dsh web` is an unknown
 *     command and the child exits before it ever binds a port.
 *  2. `--workspace` is not an option of `--profile web`; passing it makes
 *     the CLI print `error: unknown option '--workspace'` and exit non-zero.
 *  3. `--no-open` was missing, so the server tried to launch a desktop
 *     browser on a device that has none.
 *
 * The user-visible symptom of all three was identical — "the agent never
 * opened its port" — with nothing in the UI pointing at argv.
 *
 * The option whitelist below is transcribed from the real
 * `dsh --profile web --help`:
 * ```
 * Usage: dsh --profile web [options]
 * Options:
 *   --host <host>                  bind host
 *   --no-open                      do not open the Web UI in the default browser
 *   --port <port>                  listen port; pass 0 to let the OS pick a free one
 *   --trusted-host <authority...>  extra authority the /api browser-trust fence accepts
 *   -h, --help
 * ```
 */
class AgentCommandContractTest {

    private val node = "/data/user/0/com.dsh.mobile/files/dsh-runtime/node/bin/node"
    private val agents = "/data/user/0/com.dsh.mobile/files/dsh-runtime/agents"
    private val port = 3157

    /**
     * Every option this app is allowed to hand DSH. `--profile` comes from
     * the usage line (`dsh --profile web`); the rest are the profile's own
     * Options block, verbatim from `--help`.
     */
private val dshAllowedOptions = setOf(
        "--profile",
        "--host", "--no-open", "--port", "--trusted-host", "-h", "--help",
    )

    private fun dshArgv(p: Int = port): List<String> = AgentRuntime.buildArgv(
        agent = ActiveAgent.DEEPSEEK_HARNESS,
        nodePath = node,
        agentsDir = agents,
        port = p,
    )

    @Test
    fun `dsh is launched through the profile web form`() {
        val argv = dshArgv()
        val profileAt = argv.indexOf("--profile")
        assertTrue("argv must use --profile: $argv", profileAt >= 0)
        assertEquals("web", argv[profileAt + 1])
    }

    @Test
    fun `web is a profile value and never a bare subcommand`() {
        // `dsh web` is an unknown command — the old code passed it as one,
        // at argv position 2, i.e. not as a value of any option.
        val argv = dshArgv()
        argv.forEachIndexed { i, token ->
            if (token == "web") {
                assertEquals(
                    "'web' is only ever the value of --profile, never a " +
                        "positional subcommand: $argv",
                    "--profile",
                    argv[i - 1],
                )
            }
        }
        assertTrue("'web' must still be requested as a profile", argv.contains("web"))
    }

    @Test
    fun `dsh argv does not contain the unsupported workspace flag`() {
        val argv = dshArgv()
        assertFalse(
            "dsh --profile web rejects --workspace (exits non-zero): $argv",
            argv.contains("--workspace"),
        )
        assertFalse(
            "no '--workspace' in any '--flag=value' spelling either: $argv",
            argv.any { it.startsWith("--workspace=") },
        )
    }

    @Test
    fun `dsh argv disables browser auto-open`() {
        assertTrue(
            "--no-open is required or the server tries to open a desktop " +
                "browser on the device: ${dshArgv()}",
            dshArgv().contains("--no-open"),
        )
    }

    @Test
    fun `dsh binds loopback on the explicit port we already reserved`() {
        val argv = dshArgv()
        assertEquals("127.0.0.1", argv[argv.indexOf("--host") + 1])
        assertEquals(
            "an explicit port must be passed; --port 0 would leave the " +
                "app with nothing to bind the WebView to",
            "$port",
            argv[argv.indexOf("--port") + 1],
        )
    }

    @Test
    fun `dsh argv starts with the node runtime and the installed cli`() {
        val argv = dshArgv()
        // argv[0] must be the INTERPRETER. ProcessBuilder executes argv[0], so
        // leading with node's own flag makes Java try to exec a file called
        // "--expose-internals" and the child dies with ENOENT before node runs.
        // That was a real bug: it left the app on the loading screen forever.
        assertEquals(node, argv[0])
        // dsh refuses to load its plugin tree without it ("cordis-plugin-hmr:
        // --expose-internals is required for HMR service").
        assertEquals("--expose-internals", argv[1])
        // Position-based assertions are brittle: the argv legitimately grows
        // when a V8 memory cap is added. Assert the script is present and comes
        // after the node flags instead of pinning an exact index.
        assertTrue(
            "must run the real lib/bin.js, not an npm shim: $argv",
            argv.any { it.endsWith("dsh/lib/bin.js") },
        )
        val scriptIdx = argv.indexOfFirst { it.endsWith("dsh/lib/bin.js") }
        assertTrue(
            "every node flag must precede the script: $argv",
            argv.subList(1, scriptIdx).all { it.startsWith("--") },
        )
        // CRITICAL on a phone: Node's default V8 heap is ~4200 MB, larger than
        // the whole app memory budget, so an uncapped child grows until the
        // system kills it and takes the user's session with it.
        assertTrue(
            "the agent child must be given an explicit V8 heap cap: $argv",
            argv.any { it.startsWith("--max-old-space-size=") },
        )
    }

    @Test
    fun `native runtime env carries everything the bundled node needs`() {
        // Regression guard for the bug that left a fresh install on the
        // loading screen forever. The guest-shaped env was used for the
        // bundled bionic runtime too, so the child died at startup and the UI
        // waited for a server that could never come up.
        val native = AgentRuntime.NativePaths(
            libsDir = "/data/data/com.dsh.mobile/files/nativelibs-root/nativelibs",
            nativeLibDir = "/data/app/com.dsh.mobile/lib/arm64",
            opensslConf = "/data/data/com.dsh.mobile/files/nativelibs-root/nativelibs/openssl.cnf",
            scratchDir = "/data/data/com.dsh.mobile/files/dsh-runtime/tmp",
            homeDir = "/data/data/com.dsh.mobile/files/dsh-runtime",
        )
        val env = AgentRuntime.buildEnvironment(
            apiEnv = emptyMap(),
            workspace = File("/data/data/com.dsh.mobile/files/workspaces/default"),
            mode = AgentMode.READ_ONLY,
            port = 3080,
            insideProot = false,
            native = native,
        )
        // Without this the loader fails: "library libz.so.1 not found".
        assertTrue(
            "LD_LIBRARY_PATH must include the unpacked asset libs",
            env["LD_LIBRARY_PATH"]!!.startsWith(native.libsDir),
        )
        // Mobile-Harness base env policy, ported verbatim to kill silent
        // background update/telemetry/error-report traffic that is worthless
        // inside PRoot on a phone and slows startup.
        assertEquals("DISABLE_AUTOUPDATER", "1", env["DISABLE_AUTOUPDATER"])
        assertEquals("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1", env["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"])
        assertEquals("DISABLE_TELEMETRY", "1", env["DISABLE_TELEMETRY"])
        assertEquals("DISABLE_ERROR_REPORTING", "1", env["DISABLE_ERROR_REPORTING"])
        // Without this every run aborts with BIO_new_file:Permission denied,
        // because Termux's Node hardcodes its OpenSSL config to Termux's prefix.
        assertEquals(native.opensslConf, env["OPENSSL_CONF"])
        // os.tmpdir() returns the Termux prefix unless TMPDIR is set.
        assertEquals(native.scratchDir, env["TMPDIR"])
        assertEquals(native.homeDir, env["HOME"])
        // Guest-only variables must NOT leak into the native child: PROOT_TMP_DIR
        // and the guest PATH describe a rootfs this runtime does not have.
        assertFalse("PROOT_TMP_DIR is guest-only", env.containsKey("PROOT_TMP_DIR"))
        assertFalse("guest PATH must not shadow the host PATH", env.containsKey("PATH"))
    }

    @Test
    fun `every dsh option is in the cli help text`() {
        // An option DSH does not know aborts the child at startup, which is
        // the single most expensive bug in this file — assert the whole
        // whitelist rather than one flag at a time.
        val argv = dshArgv()
        // Everything after the entry point is what dsh itself sees; anything
        // before it is consumed by node.
        val entryIdx = argv.indexOfFirst { !it.startsWith("-") && it.endsWith("dsh/lib/bin.js") }
        val dshArgs = argv.drop(entryIdx + 1)
        val unknown = dshArgs.filter { it.startsWith("-") && it !in dshAllowedOptions }
        assertEquals("options not present in `dsh --profile web --help`: $argv", emptyList<String>(), unknown)
        assertTrue(
            "--expose-internals is a node flag and must not reach dsh",
            argv.indexOf("--expose-internals") < entryIdx,
        )
    }

    @Test
    fun `gateway backends keep their own argv unchanged`() {
        // Regression guard for the refactor that split buildArgv out of
        // buildCommand: the proot wrapper moved, these two must not.
        for (agent in listOf(ActiveAgent.CLAUDE_CODE, ActiveAgent.CODEX)) {
            val argv = AgentRuntime.buildArgv(agent, node, agents, port)
            assertTrue(
                "$agent must run the visual gateway: $argv",
                argv.any { it.endsWith("visual-gateway/gateway.js") },
            )
            assertTrue(
                "$agent gateway node must be heap-capped too: $argv",
                argv.any { it.startsWith("--max-old-space-size=") },
            )
            assertEquals("$port", argv[argv.indexOf("--port") + 1])
            assertEquals("127.0.0.1", argv[argv.indexOf("--host") + 1])
        }
        assertEquals("claude", dshArgvFor(ActiveAgent.CLAUDE_CODE).let {
            it[it.indexOf("--backend") + 1]
        })
        assertEquals("codex", dshArgvFor(ActiveAgent.CODEX).let {
            it[it.indexOf("--backend") + 1]
        })
    }

    private fun dshArgvFor(agent: ActiveAgent): List<String> =
        AgentRuntime.buildArgv(agent, node, agents, port)

    @Test
    fun `antigravity launches its native ELF binary directly after --`() {
        // antigravity is a bionic-native ELF (gatewayLaunch==null, relativeBinary
        // = agy/bin/agy). Unlike codex/claude it must NOT be run through node as
        // the backend command: the gateway execs the binary path itself, so the
        // path after `--` is the raw ELF, not a .js file.
        val argv = AgentRuntime.buildArgv(
            agent = ActiveAgent.ANTIGRAVITY,
            nodePath = node,
            agentsDir = agents,
            port = port,
            control = AgentCatalog.ControlAdapter.bundleForMode(
                "antigravity", AgentMode.PLAN,
            ),
        )
        // Gateway wrapper, heap-capped like every backend.
        assertTrue(argv.any { it.endsWith("visual-gateway/gateway.js") })
        assertTrue(argv.any { it.startsWith("--max-old-space-size=") })
        assertEquals("antigravity", argv[argv.indexOf("--backend") + 1])
        assertEquals("$port", argv[argv.indexOf("--port") + 1])
        assertEquals("127.0.0.1", argv[argv.indexOf("--host") + 1])
        // Native binary, not a .js entry: the bug this pins down is routing the
        // ELF through `node codex.js`-style launch and getting a "cannot execute
        // binary" abort. The post-`--` command is the binary path verbatim.
        val sep = argv.indexOf("--")
        assertTrue("-- separator must gate the backend command", sep >= 0)
        val binary = argv[sep + 1]
        assertTrue(
            "backend command must be the native ELF, not a .js script: $binary",
            binary.endsWith("agy/bin/agy"),
        )
        assertFalse(
            "native ELF must not be wrapped in node: $argv",
            binary.endsWith(".js"),
        )
        // PLAN -> mode=plan, effort=medium, surfaced as real agy flags.
        assertEquals("plan", argv[argv.indexOf("--mode") + 1])
        assertEquals("medium", argv[argv.indexOf("--effort") + 1])
    }

    @Test
    fun `antigravity resumes a conversation with the captured id`() {
        // Mobile-Harness `antigravityCommand(model, effort, conversationId)`
        // appends `--resume <id>`; the id is captured from the agent's
        // `Initialized` event and persisted so a mode change / respawn does not
        // nuke the thread. Here we pin the arg mapping (the capture side lives
        // in AgentRuntimeService + AgentState).
        val argv = AgentRuntime.buildArgv(
            agent = ActiveAgent.ANTIGRAVITY,
            nodePath = node,
            agentsDir = agents,
            port = port,
            control = ControlBundle(mode = "plan", effort = "medium", resumeId = "conv-123"),
        )
        // Resume flag lives with the other antigravity flags, after `--`.
        assertEquals("conv-123", argv[argv.indexOf("--resume") + 1])
        // And it is NOT present when no id was captured — a null resume must
        // never emit a bare `--resume` (agy would reject the empty arg).
        val noResume = AgentRuntime.buildArgv(
            agent = ActiveAgent.ANTIGRAVITY,
            nodePath = node,
            agentsDir = agents,
            port = port,
            control = AgentCatalog.ControlAdapter.bundleForMode("antigravity", AgentMode.PLAN),
        )
        assertFalse("--resume must be omitted when there is no id: $noResume", noResume.contains("--resume"))
    }

    @Test
    fun `claude resumes with --resume and codex opencode decline the flag`() {
        // Pinned against the vendors' own documentation:
        //  - Claude Code: `claude --resume <session-id>` (session docs).
        //  - OpenCode: continue is `--session`/`-s`, but only on
        //    run/tui/attach - NOT on the `opencode serve` mode this app
        //    launches, and that branch does not apply argvFor at all.
        //  - Codex: resume is the SUBCOMMAND `codex exec resume <id>`, not a
        //    trailing flag this append-only argv builder can express.
        // A null mapping is the safe outcome: it starts a fresh session rather
        // than handing the CLI an unknown flag, which exits non-zero and reads
        // to the user as "the agent is installed and nothing works".
        assertEquals("--resume", AgentCatalog.ControlAdapter.resumeIdArgFor("claude"))
        assertNull(AgentCatalog.ControlAdapter.resumeIdArgFor("codex"))
        assertNull(AgentCatalog.ControlAdapter.resumeIdArgFor("opencode"))
        assertNull(AgentCatalog.ControlAdapter.resumeIdArgFor("dsh"))

        val claude = AgentRuntime.buildArgv(
            agent = ActiveAgent.CLAUDE_CODE,
            nodePath = node,
            agentsDir = agents,
            port = port,
            control = ControlBundle(mode = "plan", resumeId = "sess-9f8e7d6c"),
        )
        assertEquals("sess-9f8e7d6c", claude[claude.indexOf("--resume") + 1])

        // A codex argv carrying a resumeId must gain no resume token at all.
        val codex = AgentRuntime.buildArgv(
            agent = ActiveAgent.CODEX,
            nodePath = node,
            agentsDir = agents,
            port = port,
            control = ControlBundle(mode = "plan", resumeId = "thread-12345678"),
        )
        assertFalse("codex must not emit a resume token: $codex", codex.contains("thread-12345678"))
    }

    @Test
    fun `an agent whose binary is missing is never spawned`() {
        // VERIFIED ON DEVICE: only `dsh` and the gateway are bundled, but the
        // app could be configured for Claude Code. It spawned the gateway
        // pointed at a nonexistent binary and the user saw a blank page with
        // no error at all. Resolution must be driven by what is on disk.
        val dir = Files.createTempDirectory("agents").toFile()
        val dsh = dir.toPath().resolve("dsh/lib/bin.js")
        Files.createDirectories(dsh.parent)
        Files.write(dsh, "// entry".toByteArray())

        assertEquals(
            listOf(ActiveAgent.DEEPSEEK_HARNESS),
            AgentRuntime.installedAgents(dir),
        )
        assertFalse(AgentRuntime.isAgentInstalled(ActiveAgent.CLAUDE_CODE, dir))
        // Preferred-but-absent falls back to something real.
        assertEquals(
            ActiveAgent.DEEPSEEK_HARNESS,
            AgentRuntime.resolveRunnableAgent(ActiveAgent.CLAUDE_CODE, dir),
        )
        // Preferred-and-present is left alone.
        assertEquals(
            ActiveAgent.DEEPSEEK_HARNESS,
            AgentRuntime.resolveRunnableAgent(ActiveAgent.DEEPSEEK_HARNESS, dir),
        )
        // Nothing installed: no silent "run a missing binary".
        val empty = Files.createTempDirectory("empty-agents").toFile()
        assertTrue(AgentRuntime.installedAgents(empty).isEmpty())
        assertEquals(
            ActiveAgent.CLAUDE_CODE,
            AgentRuntime.resolveRunnableAgent(ActiveAgent.CLAUDE_CODE, empty),
        )
    }
}
