package com.dsh.mobile.runtime

import android.content.Context
import android.system.Os
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentMode
import java.io.File
import java.net.ServerSocket

/**
 * Owns the embedded rootless Linux/Node (PRoot) runtime layout and the
 * construction of agent child-process commands.
 *
 * The real process spawning happens in [AgentRuntimeService] via
 * [ProcessBuilder]; this object keeps every path, port, and env decision
 * in one testable place.
 *
 * Directory layout (app-private, no root required):
 * ```
 * filesDir/
 *   dsh-runtime/        <- PRoot distro root (mounted as / inside proot)
 *     node/             <- embedded Node 20 binary
 *     agents/           <- npm-installed agent CLIs (dsh, claude, codex, opencode)
 *   workspaces/         <- default workspace root if the user picked none
 *   dsh-logs/           <- stdout/stderr capture per agent
 * ```
 */
object AgentRuntime {

    const val LOOPBACK_HOST = "127.0.0.1"
    const val PORT_RANGE_START = 3080
    const val PORT_RANGE_END = 3999

    fun runtimeRoot(context: Context): File =
        File(context.filesDir, "dsh-runtime").apply { mkdirs() }

    fun nodeBinary(context: Context): File =
        File(runtimeRoot(context), "node/bin/node")

    fun agentsDir(context: Context): File =
        File(runtimeRoot(context), "agents").apply { mkdirs() }

    fun defaultWorkspace(context: Context): File =
        File(context.filesDir, "workspaces").apply { mkdirs() }

    fun logFile(context: Context, agent: ActiveAgent): File =
        File(context.filesDir, "dsh-logs/${agent.name.lowercase()}.log").apply {
            parentFile?.mkdirs()
        }

    /** Finds a free loopback port in the app's private range. */
    fun findFreePort(preferred: Int = 0): Int {
        if (preferred > 0 && isPortFree(preferred)) return preferred
        for (port in PORT_RANGE_START..PORT_RANGE_END) {
            if (isPortFree(port)) return port
        }
        throw IllegalStateException("No free loopback port available")
    }

    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port).close()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Builds the argv for the requested agent. DSH serves its real web UI
     * (`npx @deepseek-ai/dsh web`); CLI-only agents are wrapped by the
     * embedded visual proxy gateway ([com.dsh.mobile.proxy.VisualGateway]).
     *
     * Agent modes are NOT passed as CLI flags — unknown flags would crash the
     * child. Modes are enforced at the app layer (gateway approval strictness
     * + DSH_AGENT_MODE env in [buildEnvironment]).
     */
    fun buildCommand(
        context: Context,
        agent: ActiveAgent,
        workspace: File,
        port: Int,
    ): List<String> {
        val node = nodeBinary(context).absolutePath
        val agents = agentsDir(context).absolutePath
        val proot = File(runtimeRoot(context), "proot").absolutePath

        fun prootWrap(inner: List<String>): List<String> {
            val rootFs = runtimeRoot(context).absolutePath
            // If the proot binary hasn't been extracted yet, run natively.
            if (!File(proot).canExecute()) return inner
            return listOf(
                proot,
                "-r", rootFs,
                "-b", "${workspace.absolutePath}:/workspace",
                "-w", "/workspace",
            ) + inner
        }

        return when (agent) {
            ActiveAgent.DEEPSEEK_HARNESS -> prootWrap(
                listOf(
                    node, "$agents/dsh/bin/dsh.js",
                    "web",
                    "--host", LOOPBACK_HOST,
                    "--port", port.toString(),
                    "--workspace", "/workspace",
                ),
            )
            // CLI-only agents: boot behind the visual gateway so the user
            // still gets a chat-and-file interface instead of a terminal.
            // The gateway wrapper serves its chat UI on the loopback port
            // (health-checked like every other backend) while keeping the
            // child's stdin/stdout pipes attached for approval interception.
            ActiveAgent.CLAUDE_CODE -> prootWrap(
                listOf(
                    node, "$agents/visual-gateway/gateway.js",
                    "--backend", "claude",
                    "--port", port.toString(),
                    "--host", LOOPBACK_HOST,
                    "--", "$agents/claude/bin/claude",
                ),
            )
            ActiveAgent.CODEX -> prootWrap(
                listOf(
                    node, "$agents/visual-gateway/gateway.js",
                    "--backend", "codex",
                    "--port", port.toString(),
                    "--host", LOOPBACK_HOST,
                    "--", "$agents/codex/bin/codex",
                ),
            )
            ActiveAgent.OPENCODE -> prootWrap(
                listOf(
                    node, "$agents/opencode/bin/opencode",
                    "serve",
                    "--host", LOOPBACK_HOST,
                    "--port", port.toString(),
                    "--workspace", "/workspace",
                ),
            )
        }
    }

    /** CLI-only agents render through the in-app visual gateway (no TCP server). */
    fun isGatewayAgent(agent: ActiveAgent): Boolean =
        agent == ActiveAgent.CLAUDE_CODE || agent == ActiveAgent.CODEX

    /** Full environment for the child: silent key injection + workspace config. */
    fun buildEnvironment(
        apiEnv: Map<String, String>,
        workspace: File,
        mode: AgentMode,
        port: Int,
    ): Map<String, String> = buildMap {
        putAll(apiEnv)
        put("DSH_WORKSPACE", workspace.absolutePath)
        put("DSH_AGENT_MODE", mode.name)
        put("PORT", port.toString())
        put("HOST", LOOPBACK_HOST)
        put("TERM", "xterm-256color")
        // Keep child traffic on-device: the UI is served via loopback only.
        put("NO_UPDATE_CHECK", "1")
    }

    // -- runtime payload ---------------------------------------------------

    fun runtimeAssets(context: Context): RuntimeAssets = RuntimeAssets(runtimeRoot(context))

    /**
     * Reads `assets/runtime-assets.json`. Returns null (rather than throwing)
     * so a malformed manifest degrades to "unknown payload" instead of
     * crashing the boot sequence.
     */
    fun loadManifest(context: Context): RuntimeAssetManifest? = try {
        context.assets.open(MANIFEST_ASSET).use {
            RuntimeAssetManifest.parse(it.readBytes().decodeToString())
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Copies the gateway bundle out of the APK into the runtime agents dir.
     * [buildCommand] points argv at `agents/visual-gateway/gateway.js`, which
     * does not exist on the filesystem until this runs.
     */
    fun stageBundledAssets(context: Context) {
        runtimeAssets(context).stageBundledAssets(
            openSource = { rel ->
                try {
                    context.assets.open(rel)
                } catch (_: Exception) {
                    null
                }
            },
            assetSink = { rel -> File(agentsDir(context), rel) },
        )
    }

    /** What the payload looks like right now, for status/diagnostics UI. */
    fun payloadState(context: Context): RuntimePayloadState {
        val manifest = loadManifest(context) ?: return RuntimePayloadState.Unknown
        return runtimeAssets(context).evaluate(manifest)
    }

    const val MANIFEST_ASSET = "runtime-assets.json"
}

/** Best-effort helper to mirror a SAF tree into the loopback workspace mount. */
fun mirrorSafTreeToWorkspace(context: Context, sourceTree: File, workspace: File) {
    // SAF URIs are resolved by the ViewModel into an app-private staging dir
    // before this is called; here we simply ensure the mount point exists and
    // record the mapping for the gateway's file browser.
    workspace.mkdirs()
    try {
        Os.symlink(sourceTree.absolutePath, File(workspace, ".saf-source").absolutePath)
    } catch (_: Exception) {
        // Symlink may already exist — harmless.
    }
}
