package com.dsh.mobile.runtime

import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(
            "must run the real lib/bin.js, not an npm shim: $argv",
            argv[2].endsWith("dsh/lib/bin.js"),
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
            assertTrue("$agent must run the visual gateway: $argv", argv[1].endsWith("visual-gateway/gateway.js"))
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
}